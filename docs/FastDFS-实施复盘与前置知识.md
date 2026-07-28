# FastDFS 实施复盘与前置知识

## 实施记录（2026-07-26）

### 已完成的改动

- 新增 `docker-compose.fastdfs.yml`：本地 `1 Tracker + 2 个同 group1 Storage`；三个节点各自使用命名数据卷。Storage-1 提供唯一的本地 HTTP 验证入口，Storage-2 仅作为组内副本节点。
- 新增 `fastdfs` Profile：默认仍选 `local` 引擎；只有 `com.disk.file.storage.engine.type=fdfs` 时才实例化 FastDFS 引擎，OSS 也必须显式选择才会参与装配，避免多个存储实现混用。
- 改造分片链路：分片先存入本地临时目录，合并时按顺序流式写入临时完整文件、校验 MD5，再以完整流上传 FastDFS。FastDFS 只保存最终二进制，保留了现有断点续传协议。
- 为合并操作增加以 `userId + identifier` 为键的分布式锁，并使用已完成路径缓存处理重复合并；远端文件删除时同步清理该缓存。
- 仅在 `file` 元数据和 `user_file` 用户目录关系均成功后清理临时分片；若元数据或用户目录写入失败，保留分片用于重试，并尝试删除已经写入 FastDFS 的孤儿文件。

### 已知兜底边界

1. FastDFS 上传成功但补偿删除也失败时，仍可能留下远端孤儿文件；当前保留了失败边界，后续应补充定时孤儿扫描/补偿任务，不能宣称完全无垃圾文件。
2. Storage-1 是当前唯一 HTTP 入口；Storage-1 故障不会损失 Storage-2 副本，但预览/下载入口会暂时不可用。这不是 HTTP 高可用，需单独增加反向代理或负载均衡后才能改善。
3. 远端文件上传与数据库事务无法形成真正的分布式事务；本实现采用“先远端写入、后写元数据、失败补偿、保留分片重试”的 Saga 式补偿边界。

### 本轮验证结果

- `docker compose -f docker-compose.fastdfs.yml config`：通过，仅验证编排语法，未拉取镜像或启动容器。
- `git diff --check`：通过。
- Maven 离线构建未能进入编译：本机 Maven 缓存缺少 Spring Boot 父 POM；错误是依赖不可解析，不是已确认的 Java 编译错误。待网络/依赖可用时执行模块构建。
- 双 Storage 副本、节点故障、重复合并、DB 写失败等端到端场景**尚未执行**；必须由独立验收方案执行并留存证据后，才可标记通过。

### 独立验收回传（基础设施层）

独立验收已实际完成基础设施层验证：Tracker 中 `group1` 的两个 Storage 均为 `ACTIVE`；无敏感测试文件在两节点的 SHA-256 与源文件一致；Storage-2 停止后 Storage-1 的 HTTP 下载仍可用，恢复后两个节点重新变为 `ACTIVE`。结论仅为“基础设施层有条件通过”。

验收同时发现并记录：HTTP 入口只部署在 Storage-1，不具备入口故障自动切换；文件服务尚未启动，鉴权、分片、元数据一致性和补偿等应用端用例仍未执行。详情与证据见《FastDFS-独立验收测试方案》。

## 第二轮实施：多实例访问与失败清理

### 文件服务双实例

- 新增 `files-instance-a` 与 `files-instance-b` Profile，分别固定 HTTP `8082/8083`、Nacos `instance-id` 和 Dubbo QoS `22222/22223`，避免同机双实例端口冲突。
- 新增 `scripts/start-files-dual-instance.ps1`，以已有可执行 jar 为前置条件，一次拉起两份文件服务；可选 `local` 或 `fastdfs` 存储 Profile。
- 服务发现边界：网关走 `lb://networkdisk-files` 才能基于 Nacos 负载均衡；Vite 直连某个后端端口不是负载均衡，开发调试时不能据此宣称多实例生效。
- **实际验证（2026-07-26）：** 两个 JVM 分别监听 HTTP `8082/8083` 与 Dubbo `20882/20883`；Nacos `DEFAULT_GROUP` 下查询到两个健康的 `networkdisk-files` 实例，元数据分别为 `local-instance=a/b`。Spring Cloud 的 HTTP 服务使用 `DEFAULT_GROUP`，不要误用 Dubbo 的 `networkdisk-dev` 组去查询该服务。

