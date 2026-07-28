# Canal 同步 MySQL 到 Elasticsearch 故障排查

## 阅读前需要知道什么

### MySQL binlog 是什么

binlog 是 MySQL 按时间记录数据变化的日志。执行 `INSERT`、`UPDATE`、`DELETE` 后，MySQL 会把变化写入 binlog。Canal 读取的是 binlog，不会定期扫描整张 MySQL 表。

因此：

- Canal 正常运行期间产生的新变化，可以增量同步。
- 早于 Canal 当前消费位点的数据，不会因为客户端后来启动而自动补进 ES。
- 已经错过的数据需要全量导入，或者通过受控的 UPDATE 重新产生 binlog。

### Canal 是什么

Canal 模拟 MySQL 从库协议，从 MySQL 拉取 binlog，再把解析后的行变化提供给客户端。本项目中的 Canal Server 监听 TCP 11111，实例名是 `example`。

Canal 有两个容易混淆的连接：

1. Canal Server 连接 MySQL 3306，读取 binlog。
2. `networkdisk-files` 连接 Canal 11111，消费解析后的事件。

任意一段断开，ES 都不会更新。

### 消费位点是什么

消费位点可以理解为“binlog 读到哪里了”。它通常由 binlog 文件名和 position 组成，例如：

```text
binlog.000001:1160
```

Canal 和客户端会保存位点，重启后继续读取。随意删除 `meta.dat` 或修改 position 可能造成：

- 位点向后跳：旧数据被跳过。
- 位点向前退：旧事件被重复消费。

所以不能把“删除位点文件”当成通用重启办法。

### 全量同步和增量同步的区别

- 全量同步：把 MySQL 当前已有数据完整写入 ES，通常用于首次建索引或补历史数据。
- 增量同步：只同步某个位点之后的新变化，本项目由 Canal 完成。

正确顺序通常是“先全量建立 ES 基线，再启动 Canal 持续同步增量”。Canal 本身不能替代全量同步。

### ack 和 rollback 是什么

`networkdisk-files` 从 Canal 拉取一批事件后：

- ES 写入成功则 `ack`，推进客户端消费进度。
- ES 写入失败则 `rollback`，让 Canal 下次重新发送该批事件。

这能避免 ES 临时失败时直接丢数据，但前提是 Canal 客户端仍在运行并且消费位点没有被人为重置。

### DML 和 DDL 的区别

- DML：修改数据，例如 `INSERT`、`UPDATE`、`DELETE`。
- DDL：修改表结构，例如 `ALTER TABLE ADD COLUMN`。

普通 DML 会产生待同步的行事件。DDL 会改变列结构；如果 Canal 仍使用旧表结构解析新 binlog，就可能出现 `16 vs 14` 这样的列数不一致错误。

### `sh` 和 Bash 的区别

`sh` 表示 POSIX Shell。在 Ubuntu 上，`/bin/sh` 通常指向精简的 `dash`。Bash 是功能更多的 Shell，支持 `let`、部分扩展条件表达式等语法。

脚本第一行的：

```bash
#!/bin/bash
```

只有直接执行脚本或使用 Bash 时才生效。执行 `sh bin/stop.sh` 会强制交给 `dash`，忽略脚本要求的 Bash，因此会出现 `unexpected operator`、`let: not found`。

本项目运行 Canal 脚本时使用：

```bash
bash bin/stop.sh
bash bin/startup.sh
```

## 同步链路

本项目的增量同步链路如下：

```text
MySQL network_disk.user_file
  -> binlog
  -> Canal Server（TCP 11111，destination=example）
  -> networkdisk-files 中的 CanalUserFileEsSyncRunner
  -> Easy-ES
  -> Elasticsearch user_file_index
```

这里不是 MySQL 主动向 Canal 推送数据，而是 Canal 伪装成 MySQL 从库读取 binlog；`networkdisk-files` 再作为 Canal 客户端消费变更并写入 ES。

## 标准排查顺序

排查时按链路从左到右进行，不要一开始就删除位点或重建 ES。

