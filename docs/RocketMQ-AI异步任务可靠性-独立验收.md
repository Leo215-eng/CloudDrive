# RocketMQ—AI 异步任务可靠性独立验收

## 1. 验收身份、范围与结论

本文件由独立验收方编写。验收方未参与本轮设计或实现，只依据当前工作区代码、配置、DDL、Docker 容器状态、构建和测试结果作出判断；未修改任何业务实现、DDL 或编排。

验收范围为“文件保存后产生 AI 派生任务”的可靠投递、任务幂等、任务隔离、租约恢复、重试/死信、基础设施可运行性和性能表述边界。文件服务/AI 服务未成功完成端到端启动，故不得把静态审计或单元测试等同于完整业务验收。

**总体结论：不通过。**

原因不是编译失败：模块编译、定向消费者单测和 RocketMQ/pgvector 基础设施检查均通过。阻断项在于 Outbox 事务边界不满足设计目标、MySQL Outbox DDL 没有可执行的实际初始化路径、应用连接配置与已启动基础设施不一致，以及租约/重试/死信关键可靠性语义尚未闭环。

## 2. 已执行的独立检查与证据

| 项目 | 实际检查 | 结果 | 判定 |
|---|---|---|---|
| 文件模块编译 | Maven 以 files 模块及依赖执行 `compile -DskipTests` | BUILD SUCCESS | 通过 |
| AI 模块编译与定向测试 | Maven 执行 AI 模块及依赖的 `AiTaskStreamConfigurationTest` | BUILD SUCCESS；4 tests、0 failure、0 error | 通过（覆盖有限） |
| Compose 语法 | `docker compose ... config --quiet` | 退出成功 | 通过 |
| PowerShell 脚本语法 | 对启动、停止、健康检查脚本使用 PowerShell Parser | 三个脚本均为 0 parse errors | 通过 |
| RocketMQ 与 pgvector | 实际启动隔离 Compose，执行项目健康检查脚本 | NameServer healthy、Broker 运行且集群列表可查询；pgvector healthy、数据库就绪 | 通过（仅基础设施） |
| PostgreSQL DDL | 在实际 pgvector 数据库查询表 | `ai_document_task` 与既有 AI 结果表存在 | 通过（仅新建卷初始化） |
| MySQL Outbox DDL | 尝试启动既有 MySQL 容器并查询表 | 容器依赖的 Docker 网络不存在，无法启动；无法实测表 | 未通过/运行阻断 |
| 代码差异与格式 | 完整阅读已跟踪和未跟踪改动，执行 `git diff --check` | 未发现 diff whitespace error | 通过 |

构建过程中 Maven 提示既有 Gateway 依赖重复声明、资源插件参数等警告；本次不作为本功能阻断项，但应由项目维护者另行治理。

## 3. 可靠性设计逐项审计

| 目标 | 独立证据 | 结论 |
|---|---|---|
| 统一消息包装 | `StreamProducer` 会把业务 JSON 包装为 `MessageBody(identifier, body)`；AI 两类消费者均按该结构解包。定向测试覆盖了包装 INDEX 消息成功路径。 | 部分通过 |
| Outbox 入库替代 afterCommit 直发 | `DocumentAiInitializer` 不再直接调用发送器，而调用 `AiEventOutboxService.append`；调度器存在 `PENDING/RETRY_WAIT -> SENDING -> SENT/DEAD` 代码路径。 | 部分通过，见事务阻断项 |
| SENDING 租约与补投 | 投递器将 `SENDING` 的到期记录改回 `RETRY_WAIT`，且用状态条件更新抢占。 | 部分通过；无 owner/fencing，见风险 R-3 |
| 任务 taskKey 幂等 | AI 端表有 `task_key unique`，`createIfAbsent` 使用 PostgreSQL `on conflict do nothing`。 | 静态通过；未做数据库并发验证 |
| 原子 claim | `claim` 是单条带状态谓词的 `UPDATE`，返回影响行数；定向测试覆盖 claim 未获得时不调用 AI。 | 部分通过；租约完成写入缺少 fencing |
| INDEX/SUMMARY/TAGS 隔离 | DocumentReady 消费循环创建并投递三条任务；任务消费按类型分别调用对应应用服务。 | 部分通过；未验证 Broker 级独立重试/积压隔离 |
| 租约恢复 | `@Scheduled` 恢复过期 RUNNING 为 RETRY_WAIT，AI 启动类启用了 scheduling。 | 静态部分通过；无数据库/宕机恢复测试 |
| 重试、DEAD、DLQ、重放 | 任务和 Outbox 都有 retry/DEAD 字段；任务 consumer 配置最大尝试次数。 | 不通过：无 DLQ 消费、无重放接口、无端到端重试证据 |