### 双 Storage 访问层

- 将宿主机 `8888` 从 Storage-1 移交给独立 `fastdfs-access-nginx`；两个 Storage 的 FastDFS Nginx 均只留在 Docker 内网。
- 代理 upstream 使用两个 Storage，针对连接错误、超时和常见 5xx 执行被动失败切换。实施侧已验证 Storage-1 停止后，同一文件仍可经 `8888` 由 Storage-2 返回且校验一致；正式验收结论以独立验收文档为准。
- 该代理本身仍是单点，且为被动探测：第一个遇到失效节点的请求可能等待连接超时。要形成访问层高可用，仍需在后续加双代理/负载均衡器。

### 失败补偿与过期清理

- 完整文件先写 FastDFS、再写 `file` 元数据、最后创建 `user_file` 关系；中途失败时保留分片供续传/重试，并尝试删除已写入的远端文件。
- 新增分布式定时清理任务：每 10 分钟扫描过期分片，多实例通过分布式锁选出单一执行者；只有物理临时文件删除成功才物理删除分片记录，失败保留记录供下一轮重试。
- 远端补偿删除也失败时仍可能残留孤儿文件；这需要后续独立的孤儿扫描任务，当前没有虚构为已解决。

### 问题闭环：FastDFS Profile 启动时缺少 LocalStorageEngineConfig

- **现象：** 以 `fastdfs` Profile 启动双文件服务实例时，Spring 报 `FolderAndChunksFolderInitializer` 无法注入 `LocalStorageEngineConfig`，Tomcat 初始化后回滚，端口与 Nacos 实例均不会就绪。
- **根因：** 存储引擎选择改造时，误将 `LocalStorageEngineConfig` 和 `LocalStorageEngine` 一起加上 `type=local` 条件。前者只是配置载体，仍被通用目录初始化器依赖；后者才是需要按 Profile 排他的存储实现。
- **修复：** 移除配置类的条件注解，使其始终绑定本地路径配置；保留 `LocalStorageEngine` 的条件装配，因此 `fastdfs` Profile 下不会回退为 Local 存储引擎。
- **验证：** 需重新构建 jar、重启 A/B 实例后确认 `8082/8083` 监听且 Nacos 出现两个实例；旧失败进程不可作为验证结果。

> 本文在实施前记录基线与必要知识；实施开始后，按“问题闭环模板”追加真实现象、改动和验证结果，不以设计假设替代实际结果。

## 1. 必要前置知识

### 1.1 Tracker、Storage 与 Group

- **Tracker** 不保存业务文件，负责记录 Storage 状态并在上传、下载时返回可用节点。
- **Storage** 保存实际文件；文件副本由同一个 Group 内的多个 Storage 同步。
- **Group** 是副本与容量管理的基本单位。两个 Storage 只有加入同一个 Group，才能验证组内复制。

### 1.2 FastDFS 逻辑路径

上传后会得到类似 `group1/M00/00/00/xxx` 的逻辑路径：

- `group1`：存储组。
- `M00/...`：Storage 生成的相对文件路径。
- 文件服务应保存逻辑路径，而不是把某一台 Storage 的 IP 写进数据库。
- 对外访问 URL 由 Nginx 地址与逻辑路径组合，例如 `http://<host>:8888/group1/M00/...`。

### 1.3 为什么文件二进制不放 MySQL

MySQL 适合保存用户、目录、文件名、大小、哈希、分享状态等结构化元数据；大文件二进制应进入文件存储系统。这样可以避免大对象读写挤占业务数据库连接和备份资源，同时更便于横向扩展文件容量。

