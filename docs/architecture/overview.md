# Meta-Flow 系统架构文档

> 生成日期：2026-04-03

---

## 总体架构

Meta-Flow 是一个**工作流编排主机**，面向无人值守运行设计。所有权威状态存储于 SQLite，调度器无进程内状态，随时可从数据库恢复。

### 分层结构

```
┌─────────────────────────────────────────────────────┐
│  Definitions Layer  (EDN, read-only at runtime)     │
│  FSMs · TaskTypes · Contracts · Validators          │
└──────────────────────────┬──────────────────────────┘
                           │ versioned refs
┌──────────────────────────▼──────────────────────────┐
│  Scheduler  (single entry point per cycle)          │
│  recover → poll → validate → dispatch → retry       │
└──┬──────────────┬──────────────┬────────────────────┘
   │              │              │
   ▼              ▼              ▼
StateStore   RuntimeAdapter  ValidationService
(SQLite)    (mock / codex)   (artifact checks)
```

---

## 核心数据流

```
enqueue-task! (idempotent by work_key)
  → queued
    → create-run! + grant-lease
      → prepare-run! + dispatch-run!
        → poll-run! (ingest events)
          → awaiting-validation
            → validate artifact → assessment + disposition
              → completed  (success)
              → retryable-failed → retry or needs-review
```

---

## 双层 FSM 设计

Task 和 Run 是两个**独立但同步推进**的状态机。Task 代表逻辑工作流（跨重试持久），Run 代表一次执行实例。

### Task FSM（7 个状态）

```
queued ──lease-granted──► leased ──worker-started──► running
  ▲                          │                          │
  └────requeued──────────────┤                          │
  └────lease-expired─────────┘◄─heartbeat-timed-out────┘
                                                        │
running ──artifact-ready──► awaiting-validation         │
  └──lease-expired / hb-timeout──► retryable-failed ◄───┘

awaiting-validation ──assessment-accepted──► completed
                   └──assessment-rejected──► retryable-failed

retryable-failed ──retry-exhausted──► needs-review  (terminal)
```

### Run FSM（8 个状态）

```
created → leased → dispatched → running → exited → awaiting-validation
                                                          │
                            retryable-failed (terminal) ◄─┤
                            finalized (terminal) ◄─────────┘
```

---

## 关键协议

| 协议 | 实现 | 职责 |
|------|------|------|
| `StateStore` | `SQLiteStateStore` | 所有运行时状态的单一真相源 |
| `DefinitionRepository` | `FilesystemDefinitionRepository` | 版本化配置的只读访问 |
| `RuntimeAdapter` | `MockRuntimeAdapter` / Codex | 可插拔执行后端 |

---

## 调度器单步算法（`scheduler/step.clj`）

每次调用独立执行，无进程内状态：

1. **恢复阶段** — 扫描过期 lease、心跳超时的 run，发出超时事件并推进 FSM
2. **轮询阶段** — 对所有活跃 run 调用 `poll-run!`，摄入返回事件
3. **验证阶段** — 对 `awaiting-validation` 的 run 执行 artifact 校验，记录 assessment/disposition
4. **调度阶段** — 在 `max-active-runs` 容量约束内，派发 queued 任务到 runtime
5. **重试阶段** — 对 `retryable-failed` 任务：未超 `max-attempts` 则 requeue，否则升级为 `needs-review`

---

## 一致性保证机制

| 机制 | 实现位置 | 目的 |
|------|----------|------|
| 乐观锁 | 所有 `transition-*!` | 防止并发状态覆盖 |
| Lease | `leases` 表 + UNIQUE 索引 | 防止重复派发 |
| 事件幂等键 | `run_events.event_idempotency_key` | 去重事件 |
| 水位线重放 | `last-applied-event-seq` | 增量应用事件流 |
| Work key 唯一索引 | `tasks.work_key` | `enqueue-task!` 幂等 |

---

## 状态存储 Schema

**核心表结构：**

| 表 | 主键 | 关键约束 | 用途 |
|----|------|----------|------|
| `collection_state` | — | — | 资源策略配置、调度暂停标志 |
| `tasks` | `task_id` | `UNIQUE(work_key)` | 任务生命周期 |
| `runs` | `run_id` | `UNIQUE(task_id, non-terminal)` | 执行实例，每个任务同时只有一个非终态 run |
| `leases` | `lease_id` | `UNIQUE(run_id, active)` | 执行锁，每个 run 同时只有一个活跃 lease |
| `run_events` | `(run_id, event_seq)` | `UNIQUE(run_id, idempotency_key)` | 只增不减的事件日志 |
| `artifacts` | `artifact_id` | — | 工件引用（文件系统路径） |
| `assessments` | `assessment_id` | — | 验证结果 |
| `dispositions` | `disposition_id` | — | 最终判定（accept / reject） |

**视图：**
- `runnable_tasks_v1` — 调度器派发查询
- `awaiting_validation_runs_v1` — 待验证 run 查询

**SQLite 连接参数：** WAL 模式、`foreign_keys=ON`、`busy_timeout=5000ms`

---

## 定义版本化

所有定义类型均带版本号，通过 `{:definition/id :task-fsm/default :definition/version 3}` 形式引用。运行中的 task/run 锁定其引用的定义版本，支持定义演化而不影响存量实例。

**定义类型：**
- `task-fsms.edn` — 任务状态机
- `run-fsms.edn` — 执行状态机
- `task-types.edn` — 任务类型与关联配置
- `runtime-profiles.edn` — 执行器配置（adapter 类型、超时等）
- `artifact-contracts.edn` — 工件结构契约
- `validators.edn` — 验证器规则
- `resource-policies.edn` — 容量与重试策略

---

## 源码规模

| 模块 | 行数 | 说明 |
|------|------|------|
| `scheduler/` | 570 | 调度主循环 |
| `store/` (含子目录) | 851 | 状态持久化 |
| `control/` | 311 | 事件摄入、FSM、投影查询 |
| `defs/` | 137 | 定义仓库 |
| `runtime/` | 103 | 执行适配器 |
| `db.clj` + `sql.clj` | 278 | 数据库连接与 SQL 工具 |
| `schema.clj` | 135 | Malli 验证 schema |
| **合计** | **~3,800** | |

---

## 潜在风险与技术债

### 可扩展性

- **单调度器假设**：当前无多实例协调机制；水平扩展需引入分布式锁
- **事件表无归档**：`run_events` 只增不减，长期运行需要归档或分区策略
- **轮询规模**：每轮轮询所有活跃 run，百量级后延迟会增长

### 并发边界

- 超时恢复中的竞争条件通过再次检查状态缓解，但当前抛出异常而非优雅降级（`scheduler/runtime.clj`）
- `recover-run-startup-failure!` 假设故障前无事件写入，若事件已写入则恢复逻辑可能跳过

### 可观测性

- 无分布式追踪 / Metrics 直方图，仅有结构化日志和 snapshot 计数
- 无人工介入操作的审计日志（手动暂停、强制重试等）

### 定义演化

- 版本化是手动的，无自动迁移支持
- 定义校验仅在 repository 加载时触发，运行中的任务不重新验证
