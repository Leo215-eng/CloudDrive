# ES 高亮依赖不兼容导致搜索回退 MySQL

## 事件摘要

2026-07-25 使用 JMeter 对“ES + Canal 搜索链路”进行压测时，接口没有返回 HTTP 错误，但文件服务持续输出：

```text
ES search failed; falling back to MySQL
java.lang.reflect.UndeclaredThrowableException
...
org.dromara.easyes.core.kernel.WrapperProcessor.initHighlightBuilder
```

这轮请求实际执行的是：

```text
请求
  -> 尝试构建 ES 查询
  -> Easy-ES 构建高亮参数时异常
  -> catch 异常
  -> 回退 MySQL
  -> 返回正常业务响应
```

因此，这轮结果不能作为 ES 性能数据，只能说明 ES 异常时 MySQL 兜底生效。

## 修复状态

2026-07-26 已从 `UserFileESEntity.filename` 删除 Easy-ES 的 `@HighLight` 及对应导入，保留项目已有的手工高亮。执行以下构建命令通过：

```bash
mvn -o -pl networkdisk-business/networkdisk-files -am package -DskipTests
```

构建结果为 `BUILD SUCCESS`。运行时单请求和重新压测仍需按本文“构建和验证”章节执行。

修复后使用相同的 50 线程、10 秒 Ramp-up、180 秒持续时间重新压测：

| 场景 | 请求数 | 平均延迟 | P95 | P99 | 吞吐量 | Error % |
|---|---:|---:|---:|---:|---:|---:|
| 修复后的 ES 优先搜索 | 260142 | 33 ms | 69 ms | 95 ms | 1444.9/s | 0.00% |

相较异常回退时，P95 从 145 ms 降至 69 ms，吞吐量从 543.3/s 提升至 1444.9/s，说明高亮异常及异常日志开销已经消除。

但该结果仍慢于 MySQL-only 的 P95 32 ms 和 3601.8/s，不能据此声称 ES 已降低查询延迟。当前 ES 路径在查询 ES 后，还会执行 `mergeRecentMysqlChanges()` 查询 MySQL 最近变更；有结果时 `fillParentFilename()` 还会再次读取 MySQL。因此测到的是“ES + 一致性补偿 + MySQL 后处理”，不是纯 ES 查询性能。

## 阅读前需要知道什么

### JMeter 的 Error % 不等于内部组件没有异常

JMeter 默认根据 HTTP 请求及断言判断成功或失败。应用内部即使发生 ES 异常，只要捕获异常、成功查询 MySQL 并返回正常响应，JMeter 仍会显示：

```text
Error % = 0.00%
```

所以验证 ES 性能不能只看 Error %，还要确认服务日志没有回退警告，并确认请求确实命中 ES。

### Java 二进制兼容是什么

Java 依赖在编译时会记录要调用的方法签名。例如：

```text
type(Function)
```

如果运行时加载的另一个版本只提供：

```text
type(String)
type(HighlighterType)
```

即使类名相同，JVM 也找不到编译时要求的方法，通常会抛出 `NoSuchMethodError`。异常经过动态代理后，外层可能表现为 `UndeclaredThrowableException`。

### Easy-ES 的 `@HighLight` 做了什么

实体字段加上 `@HighLight` 后，Easy-ES 会在每次查询时自动构建 Elasticsearch 高亮参数。它不是单纯的结果展示注解，而是会进入：

```text
WrapperProcessor.setHighLight
  -> WrapperProcessor.initHighlightBuilder
```

本项目已经在 `UserFileServiceImpl.applyHighlight()` 中根据搜索词手工生成高亮 HTML，因此实体上的 Easy-ES 高亮属于重复实现。

### Canal 与这次异常没有直接关系

Canal 负责把 MySQL 增量变化同步到 ES。当前异常发生在应用读取 ES、构建搜索请求的阶段。即使 Canal 同步完全正常，只要查询构建失败，搜索仍会回退 MySQL。