### 第一步：确认 MySQL 能产生 binlog

```bash
mysql -uroot -p -e "
SHOW VARIABLES WHERE Variable_name IN
('log_bin', 'binlog_format', 'binlog_row_image');
SHOW MASTER STATUS;
"
```

应重点确认：

```text
log_bin          ON
binlog_format    ROW
binlog_row_image FULL
```

若 MySQL 没有开启 ROW binlog，Canal 无法获得完整的行变化。

### 第二步：确认 Canal 正确连接 MySQL

```bash
tail -50 /home/madm/es-demo/canal-deployer/logs/example/example.log
```

正常标志：

```text
find start position successfully
the next step is binlog dump
```

异常标志包括：

```text
Access denied
column size is not match
Could not find first log file
```

### 第三步：确认 11111 只有一个 Canal

```bash
sudo ss -lntp | grep ':11111'
docker ps -a --filter name=canal
ps -ef | grep '[c]anal.deployer'
```

本项目当前使用本地 Canal，预期 11111 的监听者是 `java`：

```text
127.0.0.1:11111 ... users:(("java",...))
```

若显示 `docker-proxy`，说明应用连接的是 Docker Canal。不能同时运行 Docker Canal 和本地 Canal。

### 第四步：确认应用已订阅 Canal

```bash
grep -E \
  "canal sync started|canal sync stopped|canal disconnected|batch handle error|ERROR" \
  /data1/jinbai/NetworkDisk-main/networkdisk-business/networkdisk-files/nohup.out \
  | tail -50
```

正常标志：

```text
canal sync started. destination=example, filter=network_disk\.user_file
```

如果只有 `canal sync stopped unexpectedly`，旧代码中的同步线程已经退出，需要修复 Canal 后重启 `networkdisk-files`。

### 第五步：确认 ES 可用并检查数量

```bash
curl -s "http://127.0.0.1:9200/_cluster/health?pretty"
curl -s "http://127.0.0.1:9200/user_file_index/_count?pretty"
```

ES 集群可用但数量不增长时，继续做单条端到端探针，不要马上更新全表。

### 第六步：用一条数据定位断点

```bash
TEST_ID=$(mysql -uroot -p -Nse \
  "SELECT id FROM network_disk.user_file ORDER BY id DESC LIMIT 1")

mysql -uroot -p -e \
  "UPDATE network_disk.user_file
   SET gmt_modified = DATE_ADD(gmt_modified, INTERVAL 1 SECOND)
   WHERE id = ${TEST_ID};"

sleep 3

curl -s \
  "http://127.0.0.1:9200/user_file_index/_doc/${TEST_ID}?pretty"
```

判断方法：

- `"found": true`：实时链路正常，之前的数据早于当前位点，需要补历史数据。
- 应用出现 `batch handle error`：Canal 已交付事件，但 ES 写入失败，检查异常堆栈。
- Canal 没有新事件：检查订阅过滤、MySQL binlog 和 Canal 位点。
- 应用出现 `disconnected`：检查 11111 的监听者和 Canal Server。

## 2026-07-25 故障现象

- 修改 MySQL 的 `user_file` 后，ES 的 `user_file_index` 不更新。
- Canal 曾报：

  ```text
  column size is not match for table:network_disk.user_file,16 vs 14
  ```

- `networkdisk-files` 曾报：

  ```text
  CanalClientException: java.net.ConnectException: 拒绝连接
  canal sync stopped unexpectedly
  ```

- 再次启动应用后曾报：

  ```text
  java.io.IOException: end of stream when reading header
  ```

## 原因分析

### 1. Canal 的表结构元数据与 binlog 不一致

`user_file` 发生过 DDL 变化，当前表包含 `file_id`、`update_time` 等生成列。Canal 读取到的行事件有 16 列，但当时使用的表结构元数据只有 14 列，因此无法解析 binlog。

这通常发生在开发环境修改表结构后，Canal 仍持有旧的表结构或旧的消费位点。不能只看 Canal 进程是否存在，必须确认实例日志已经进入 binlog dump 且没有继续出现列数错误。