### 1.4 网盘中的三层数据模型

| 层次 | 示例 | 责任 |
|---|---|---|
| 物理文件 | FastDFS 的 `group + path` | 保存一次二进制内容。 |
| 文件元数据 | 文件大小、后缀、MD5、物理路径 | 描述一个可复用文件。 |
| 用户文件关系 | 用户、父目录、文件名、删除状态 | 决定谁能在自己的目录中看到和操作文件。 |

## 2. 实施前基线记录

| 项目 | 当前状态 | 结论 |
|---|---|---|
| FastDFS 客户端代码 | 已存在 `FastDFSStorageEngine` 及配置类。 | 可复用，不需要从零编写 SDK 封装。 |
| FastDFS 服务端 | 本地尚未部署 Tracker、Storage、Nginx。 | 当前不能宣称已运行 FastDFS 分布式存储。 |
| 默认存储 | 本地服务默认使用 `LocalStorageEngine`。 | 实施时必须显式切换，避免“配置了 FastDFS 但实际仍写本地磁盘”。 |
| 外部存储配置 | 仓库存在外部地址示例。 | 本地 FastDFS Profile 必须与外部资源彻底隔离。 |
| 当前电脑资源 | 16 GB 内存。 | FastDFS 实施期间不同时启动 ES、AI、RocketMQ 等重组件。 |

## 3. 实施过程记录模板

每一个问题按以下格式追加，不删除原始现象。

### 问题编号：FDFS-XX

- **阶段：** 基础设施 / 文件服务接入 / 副本验证 / 故障验证。
- **现象：**
- **影响：**
- **排查证据：** 日志、容器状态、命令输出或文件校验值。
- **根因：**
- **方案候选：**
  1. 
  2. 
- **最终方案与原因：**
- **实际修改：** 文件路径、配置项、命令。
- **验证：** 成功标准与实际结果。
- **复用经验：**

## 4. 预期高频问题与排查思路

| 问题 | 常见原因 | 排查与处理方向 |
|---|---|---|
| Tracker 找不到 Storage | Docker 网络、Tracker 地址或组名配置错误。 | 检查容器网络、Tracker 状态与 Storage 注册日志。 |
| 文件上传成功但 URL 404 | Nginx 未正确映射 FastDFS 存储目录或 URL 拼接错误。 | 对比逻辑路径、Nginx location 和 Storage 实际目录。 |
| 两节点没有副本 | 两个 Storage 不在同一 Group、复制尚未完成或网络不通。 | 检查组名、节点状态、等待复制后计算文件校验值。 |
| 文件服务仍写本地 | Local Storage Bean 仍被优先注入。 | 检查 Profile、生效配置和 Spring Bean 装配条件。 |
| 删除后其他用户无法下载 | 物理文件删除没有引用计数或有效关联检查。 | 先删除用户关系，确认无有效引用后再异步删除物理文件。 |

## 5. 验证证据清单

- [ ] Docker 容器状态：Tracker、Storage-1、Storage-2、Nginx 全部在线。
- [ ] Tracker 查询结果：两个 Storage 都属于 `group1`。
- [ ] 上传接口返回的逻辑路径和 HTTP URL。
- [ ] Storage-1 与 Storage-2 内相同逻辑路径的文件存在证明。
- [ ] 两副本文件的大小或 SHA-256 校验一致。
- [ ] 暂停单个 Storage 后的访问或恢复验证结果。
- [ ] 文件服务 Profile、生效 Bean 和日志记录。

## 6. 面试表达要点

> FastDFS 解决的是文件二进制存储与扩容问题，MySQL 仍保存文件元数据和用户目录关系。上传后服务端保存 `group + path` 逻辑路径，而不是固定某台机器地址；同组双 Storage 用于验证副本同步。业务删除先处理用户文件关系，物理文件删除必须确认不存在有效引用，避免误删去重文件。
