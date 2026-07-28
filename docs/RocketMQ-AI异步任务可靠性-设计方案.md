# RocketMQ AI 异步任务可靠性设计方案

## 1. 文档状态

- 当前阶段：设计待确认
- 已完成范围：现状代码只读审计、目标架构和验收口径设计
- 未完成范围：业务代码、配置、DDL、容器、测试与性能验证均未修改或执行
- 职责约束：本方案设计者不负责最终验收结论；实施与验收由不同职责 Agent 执行

## 2. 业务目标

文件上传成功后，解析、向量索引、摘要和标签属于耗时且允许稍后完成的派生能力，不应阻塞用户上传；同时必须避免消息发送失败后任务永久丢失、消息重复投递后重复调用模型、单项任务失败阻断其他任务，以及服务重启后任务状态不可追踪。

目标链路：

```text
文件元数据提交
  -> 同事务写 DocumentReady Outbox
  -> 后台可靠投递 RocketMQ
  -> AI 服务创建 INDEX / SUMMARY / TAGS 三类任务
  -> 独立消费、幂等执行、失败重试
  -> 超限进入 DEAD 并支持补偿重放
```

## 3. 当前实现与问题

当前 `DocumentAiInitializer` 在文件事务 `afterCommit` 回调中直接发送 `ai-document-warmup` 消息；发送返回 `false` 或抛出异常时只记录日志，因此可能出现“文件已成功、AI 任务永久缺失”。

当前 `AiWarmupStreamConfiguration` 在同一条消息中依次执行索引、摘要和标签：

```text
index -> summary -> tags
```

已经具备的弱幂等基础：

- 索引使用 `forceReindex=false`，已有索引时跳过重建；
- 摘要和标签会复用已落库结果；
- 消费异常向上抛出时可由 Binder/Broker 触发重新投递。

尚缺少：

- 持久化 Outbox 和可靠补投；
- 明确的任务状态、任务唯一键和执行租约；
- INDEX、SUMMARY、TAGS 的失败隔离；
- 可重试异常与永久异常分类；
- 明确配置和管理的重试、死信、补偿重放；
- 任务积压、失败率、处理耗时等可观测指标；
- 能证明上传 P95 和任务最终成功率的自动化测试。

## 4. 边界

### 4.1 本次包含

- 支持 AI 的文件在元数据提交后产生 `DocumentReady` 事件；
- Outbox 可靠投递；
- 三类 AI 任务的状态机、幂等和独立执行；
- 原子抢占、执行租约、宕机恢复；
- 限次退避重试、DEAD 状态、死信记录和重放接口；
- 状态查询、日志、指标与自动化验收。

### 4.2 本次不包含

- 修改 RAG 召回算法、切块算法或模型；
- 引入分布式事务；
- 保证模型服务自身的 SLA；
- 将设计目标直接写成已经完成的简历结果。

## 5. 数据模型

### 5.1 文件服务 Outbox

Outbox 必须与 `user_file` 等文件元数据位于同一数据库，并在同一事务内写入。

```sql
create table ai_event_outbox (
    id bigint primary key,
    event_id varchar(64) not null,
    aggregate_id varchar(128) not null,
    event_type varchar(64) not null,
    topic varchar(128) not null,
    message_tag varchar(64),
    payload text not null,
    status varchar(32) not null,
    retry_count int not null default 0,
    next_retry_time datetime null,
    last_error varchar(1000) null,
    create_time datetime not null,
    sent_time datetime null,
    unique key uk_ai_event_outbox_event_id(event_id),
    key idx_ai_event_outbox_dispatch(status, next_retry_time)
);
```

状态：

- `PENDING`：等待发送；
- `SENDING`：已被某个投递实例抢占；
- `SENT`：Broker 已确认；
- `DEAD`：超过发送重试上限，等待补偿。

消息只携带 `eventId、userId、userFileId、fileVersion、filename` 等定位信息，不携带文件二进制。

### 5.2 AI 任务表