## 4. 阻断项与高风险

### R-1：Outbox 与用户文件保存并非所有路径同一事务（阻断）

`UserFileServiceImpl.secUpload()` 没有事务注解，却在 `saveUserFile()` 后调用 Outbox append。该路径中 user_file 与 ai_event_outbox 分别自动提交，不能满足“同事务写入、任一失败同时回滚”的设计要求。普通上传和分片合并的外层方法有事务注解，但秒传仍构成真实网盘业务缺口。

影响：秒传场景可能出现文件可见而无 Outbox，或 Outbox 已创建但后续业务异常时没有一致性保证。

### R-2：MySQL Outbox DDL 没有实际初始化/迁移链路（阻断）

`DDL_ai_reliability.sql` 把 MySQL Outbox 和 PostgreSQL 任务表放在同一文件，不能直接作为单一数据库初始化脚本执行；本地 MySQL Compose 只挂载既有主 DDL，不挂载 Outbox DDL。即使新增挂载，MySQL 初始化脚本也只在新数据卷创建时自动执行，已有卷不会补表。

本次尝试启动当前 MySQL 容器时发现其原 Docker 网络已不存在，因此无法查询表存在性。这是本机运行阻断，且不能替代明确的迁移方案。未确认 `ai_event_outbox` 表存在前，文件服务调用 Outbox 会在运行期失败。

### R-3：任务租约没有 worker fencing/续租，可能发生陈旧 worker 覆盖（高风险）

`claim(taskKey, worker)` 会写入 worker_id 和 5 分钟租约，但 `success()` 与 `failure()` 只按 task_key 更新，不校验 worker_id、RUNNING 状态或租约版本。场景为：Worker A 执行超过租约，Worker B 抢占；A 随后完成并把 B 正在处理的任务改成 SUCCESS，造成状态与真实结果错配。代码也没有执行中的续租。

这不满足“同一时刻只有一个有效执行者”的可靠性语义，需在状态更新中引入 claim token/worker 条件并补并发与超时回归测试。

### R-4：重试延迟、DLQ 与补偿重放未闭环（高风险）

任务失败后写入 `RETRY_WAIT` 和 next_retry_time，但 `claim()` 没有检查 next_retry_time；Broker 的即时重投可立刻再次 claim，数据库退避时间不生效。没有发现针对 RETRY_WAIT 的定时再投递器。不存在 DLQ 消费器、DEAD 查询/重放接口或补偿审计；非法消息在 taskKey 创建前抛出，也没有持久化为 DEAD 记录。

因此只能称为“存在有限次数状态字段和异常抛出”，不能称为“完成重试、死信和重放”。

### R-5：eventId 与 Outbox event_id 未贯通（高风险）

Outbox 自己生成 event_id，但 payload 里的 `AiDocumentWarmupMessage` 未携带该值；`StreamProducer` 每次发送又生成新的 MessageBody.identifier。AI 端保存的是后一个随机 identifier，而不是 Outbox event_id。

影响：无法从 AI 任务可靠追溯到具体 Outbox 记录，重复投递/补投的事件关联审计不成立。

### R-6：任务版本没有使用内容版本（高风险）

AI 端 version 使用加密后的 userFileId（或 FileId），而非设计要求的文件 MD5/identifier 等内容版本。文件内容替换而 user_file 关系保持时，taskKey 不会变化；反之不同用户文件关系可能生成冗余任务。不能证明“内容变化必重新处理、仅改名不重复处理”。

### R-7：应用连接配置与当前基础设施端口不一致（运行阻断）