## 故障表现

同样使用 50 线程、10 秒 Ramp-up、180 秒持续时间：

| 场景 | 请求数 | 平均延迟 | P95 | P99 | 吞吐量 | Error % |
|---|---:|---:|---:|---:|---:|---:|
| MySQL-only | 648396 | 13 ms | 32 ms | 46 ms | 3601.8/s | 0.00% |
| 标称“ES + Canal”，实际异常回退 MySQL | 97845 | 89 ms | 145 ms | 187 ms | 543.3/s | 0.00% |

第二轮比 MySQL-only 更慢，不代表 ES 比 MySQL 慢。它额外执行了：

1. 构建 ES 查询；
2. 抛出并包装异常；
3. 为大量请求打印完整异常堆栈；
4. 再执行 MySQL 查询。

大量同步日志 I/O 也会继续放大延迟、降低吞吐。

## 根因

### 1. Easy-ES 与运行时 Elasticsearch Java Client 不兼容

项目使用：

```text
org.dromara.easy-es:easy-es-core:3.0.2
```

Easy-ES 3.0.2 的父 POM 声明：

```text
elasticsearch-java = 7.17.28
```

但项目经过 Spring Boot 依赖管理后，实际解析并打包的是：

```text
co.elastic.clients:elasticsearch-java:8.10.4
```

Easy-ES 3.0.2 的高亮字节码调用：

```text
HighlightField.Builder.type(Function)
```

而运行时 `elasticsearch-java 8.10.4` 只提供：

```text
type(String)
type(HighlighterType)
```

因此，只要 `@HighLight` 激活 Easy-ES 自动高亮路径，就会在构建查询时发生二进制方法不兼容。

### 2. 项目同时维护了两套高亮

`UserFileESEntity.filename` 上配置了：

```java
@HighLight(mappingField = "filename")
```

搜索结果返回前，`UserFileServiceImpl` 又执行：

```java
applyHighlight(result, searchTerms);
```

后者已经满足页面高亮需求。继续启用 Easy-ES 自动高亮没有必要，却触发了不兼容代码路径。

### 3. 兜底逻辑掩盖了 ES 故障

搜索代码会捕获 ES 异常：

```java
catch (Exception e) {
    log.warn("ES search failed; falling back to MySQL", e);
    result = doSearch(context, searchTerms);
}
```

这保证了搜索可用性，却意味着监控和压测不能只依赖 HTTP 成功率。

## 排查过程

### 第一步：确认鉴权通过

日志出现：

```text
login pre intercept，uri：[/api/v1/files/file/search]，pass
```

说明本轮请求不是登录拦截器的假成功。

### 第二步：从回退日志定位异常阶段

关键日志为：

```text
ES search failed; falling back to MySQL
WrapperProcessor.initHighlightBuilder
WrapperProcessor.setHighLight
BaseEsMapperImpl.selectList
```

异常发生在 ES 请求发出前的高亮构建阶段，不是 Canal 消费、ES 索引数量或 MySQL binlog 问题。

### 第三步：检查实体高亮配置

```bash
rg -n "@HighLight|applyHighlight|buildHighlightFilename" \
  networkdisk-business/networkdisk-files/src/main/java
```

可以看到 Easy-ES 注解高亮和项目手工高亮同时存在。

### 第四步：确认运行时依赖版本

```bash
mvn -o -pl networkdisk-business/networkdisk-files -am \
  dependency:tree \
  -Dincludes=co.elastic.clients:elasticsearch-java,org.dromara.easy-es:easy-es-core \
  -DskipTests
```

关键结果：

```text
easy-es-core:3.0.2
elasticsearch-java:8.10.4
```

### 第五步：核对二进制方法签名

Easy-ES 高亮字节码要求：

```text
HighlightField$Builder.type(java.util.function.Function)
```

运行时 Elasticsearch Java Client 提供：

```text
HighlightBase$AbstractBuilder.type(java.lang.String)
HighlightBase$AbstractBuilder.type(HighlighterType)
```