正常标志：

```text
find start position successfully
the next step is binlog dump
```

### 2. Docker Canal 与本地 Canal 同时运行

当时存在两套 Canal：

- Docker 容器 `canal/canal-server:v1.1.7`
- `/home/madm/es-demo/canal-deployer` 本地 Canal

端口检查显示 11111 实际由 Docker 占用：

```text
0.0.0.0:11111 users:(("docker-proxy",...))
```

本地 Canal 虽然有 Java 进程并能启动 `example` 实例，但无法拥有对外的 11111 监听端口。应用实际连接到了 Docker 暴露的端口，服务端随即关闭连接，于是客户端出现 `end of stream when reading header`。

### 3. 应用连接失败后不会自动重连

`CanalUserFileEsSyncRunner` 的连接和消费循环被同一个外层 `try/catch` 包围。首次连接失败后会记录 `canal sync stopped unexpectedly`，随后工作线程结束。

因此，即使稍后修复或启动 Canal，已经运行的 `networkdisk-files` 也不会恢复同步，必须重启应用。

### 4. Canal 脚本使用了错误的 Shell

执行：

```bash
sh bin/stop.sh
```

会由 Ubuntu 的 `dash` 解释脚本，导致：

```text
unexpected operator
let: not found
```

这些 Canal 脚本应明确使用 Bash：

```bash
bash bin/stop.sh
bash bin/startup.sh
```

## 本次恢复步骤

本次选择保留本地 Canal，停止 Docker Canal：

```bash
docker stop canal

cd /home/madm/es-demo/canal-deployer
bash bin/stop.sh
bash bin/startup.sh
```

确认 11111 由本地 Java 进程监听：

```bash
sudo ss -lntp | grep ':11111'
```

预期结果类似：

```text
127.0.0.1:11111 ... users:(("java",...))
```

检查 Canal 已进入 binlog dump：

```bash
tail -50 /home/madm/es-demo/canal-deployer/logs/example/example.log
```

由于旧同步线程已经退出，还需要重启 `networkdisk-files`：

```bash
cd /data1/jinbai/NetworkDisk-main/networkdisk-business/networkdisk-files

nohup java -jar target/networkdisk-files-1.0.0-SNAPSHOT.jar \
  --easy-es.enable=true \
  --com.disk.canal.enable=true > nohup.out 2>&1 &

sleep 30
grep -E "Started Networkdisk|canal sync started|canal sync stopped|ERROR" nohup.out
```

恢复成功的关键日志：

```text
canal sync started. destination=example, filter=network_disk\.user_file
```

## 端到端验证

以下命令插入一条固定 ID 的测试记录，检查 ES 后再删除。执行前先确认该 ID 不存在：

```bash
TEST_ID=9223372036854775000

mysql -uroot -p -D network_disk -e \
  "SELECT id, filename FROM user_file WHERE id=${TEST_ID};"
```

插入测试记录：

```bash
mysql -uroot -p -D network_disk -e "
INSERT INTO user_file
  (id, user_id, parent_id, real_file_id, filename, folder_flag,
   file_size_desc, file_type, create_user, update_user, deleted, lock_version)
VALUES
  (${TEST_ID}, 0, 0, NULL, 'canal-sync-verify.txt', 1,
   '--', 0, 0, 0, 0, 0);
"
```

等待同步并查询 ES：

```bash
sleep 2
curl -s "http://127.0.0.1:9200/user_file_index/_doc/${TEST_ID}?pretty"
```

预期：

```json
{
  "found": true
}
```

若没有同步，立即同时检查两端日志：

```bash
grep -E "canal sync|ERROR|Exception" \
  /data1/jinbai/NetworkDisk-main/networkdisk-business/networkdisk-files/nohup.out \
  | tail -50

tail -50 /home/madm/es-demo/canal-deployer/logs/example/example.log
```

删除测试数据并验证 ES 删除同步：