隔离 Compose 为避免与现有本机服务冲突，使用了非默认宿主端口；而 AI 的 pgvector JDBC 默认仍指向另一默认端口，公共 RocketMQ binder 也仍使用另一默认 NameServer 地址。未发现相应环境变量覆盖或运行 profile 说明。基础设施健康不代表 AI/files 应用可连接。

## 5. 测试覆盖评价

已通过的 4 个 Mockito 测试仅证明：包装消息的 INDEX 成功分支、claim 失败不调用 AI、未知类型走永久失败调用、缺 taskKey 在 SQL 前抛错。它们不连接 PostgreSQL、MySQL、RocketMQ 或文件服务事务。

以下验收均未执行，且在 R-1/R-2/R-7 修复前不应开始标记通过：

- 文件事务回滚时 Outbox 不存在；Broker 不可用时文件成功且 Outbox 之后补发。
- 重复 DocumentReady/任务消息至少 10 次时三类任务只产生一个有效最终结果。
- 多消费实例并发 claim、租约过期/worker 宕机、陈旧 worker 完成写入。
- INDEX 失败时 SUMMARY/TAGS 的独立完成；重试退避、DLQ、DEAD 重放。
- Outbox SENDING 租约恢复和发送“Broker 已收、SENT 未落库”后的重复投递。
- 文件上传到三类任务最终状态的端到端可观测性。

## 6. 性能口径审计

**不接受“35 秒降至 500 毫秒”或类似结论。** 变更前 `DocumentAiInitializer` 已在事务 afterCommit 中向 MQ 发送预热消息，AI 的索引、摘要、标签本来就在 AI 消费端执行，不在上传请求中同步执行。因此本次改造不能以“同步 AI 处理被异步化”为由宣称上传耗时从数十秒降至亚秒。

本轮没有上传 P50/P95/P99、文件大小、并发、样本数、机器配置、失败率或任务最终成功率数据。可以讨论的潜在收益仅限“Outbox 降低事务提交成功后消息丢失风险”，不能量化为延迟或吞吐提升。

## 7. 可写简历/项目说明的边界

在当前状态下可写（须明确为实现中的能力，不写结果指标）：

> 为 AI 文档处理补充 Outbox、任务唯一键和任务状态的代码基础，并完成文件/AI 模块编译、消费者单元测试及 RocketMQ、pgvector 本地基础设施健康检查。

当前不得写：

- “实现可靠 Outbox、最终一致性、DLQ 重放、生产级任务调度”；
- “三类 AI 任务已完全隔离、支持宕机恢复”；
- 任意上传 P95、任务成功率、吞吐提升或“35 秒到 500 毫秒”等量化结果；
- “已完成 AI 异步任务可靠性验收”。

## 8. 重新验收的前置条件

1. 为 MySQL Outbox 与 PostgreSQL 任务表提供独立、可重复执行的迁移，并在已有数据卷上实际验证。
2. 使 files 的所有会创建用户文件的路径均与 Outbox 使用同一 Spring 事务；增加事务回滚测试。
3. 以 claim token/worker 条件保护 success/failure，增加续租或明确的 P99 租约策略；补并发与宕机测试。
4. 实现/配置 RETRY_WAIT 重新投递、DLQ 消费、DEAD 查询与人工重放，并验证退避不被 Broker 即时重投绕过。
5. 把 Outbox event_id 贯穿到消息与 AI task；以真实内容版本构建 taskKey。
6. 提供可启动的本地 profile，使 files、AI 分别连接隔离的 MySQL、RocketMQ 和 pgvector，并执行全链路验收。
7. 在相同输入、并发与机器条件下建立测量基线后，才可讨论性能指标。

## 9. 2026-07-28 修复复核：秒传事务与 next_retry_time

### 复核范围

实施方提交了两项增量修改；本次独立复核只阅读了最新代码差异并重新执行必要构建/定向测试，未修改任何业务实现。

