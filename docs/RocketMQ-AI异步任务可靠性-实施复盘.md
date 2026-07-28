# RocketMQ AI 异步任务可靠性实施复盘

## 本轮实际变更

- 新增独立 `docker-compose.ai-reliability.yml`：RocketMQ NameServer、Broker 与 pgvector；不修改既有 FastDFS 或本地基础设施编排。
- 新增启动、健康检查、停止脚本，均只使用本地开发凭据。
- 新增 `DDL/sql/DDL_ai_reliability.sql`，提供 MySQL Outbox 与 PostgreSQL AI 任务表的建表语句和调度索引。
- 移除 AI 默认配置中的明文模型密钥，并将本地默认 provider 调整为 mock，真实模型仅通过运行时环境变量注入。

## 发现的问题与处理

现有 `DocumentAiInitializer` 在 `afterCommit` 里直接发送消息；Broker 不可用时只记录日志，不能保证事件最终投递。现有 AI 消费者也将索引、摘要、标签串行处理。设计中的 Outbox、任务状态机、独立消费者与 DLQ 管理接口尚未进入本轮代码：它们需要同时改动 files 的事务边界、MySQL Mapper 和 AI 的 PostgreSQL DAO，不能以未验证的骨架替代完整闭环。

## 验证

执行 `docker compose -f docker-compose.ai-reliability.yml config --quiet`，Compose 语法通过。未启动容器，未执行外部模型调用，也未产生性能数据。

补投实现采用 `RETRY_WAIT -> DISPATCHING` 的数据库发送租约，并使用 `FOR UPDATE SKIP LOCKED` 做多实例互斥；成功发送不覆盖状态，消费者可继续抢占。新增的 Mockito 测试覆盖成功发送、发送失败回退和无到期任务不发送。未完成真实 PostgreSQL 并发测试，因此不能将 `SKIP LOCKED` 静态语义等同于运行期验证。

任务表额外保存 `file_id` 与 `filename` 快照；补投查询会恢复这两个字段，避免 INDEX、SUMMARY、TAGS 在重投时因消息缺少文件定位信息而失败。既有 PostgreSQL 数据库需通过迁移补充这两列，不能依赖新卷初始化。

两份 PostgreSQL DDL 都包含 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，用于在已有 pgvector 数据卷上补齐该快照字段。

## 本轮代码实现（未替代独立验收）

- files 在保存 `user_file` 的既有事务内写入 `ai_event_outbox`，不再使用 afterCommit 直发；调度器以状态条件更新抢占记录，投递成功置 `SENT`，失败按限次进入 `RETRY_WAIT` 或 `DEAD`。
- AI 端新增 `ai_document_task` 的 PostgreSQL 幂等创建、带租约的原子抢占、成功/失败状态更新和过期 RUNNING 恢复基础操作。
- DocumentReady 消费仅创建 INDEX、SUMMARY、TAGS 三类任务并逐条投递；任务消费者按 taskKey 抢占后分别调用既有索引、摘要、标签应用服务，单条消息失败不会阻断其它任务消息。
- 已执行 Maven 编译：files 和 ai 模块均 BUILD SUCCESS。未启动 RocketMQ/PostgreSQL，未进行端到端、DLQ 或性能验收。
- 补充了任务消费者 Mockito 测试：验证 MessageBody 解包后的成功路径、claim 未获得执行权时不调用 AI、未知类型永久失败、缺少 taskKey 时在 SQL 前拒绝。指定测试 BUILD SUCCESS；租约恢复仅保留一个定时器实现。
- 本地 RocketMQ 使用宿主机可达的 `brokerIP1=127.0.0.1`。健康脚本将 NameServer 容器内的 `clusterList` 仅作为注册地址检查，并用宿主机 TCP 连接单独验证 Broker；避免把容器内对 `127.0.0.1` 的回连失败误判为宿主机客户端不可达。

## 遗留风险与下一步

- Outbox DDL 需随 MySQL 初始化或迁移流程执行；现有已初始化数据卷不会自动补表。
- pgvector 初始化脚本需合并 AI 任务表 DDL 或单独通过迁移工具执行。
- 下一实施批次应先完成 files 事务内 Outbox 写入和投递器，再完成 AI 原子抢占、三类任务消费者、DLQ/重放接口，并由独立验收 Agent 验收。