由此确认是高亮路径的二进制依赖不兼容。

## 解决方案

### 本项目的最小修复方案

保留项目已有的手工高亮，删除重复的 Easy-ES 自动高亮。

文件：

```text
networkdisk-business/networkdisk-files/src/main/java/com/disk/files/infrastructure/es/entity/UserFileESEntity.java
```

删除导入：

```java
import org.dromara.easyes.annotation.HighLight;
```

删除注解：

```java
@HighLight(mappingField = "filename")
```

不需要修改 `applyHighlight()` 和 `buildHighlightFilename()`。

这个修复的优点：

- 避开不兼容的 Easy-ES 高亮代码路径；
- 不改变索引结构和 Canal 同步；
- 页面仍能获得高亮结果；
- 不引入新的依赖和版本调整风险。

### 为什么没有直接强制降级 Elasticsearch Java Client

把 `elasticsearch-java` 强制改成 Easy-ES 编译时使用的 7.17.28，会影响 Spring Boot Elasticsearch 自动配置以及项目中其他 ES 调用。当前项目不需要 Easy-ES 原生高亮，因此没有必要为了重复功能扩大依赖改动范围。

只有在未来必须使用 ES 原生高亮时，才统一评估 Easy-ES、Spring Boot、Elasticsearch Server 和 Elasticsearch Java Client 的兼容版本。

## 构建和验证

### 重新构建

```bash
cd /data1/jinbai/NetworkDisk-main

mvn -pl networkdisk-business/networkdisk-files -am \
  clean package -DskipTests
```

### 前台启动

```bash
cd networkdisk-business/networkdisk-files

java -jar target/networkdisk-files-1.0.0-SNAPSHOT.jar \
  --easy-es.enable=true \
  --com.disk.canal.enable=true \
  --com.disk.canal.batch-size=2000
```

### 先做单请求验证

正式压测前先用 JMeter 单线程执行一次。必须同时满足：

```text
鉴权日志包含 pass
没有 ES search failed
没有 WrapperProcessor.initHighlightBuilder
响应包含正常搜索数据
```

如果 ES 没有命中而触发 MySQL 兜底，也不能开始 ES 性能测试。应选择 MySQL 和 ES 中都存在的同一关键词。

### 重新进行可比压测

保持两轮配置完全一致：

```text
线程数：50
Ramp-up：10 秒
持续时间：180 秒
搜索接口、Authorization、请求体、关键词：完全相同
```

清空 Aggregate Report 后重跑，并保存为：

```text
es-canal.csv
```

旧结果应保存为：

```text
es-exception-fallback.csv
```

不要把旧结果标记为 ES 性能结果。

## 如何避免再次发生

1. 升级 Easy-ES、Spring Boot 或 Elasticsearch Java Client 后，必须检查最终依赖树，不能只看直接依赖版本。
2. 对 ES 搜索压测，除了 Error %，必须检查是否出现回退日志。
3. 正式压测前先执行单请求冒烟测试，再放大到并发测试。
4. 不要同时保留框架自动高亮和业务手工高亮。
5. 发生高频异常时立即停止压测，避免每个请求打印完整堆栈拖垮应用和磁盘。

## 快速判断表

| 现象 | 实际含义 | 下一步 |
|---|---|---|
| Error 0%，但有 `ES search failed` | MySQL 兜底成功，不代表 ES 正常 | 查看最底层异常 |
| 堆栈包含 `initHighlightBuilder` | Easy-ES 自动高亮路径失败 | 检查 `@HighLight` 和依赖版本 |
| 删除 `@HighLight` 后仍有高亮 | 项目手工高亮正常工作 | 保持最小修复 |
| ES 结果为空后回退 MySQL | ES 没有命中或数据未同步 | 检查索引数据和关键词 |
| ES 请求正常但 P95 仍高 | 才是有效的性能问题 | 分析查询条件、返回条数和后处理 |