| 修复项 | 最新代码证据 | 复核结果 |
|---|---|---|
| 秒传事务边界 | `UserFileServiceImpl.secUpload()` 已增加 `@Transactional(rollbackFor = Exception.class)`；该 public 方法经 Spring 代理调用时，`saveUserFile()` 与其后的 Outbox append 进入同一事务 | 静态通过；仍缺少“Outbox insert 失败/外层回滚后两表均无记录”的集成测试 |
| RETRY_WAIT 时间门槛 | `AiDocumentTaskRepository.claim()` 已将 PENDING/RETRY_WAIT 的抢占条件限制为 `next_retry_time is null or <= now()` | 静态通过；仍缺少 PostgreSQL 时间边界测试 |
| 回归构建 | 重新执行 files 模块编译，以及 AI 模块定向测试 | 两次 Maven 构建均 BUILD SUCCESS；`AiTaskStreamConfigurationTest` 4/4 通过 | 通过 |

### 对总体结论的影响

R-1 从“明确违反事务边界”降为“**静态修复、待集成验证**”，不再单独作为当前阻断理由。R-4 的“Broker 立即重投绕过数据库退避”也被时间谓词部分修复。

但 R-4 仍未闭环：当 Broker 在 next_retry_time 到期前再次投递时，consumer 的 claim 返回 false 并正常确认消息；当前未发现负责扫描到期 `RETRY_WAIT` 并重新投递任务消息的调度器。因此任务可能从一次短暂失败变为“留在 RETRY_WAIT、没有后续消息唤醒”，并不构成可用的延迟重试机制。

同时，R-2、R-3、R-5、R-6、R-7 及缺少 DLQ/重放/端到端验收的事实均未变化。因此**总体结论仍为不通过**，性能口径结论仍保持：旧链路本已 afterCommit 发 MQ，不存在可接受的“35 秒同步基线”。

## 10. 2026-07-28 子能力验收：到期 RETRY_WAIT 自动重新投递

### 验收范围

本节只评估“已到期 RETRY_WAIT 任务是否能被多实例安全抢占并重新发送”的代码路径。未改动业务实现。重新执行 AI 定向测试：`AiTaskStreamConfigurationTest` 4 个、`AiRetryTaskDispatcherTest` 3 个，共 **7/7 通过**。

### 静态实现复核

| 关注点 | 代码证据 | 静态判断 |
|---|---|---|
| 到期筛选和多实例去重 | `acquireDueRetries` 使用 PostgreSQL 单条 CTE：`FOR UPDATE SKIP LOCKED` 后将记录更新为 `DISPATCHING` 并 `RETURNING` | 设计方向正确；单 SQL 语句具备行级互斥基础 |
| send=false/发送异常回退 | 调度器对 `send` 返回 false 或异常调用 `releaseDispatch`，回到 RETRY_WAIT 并推迟下次时间 | 单元测试覆盖，静态通过 |
| send 成功与 consumer 竞态 | 成功发送后保留 DISPATCHING；consumer 的 claim 允许 DISPATCHING 进入 RUNNING；发送后未被消费时，DISPATCHING 租约到期由恢复器回到 RETRY_WAIT | 状态流转可解释，但未做真实 Broker/PG 并发演练 |
| DISPATCHING 租约恢复 | `recoverExpired` 包含 RUNNING 和 DISPATCHING，恢复为 RETRY_WAIT | 静态通过，未做时间推进测试 |
| fileId/filename 完整性 | Warmup 创建任务与重试查询/重发均携带 fileId、filename、fileVersion、taskType | 代码层已补全 |

### 子能力阻断：DDL 与 Repository SQL 不一致

**子能力结论：不通过。** `AiDocumentTaskRepository.acquireDueRetries()` 会读取 `ai_document_task.file_id` 和 `filename`；但当前 pgvector 初始化 DDL 中的 `ai_document_task` 定义没有这两列。另一个可靠性 DDL 虽定义了这两列，却不是 pgvector Compose 挂载的初始化文件，且没有迁移机制把已有表补齐。

此外，当前 pgvector 初始化 DDL 的 `ai_document_index` 定义重复声明了 `filename` 列；新 PostgreSQL 数据卷执行该脚本时会因重复列定义失败，后续 task 表初始化也无法依赖该脚本完成。这意味着在真实 PostgreSQL 中，重试调度器会在查询/返回字段时失败，或新环境根本没有可用任务表。