```sql
create table ai_document_task (
    id bigserial primary key,
    task_key varchar(255) not null unique,
    event_id varchar(64) not null,
    user_id bigint not null,
    user_file_id bigint not null,
    file_version varchar(64) not null,
    task_type varchar(32) not null,
    status varchar(32) not null,
    retry_count int not null default 0,
    max_retry_count int not null default 5,
    next_retry_time timestamp null,
    worker_id varchar(128) null,
    lease_expire_time timestamp null,
    last_error_code varchar(64) null,
    last_error_message varchar(1000) null,
    started_at timestamp null,
    finished_at timestamp null,
    gmt_create timestamp not null default current_timestamp,
    gmt_modified timestamp not null default current_timestamp
);
```

`task_key`：

```text
userId:userFileId:fileVersion:taskType
```

`fileVersion` 优先使用内容 MD5/identifier；仅修改文件名不应重复生成向量，内容变化必须产生新版本任务。

任务状态：

- `PENDING`：等待执行；
- `RUNNING`：已被消费者原子抢占；
- `RETRY_WAIT`：临时失败，等待再次投递；
- `SUCCESS`：已经完成；
- `DEAD`：永久错误或超过最大重试次数。

## 6. 可靠投递

文件保存事务中不再注册 `afterCommit` 直接发送，而是同时写 Outbox：

```text
begin
  保存 file/user_file 元数据
  插入 ai_event_outbox(PENDING)
commit
```

后台投递器分页抢占到期的 `PENDING` 记录并发送 RocketMQ；发送成功标记 `SENT`，失败累计次数并设置 `next_retry_time`。

多文件服务实例使用数据库行锁或带状态条件的原子更新防止重复抢占。若“Broker 已收到、SENT 尚未落库”时进程崩溃，Outbox 会再次投递，因此消费者幂等是必需条件。

## 7. 任务拆分与消费

`DocumentReady` 消费者只负责以 UPSERT 方式创建三条任务：

```text
INDEX
SUMMARY
TAGS
```

随后分别发送任务消息，使用独立 Tag/Binding 和消费组，以便分别设置并发与重试。INDEX 完成前，SUMMARY/TAGS 可选择：

- 第一阶段：摘要和标签读取原文，三类任务相互独立；
- 后续优化：若摘要和标签依赖索引文本，则检查 INDEX 状态，未完成时进入 `RETRY_WAIT`，而不是执行失败。

## 8. 幂等与原子抢占

重复消息到达时先按 `task_key` 查询：

- `SUCCESS`：直接确认，不重复调用模型；
- `RUNNING` 且租约未过期：不重复执行；
- `PENDING/RETRY_WAIT`：尝试原子抢占；
- `RUNNING` 但租约已过期：允许恢复抢占；
- `DEAD`：除非人工重放，否则不执行。

原子抢占使用带前置状态的单条 `UPDATE`，只有影响行数为 1 的消费者获得执行权。消费者执行期间定期续租，或者将租约设为大于正常 P99 耗时；定时恢复器把租约过期的 `RUNNING` 任务转为 `RETRY_WAIT`。

业务结果仍使用 UPSERT/唯一约束，形成“任务幂等 + 结果幂等”两道防线。

## 9. 重试、死信与补偿

异常分类：

- 可重试：模型超时、限流、临时 5xx、PostgreSQL/FastDFS 短暂不可用；
- 永久失败：文件不存在、格式不支持、内容为空、消息字段非法、权限关系不存在；
- 未知异常：先按可重试处理，达到上限后转 DEAD。

建议退避：1、5、15、30、60 分钟，最大 5 次；永久失败直接进入 `DEAD`，避免毒消息反复占用消费者。

Broker 重试超过上限后进入 DLQ；DLQ 消费者不直接无限重投，而是：

1. 按 `task_key` 更新任务为 `DEAD`；
2. 保留脱敏错误码、错误摘要和原消息标识；
3. 触发告警；
4. 通过管理接口或定时补偿重置为 `PENDING` 并重新投递。