```bash
mysql -uroot -p -D network_disk -e \
  "DELETE FROM user_file WHERE id=${TEST_ID};"

sleep 2
curl -s "http://127.0.0.1:9200/user_file_index/_doc/${TEST_ID}?pretty"
```

预期：

```json
{
  "found": false
}
```

## 批量压测数据未进入 ES

### 现象

MySQL 已写入约 10 万条压测数据，应用日志存在：

```text
canal sync started. destination=example, filter=network_disk\.user_file
```

应用没有 `canal sync batch handle error`，但 ES 文档数量始终不增长。

### 判断

这说明 MySQL、Canal 客户端和 ES 的连接均已建立，但这批数据不在 Canal 当前可消费位点之后。常见原因是写入数据时 Canal 尚未正常运行，或者排障期间重置过 Canal 的 binlog 位点。

Canal 是增量订阅工具，不会在客户端连接后自动扫描 MySQL 全表。已经早于当前位点的数据，需要先做全量导入，或重新产生相应的 binlog 事件。

### 先用一条数据验证

不要直接更新 10 万条。先选择最新一条记录，修改其更新时间以产生一条新的 binlog：

```bash
TEST_ID=$(mysql -uroot -p -Nse \
  "SELECT id FROM network_disk.user_file ORDER BY id DESC LIMIT 1")

mysql -uroot -p -e \
  "UPDATE network_disk.user_file
   SET gmt_modified = DATE_ADD(gmt_modified, INTERVAL 1 SECOND)
   WHERE id = ${TEST_ID};"

sleep 3

curl -s \
  "http://127.0.0.1:9200/user_file_index/_doc/${TEST_ID}?pretty"
```

若返回 `"found": true`，说明实时同步链路正常，可以确认先前的批量数据是被当前位点错过，而不是 ES 写入故障。

### 重新触发压测数据

在明确最新 10 万条都是压测数据后，可以更新其 `gmt_modified`，重新产生 binlog：

```bash
mysql -uroot -p -e \
  "UPDATE network_disk.user_file
   SET gmt_modified = DATE_ADD(gmt_modified, INTERVAL 1 SECOND)
   ORDER BY id DESC
   LIMIT 100000;"
```

同步过程中观察 ES 数量：

```bash
watch -n 2 \
  'curl -s http://127.0.0.1:9200/user_file_index/_count'
```

同时观察消费和写入异常：

```bash
tail -f \
  /data1/jinbai/NetworkDisk-main/networkdisk-business/networkdisk-files/nohup.out \
  | grep -E "batch handle error|disconnected|ERROR"
```

本次验证中，单条探针成功进入 ES，随后批量 UPDATE 触发同步，ES 数量开始增长，证明根因是 Canal 消费位点未覆盖原始批量写入。

注意：

- UPDATE 条件必须只覆盖压测数据，不能在生产库无条件更新全表。
- 10 万条单事务会产生较大的 binlog 和同步压力。正式压测应按明确的 ID 范围分批执行。
- 补数据期间不要重启 Canal、删除 `meta.dat` 或修改 binlog 位点。
- 相同 MySQL ID 会写入相同 ES `_id`，重新触发同步会覆盖文档，不会新增重复 ID。

## 防止再次发生

1. 本机只运行一种 Canal 部署方式；本项目当前约定使用 `/home/madm/es-demo/canal-deployer`，Docker Canal 保持停止。
2. 启动顺序固定为 MySQL、Elasticsearch、Canal、`networkdisk-files`。
3. 每次修改 `user_file` 表结构后，检查 Canal 表结构和消费位点。删除 `meta.dat` 或强制修改 binlog 位点可能造成重复消费或丢数据，只能在确认位点并可接受重放的开发环境操作。
4. 服务启动后不能只检查 Java 进程，应同时检查 11111 的端口所有者、Canal 的 binlog dump 日志和应用的 `canal sync started`。
5. `CanalUserFileEsSyncRunner` 已增加断线重连；修改后必须重新打包并重启服务，不能只修改源码。
6. Canal 负责增量同步，不替代首次全量建索引。压测前应先确认 ES 基线数据和 Canal 起始位点，再开始计时写入。