本次尝试对先前 pgvector 容器进行只读列查询时，该容器已不存在，故没有把旧环境状态当作本轮证据；结论依据当前两份 DDL 的静态差异，且该差异足以构成运行阻断。

### 尚未覆盖的真实 PostgreSQL 集成测试

以下全部未通过/未执行，DDL 修复并通过迁移后必须补测：

1. 两个 dispatcher 实例同时调度同一批到期任务，确保每个 taskKey 仅发送一次。
2. Broker 返回 false、抛异常、发送成功但 consumer 未收到三种场景的状态、租约和再次投递。
3. DISPATCHING 租约过期后恢复、再次抢占和消息内容（fileId、filename、版本、类型）完整性。
4. consumer 在 DISPATCHING 与 RUNNING 切换中的并发时序，以及此前 R-3 的陈旧 worker fencing 问题。

因此，尽管 7 个 Mockito 单测通过，不能将本子能力描述为“已实现可靠延迟重试”或作为总体结论转正的依据。

## 11. 2026-07-28 DDL 修复复核：RETRY_WAIT 子能力更新结论

实施方已修正主 pgvector DDL 的重复字段，并使两份任务 DDL 均包含 file_id、filename 及针对旧表的 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 兼容语句。本节为对第 10 节 DDL 阻断结论的最新复核。

### 独立干净 PostgreSQL 验证

验收方使用一个不挂载项目数据卷、自动删除的临时 pgvector 容器进行验证：

1. 以最新 `DDL_network_disk_ai_pgvector.sql` 初始化空数据库，完整脚本执行成功；ai_document_task 包含 Repository 重试查询所需的 file_id、filename 和其它状态列。
2. 在该空库直接执行 acquireDueRetries 使用的 `WITH ... FOR UPDATE SKIP LOCKED ... UPDATE ... RETURNING` SQL，语法和字段引用均成功，空表返回 0 行。
3. 删除任务表后手工创建“旧任务表”字段集合，再执行两条 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`；file_id、filename 均成功补齐。

这证明此前“新环境 DDL 不能初始化、重试 SQL 引用缺列”的**DDL 阻断已解除**。

### 最新子能力结论

**到期 RETRY_WAIT 自动重新投递：有条件通过。**

通过依据：

- 7 个定向单测全部通过；
- 多实例抢占 SQL、DISPATCHING 短租约、send=false 回退、消息字段读取与旧表列迁移均有代码或干净 PostgreSQL 实证；
- Repository 的重试 SQL 已可在干净 PostgreSQL 中实际执行。

“有条件”原因及未覆盖项：

1. 未在真实 PostgreSQL 中启动两个 dispatcher 实例，验证同一到期任务只被一个实例返回并发送。
2. 未联动实际 RocketMQ 验证 send 成功但 consumer 未收到时，DISPATCHING 租约到期后恢复并再次投递。
3. 未验证 send=false/异常在真实数据库中的 releaseDispatch 状态、等待时间和后续成功投递。
4. 未覆盖 consumer 从 DISPATCHING 抢占为 RUNNING 的真实并发时序，且总体验收中的 worker fencing 风险仍未解除。

因此可在实施记录中写“已补充到期任务调度与 PostgreSQL DDL 兼容验证”，但不可写“延迟重试端到端可靠性已验收完成”，更不能改变本文件第 1 节的**总体不通过**结论。

### 现有卷执行记录的证据边界

实施方另提供了现有 pgvector 卷的执行记录：以 ON_ERROR_STOP 执行完整 DDL 成功；在事务中插入到期 RETRY_WAIT 后，执行与 Repository 一致的 CTE + FOR UPDATE SKIP LOCKED 返回了 task_key、file_id、filename 并将状态更新为 DISPATCHING，随后回滚。该记录与本验收方的干净库 SQL 验证一致，支持“迁移 SQL 可在已有卷运行”的判断。

但该操作并非由验收方亲自执行，故本文件不把它单独标记为独立端到端证据；独立结论仍以本节已列明的干净库初始化、SQL 执行与旧表 ALTER 验证为基础。