管理能力：

```text
GET  /api/v1/ai/tasks?status=DEAD
POST /api/v1/ai/tasks/{id}/retry
POST /api/v1/ai/tasks/{id}/cancel
```

重放沿用原 `task_key`，不得创建重复业务结果。

## 10. 可观测性

日志统一携带：

```text
eventId、taskKey、taskType、userFileId、retryCount、workerId、duration、result
```

指标至少包括：

- Outbox 待发送数、发送失败数、最老消息等待时间；
- 各任务 PENDING/RUNNING/RETRY_WAIT/DEAD 数；
- INDEX/SUMMARY/TAGS 成功率、重试率、P50/P95/P99；
- DLQ 新增数和重放成功数；
- 从文件元数据提交到三类任务全部完成的端到端耗时。

日志和指标不得记录原文、真实密钥或完整模型响应。

## 11. 实施阶段

### 阶段 A：可靠事件

- 新增 Outbox DDL、实体、Mapper、服务和投递器；
- 文件保存事务改为写 Outbox；
- 保留旧直发能力作为可配置回滚开关，默认仅启用一条路径，避免双发。

### 阶段 B：任务状态与拆分

- 新增 `ai_document_task`；
- 新增任务类型、状态机、唯一键和原子抢占；
- 把单消费者串行调用拆为三个任务处理器；
- 复用现有索引、摘要、标签应用服务。

### 阶段 C：重试、DLQ、补偿

- 配置独立消费组及限次重试；
- 新增 DLQ 处理、租约恢复和管理重放；
- 增加错误分类和可观测指标。

### 阶段 D：自动化验收与性能测试

- 正常上传、重复消息、并发消费、部分失败、服务宕机、Broker 不可用、DLQ 重放；
- 对同步处理基线与异步事件方案采用相同文件集、并发数和机器环境测量。

## 12. 验收标准

功能与一致性：

- 文件事务回滚时不存在可投递 Outbox；
- Broker 不可用时文件上传仍完成，Outbox 保留并在恢复后成功补投；
- 同一事件重复投递至少 10 次，每种任务只有一个最终有效结果；
- SUMMARY 失败不阻断 INDEX 和 TAGS；
- 消费者在 RUNNING 中宕机，租约到期后任务可恢复；
- 可重试错误按策略重试，永久错误不进行无效重试；
- 超限任务进入 DEAD/DLQ，修复后可重放成功；
- 多实例下同一任务同一时刻只有一个执行者。

性能口径：

- “上传 P95 35 秒降至 500 毫秒”必须先定义测量边界。若 35 秒包含文件二进制上传，则 500 毫秒不合理；应测量“文件数据已传完后，元数据提交接口的服务端处理耗时”；
- 至少预热后执行 3 轮，每轮不少于 100 次，报告样本量、机器、文件大小、并发数、P50/P95/P99 和失败率；
- “任务成功率”定义为在观察窗口内最终进入 SUCCESS 的任务数 / 有效任务总数，重试后成功计入成功；
- 80% 只能作为最低演示门槛，工程验收目标建议最终成功率不低于 99%，DLQ 中每条任务均可追踪。

在独立验收完成前，简历中只能写设计目标，不得写“P95 已降至 500 毫秒”或“成功率达到 80%”。

## 13. 风险与回滚

- 新旧发送路径并存可能造成双发：使用互斥配置开关并依赖消费幂等；
- 任务拆分增加 Topic/Binding 和运维复杂度：先使用单 Topic 多 Tag，稳定后再按容量拆 Topic；
- Outbox 积压占用数据库：建立索引、限制批次并定期归档 SENT 数据；
- 执行时间超过租约可能重复处理：续租并保留结果唯一约束；
- 错误分类不完整可能导致无效重试：未知异常限次后 DEAD，不无限循环；
- 回滚时关闭 Outbox 投递器和新任务消费者，恢复旧直发开关；新表保留以便审计，不做破坏性删除。
