# Meta-Flow 系统扩展指南

> 生成日期：2026-04-03  
> 基于源码版本：master（d29529c）

本文档聚焦于系统各层的**可扩展点**，以及 **workflow 层级业务扩展**的具体实施方式。每个结论均附源码引用。

---

## 扩展点全景

系统的扩展性由三类机制支撑：

| 机制 | 说明 | 适用场景 |
|------|------|----------|
| **EDN 定义文件** | 纯数据配置，运行时只读 | 新增任务类型、FSM、验证规则、资源策略 |
| **协议实现** | Clojure protocol，依赖注入式 | 新增执行后端、存储后端、定义来源 |
| **调度器阶段钩子** | `scheduler/step.clj` 中的顺序步骤 | 新增调度逻辑、前/后处理 |

---

## 一、Workflow 层级业务扩展（核心）

### 1.1 什么是一个"业务类型"

一个业务类型由以下六个定义对象组成，它们通过 `task-type` 聚合为一个整体：

```
task-type
  ├── task-fsm-ref        → task 生命周期状态机
  ├── run-fsm-ref         → run 执行实例状态机
  ├── runtime-profile-ref → 执行后端配置（adapter + 超时等）
  ├── artifact-contract-ref → 产物结构契约
  ├── validator-ref       → 验证规则
  └── resource-policy-ref → 并发/重试/心跳策略
```

**源码依据**：`resources/meta_flow/defs/task-types.edn:1-32` — 现有的 `cve-investigation` 类型展示了完整的六元组结构。

---

### 1.2 新增一个业务类型的完整步骤

以"代码审计任务"（`:task-type/code-audit`）为例，展示每个步骤。

#### 步骤 1：定义 artifact-contract

在 `resources/meta_flow/defs/artifact-contracts.edn` 中追加：

```edn
{:artifact-contract/id :artifact-contract/code-audit
 :artifact-contract/version 1
 :artifact-contract/name "Code audit artifact contract"
 :artifact-contract/required-paths ["manifest.json" "audit-report.md" "run.log"]
 :artifact-contract/optional-paths ["findings.edn" "metrics.json"]}
```

**为什么**：验证服务在 `scheduler/validation.clj:22-26` 通过 `task/artifact-contract-ref` 查找合约，再调用 `service.validation/assess-required-paths`。合约决定哪些文件必须存在于 artifact 目录。

#### 步骤 2：定义 validator

在 `resources/meta_flow/defs/validators.edn` 中追加：

```edn
{:validator/id :validator/code-audit-bundle
 :validator/version 1
 :validator/name "Code audit bundle validator"
 :validator/type :validator.type/required-paths}
```

**为什么**：`scheduler/validation.clj:29` 将 `validator-ref` 写入 assessment 记录作为审计溯源。当前所有 validator 均为 `:validator.type/required-paths`（详见下方扩展点 3.2）。

#### 步骤 3：定义 resource-policy

在 `resources/meta_flow/defs/resource-policies.edn` 中追加：

```edn
{:resource-policy/id :resource-policy/code-audit
 :resource-policy/version 1
 :resource-policy/name "Code audit policy"
 :resource-policy/max-active-runs 3
 :resource-policy/max-attempts 3
 :resource-policy/heartbeat-timeout-seconds 300
 :resource-policy/queue-order :resource-policy.queue-order/created-at}
```

**为什么**：`scheduler/shared.clj:52-56` 的 `task-policy` 函数从 task 的 `task/resource-policy-ref` 查找这条记录。`max-active-runs` 控制并发上限（在 `scheduler/step.clj:113-116` 的 `dispatch-runnable-tasks!` 中使用），`max-attempts` 控制重试上限（在 `scheduler/retry.clj:10-14` 的 `retry-action` 中使用），`heartbeat-timeout-seconds` 控制心跳超时（在 `scheduler/shared.clj:58-60` 的 `task-heartbeat-timeout-seconds` 中使用）。

#### 步骤 4：定义 runtime-profile

在 `resources/meta_flow/defs/runtime-profiles.edn` 中追加：

```edn
{:runtime-profile/id :runtime-profile/code-audit-worker
 :runtime-profile/version 1
 :runtime-profile/name "Code audit worker"
 :runtime-profile/adapter-id :runtime.adapter/codex   ; 或新实现的 adapter
 :runtime-profile/dispatch-mode :runtime.dispatch/external
 :runtime-profile/worker-prompt-path "meta_flow/prompts/code-audit-worker.md"
 :runtime-profile/helper-script-path "script/worker_api.bb"
 :runtime-profile/artifact-contract-ref {:definition/id :artifact-contract/code-audit
                                         :definition/version 1}
 :runtime-profile/worker-timeout-seconds 2400
 :runtime-profile/heartbeat-interval-seconds 60
 :runtime-profile/env-allowlist ["ANTHROPIC_API_KEY" "PATH"]}
```

**为什么**：`scheduler/runtime.clj:22-24` 的 `runtime-adapter-for-run` 从 run 的 `run/runtime-profile-ref` 查找 profile，再用 `profile/adapter-id` 到 `runtime/registry.clj` 查找适配器实例。`adapter-id` 是 runtime 层的唯一路由键。

#### 步骤 5：定义 FSM（通常可复用现有的）

大多数业务类型可直接引用 `:task-fsm/default v3` 和 `:run-fsm/default v2`。只有当业务有**不同的状态转换规则**时才需要新建。

如需新建，在 `resources/meta_flow/defs/task-fsms.edn` 追加一个完整的 FSM 对象。FSM 结构见 `task-fsms.edn:1-46`；转换检查在 `control/fsm.clj:3-29` 通过 `ensure-transition!` 进行。

#### 步骤 6：定义 task-type（聚合引用）

在 `resources/meta_flow/defs/task-types.edn` 追加：

```edn
{:task-type/id :task-type/code-audit
 :task-type/version 1
 :task-type/name "Code audit"
 :task-type/description "Static analysis and security review of a code repository."
 :task-type/task-fsm-ref {:definition/id :task-fsm/default
                          :definition/version 3}
 :task-type/run-fsm-ref {:definition/id :run-fsm/default
                         :definition/version 2}
 :task-type/runtime-profile-ref {:definition/id :runtime-profile/code-audit-worker
                                 :definition/version 1}
 :task-type/artifact-contract-ref {:definition/id :artifact-contract/code-audit
                                   :definition/version 1}
 :task-type/validator-ref {:definition/id :validator/code-audit-bundle
                           :definition/version 1}
 :task-type/resource-policy-ref {:definition/id :resource-policy/code-audit
                                 :definition/version 1}}
```

**为什么**：`defs/repository.clj:26` 的 `find-task-type-def` 以 `[task-type-id version]` 为键查找记录；枚举任务时调用者从 task 的 `task/task-type-ref` 提取这个 ref 组合。

#### 步骤 7：创建 worker prompt

在 `resources/meta_flow/prompts/` 下创建对应的 `.md` 文件。这是 `runtime-profile` 中 `worker-prompt-path` 所指向的内容，由 runtime adapter 在 `prepare-run!` 或 `dispatch-run!` 阶段注入给 worker 进程。

---

### 1.3 多 Workflow 定义

当前 `resources/meta_flow/defs/workflow.edn` 是单文件单 workflow 结构：

```edn
{:workflow/id :workflow/meta-flow
 :workflow/version 1
 :workflow/default-task-type-ref {:definition/id :task-type/cve-investigation
                                  :definition/version 1}
 ...}
```

`DefinitionRepository` 协议（`defs/protocol.clj:4`）中 `load-workflow-defs` 的返回值是整个 defs map，多 workflow 支持需要两个前提：

1. 将 `workflow.edn` 改为向量（EDN list），每条记录对应一个 workflow
2. `defs/repository.clj:24` 的 `load-workflow-defs` 实现已经返回完整的 defs，调用方需按 `workflow/id` 检索

**当前限制**：`defs/source.clj:8-15` 中 `definition-files` 是一个静态映射，文件名固定。若要支持动态多文件，需要修改 `load-definition-data` 逻辑（对每类 definition 扫描目录而非加载固定文件名）。

---

### 1.4 定义版本演化规则

所有定义通过 `[id version]` 二元组索引（`defs/repository.clj:26-37`）。版本演化规则：

- **向前兼容更改**（如增加 optional-paths）：新增版本号，同时保留旧版本 EDN 条目，存量运行的 task 仍引用旧版本
- **破坏性更改**（如移除状态、改变超时）：必须新版本 + 新 task-type-ref，因为 task 在入队时已锁定 `task/task-fsm-ref`、`task/runtime-profile-ref` 等引用（见 `store/protocol.clj:7`）

---

## 二、Runtime Adapter 扩展

### 2.1 协议接口

`runtime/protocol.clj:3-8` 定义了五个方法：

| 方法 | 职责 | 调用时机 |
|------|------|----------|
| `adapter-id` | 返回标识符 keyword | 注册表路由 |
| `prepare-run!` | 创建工作目录、写入 context | `scheduler/runtime.clj:275`，`claim-task-for-run!` 之后 |
| `dispatch-run!` | 启动外部进程/任务，返回 execution handle | `scheduler/runtime.clj:276` |
| `poll-run!` | 查询进程状态，返回 event intent 列表 | `scheduler/step.clj:88`，每个调度周期对所有活跃 run 调用 |
| `cancel-run!` | 取消正在运行的任务 | 超时恢复路径（目前未被主流程显式调用，预留接口） |

### 2.2 实现步骤

1. 新建 `src/meta_flow/runtime/<name>.clj`，实现 `RuntimeAdapter` protocol
2. 在 `runtime/registry.clj:4-9` 的 `case` 语句中添加新的 `adapter-id` 分支：

```clojure
; runtime/registry.clj — 当前实现
(defn runtime-adapter
  [adapter-id]
  (case adapter-id
    :runtime.adapter/mock (mock/mock-runtime)
    ; 新增：
    :runtime.adapter/my-backend (my-backend/my-runtime)
    (throw (ex-info ...))))
```

3. 在 runtime-profile EDN 中将 `:runtime-profile/adapter-id` 设置为新 keyword

**`poll-run!` 返回值约定**：必须返回 event intent 向量，每个 intent 符合 `event-ingest/ingest-run-event!` 的入参格式（含 `:event/type`、`:event/run-id`、`:event/emitted-at`、`:event/idempotency-key` 等字段）。这是 poll → ingest → state apply 链路的接缝（`scheduler/runtime.clj:48-56`）。

---

## 三、Validation 服务扩展

### 3.1 当前实现

`service/validation.clj:8-23` 目前只实现了 `assess-required-paths`，检查 artifact 目录中是否存在所有 required-paths。调用方是 `scheduler/validation.clj:25`：

```clojure
; scheduler/validation.clj:25
(outcome (service.validation/assess-required-paths artifact-root contract))
```

validator 的 `:validator/type` 字段目前仅用于元数据记录，**未参与分发逻辑**。

### 3.2 扩展验证器类型

若需要内容校验、schema 校验、签名校验等逻辑，扩展路径为：

1. 在 `validators.edn` 中为新 validator 声明新的 `:validator/type`（如 `:validator.type/edn-schema`）
2. 在 `service/validation.clj` 中增加对应的校验函数
3. 修改 `scheduler/validation.clj:25` 的调用点，改为按 `validator/type` 分派：

```clojure
; scheduler/validation.clj — 当前单一调用
(outcome (service.validation/assess-required-paths artifact-root contract))

; 扩展后的分派方式（示意）
(let [validator-def (defs.protocol/find-validator-def defs-repo ...)]
  (service.validation/assess! artifact-root contract validator-def))
```

这个改动涉及 `scheduler/validation.clj:15-62` 中的 `assess-run!` 函数和 `service/validation.clj`，影响范围可控。

---

## 四、调度器阶段扩展

`scheduler/step.clj:140-161` 的 `run-scheduler-step` 是五个阶段的顺序编排：

```clojure
; scheduler/step.clj:140-161
(defn run-scheduler-step [db-path]
  (let [env (scheduler-env db-path) ...]
    (recover-expired-leases! env)          ; 阶段 1：恢复过期 lease
    (poll-active-runs! env)                ; 阶段 2：轮询活跃 run
    (recover-heartbeat-timeouts! env)      ; 阶段 3：恢复心跳超时
    (process-awaiting-validation! env)     ; 阶段 4：触发验证
    (dispatch-runnable-tasks! env ...)     ; 阶段 5：调度新任务
    (retry/process-retryable-failures! env ...))) ; 阶段 6：处理重试
```

每个阶段是独立函数，可以单独替换或在其前后插入新阶段。常见的扩展方向：

| 新需求 | 在哪个阶段插入 | 说明 |
|--------|--------------|------|
| 优先级队列 | 阶段 5 之前 | 修改 `list-runnable-task-ids` 排序逻辑（`control/projection.clj:27-32`）|
| 任务超时（wall-clock）| 阶段 1 后 | 扫描超时 task，发出新事件 |
| 完成后通知（webhook）| 阶段 4 之后 | 查找刚完成的 task，调用外部 API |
| 批量调度 | 阶段 5 | 修改 `dispatch-runnable-tasks!`，一次性调度多个 run |

**注意**：`scheduler-env` 在每次调用 `run-scheduler-step` 时重建（`scheduler/step.clj:13-19`），调度器无进程内状态。所有新阶段应遵循同样的无状态设计。

---

## 五、ProjectionReader 扩展

`control/projection.clj:7-15` 的 `ProjectionReader` protocol 是调度器读取输入的唯一路径。当前实现 `SQLiteProjectionReader` 依赖两个 SQL 视图（`runnable_tasks_v1`、`awaiting_validation_runs_v1`）和直接 SQL 查询。

需要新查询维度时（如"按 task-type 分组的队列深度"），有两种扩展方式：

1. 在 `ProjectionReader` 协议中增加方法（影响所有实现，需同步更新 `SQLiteProjectionReader`）
2. 创建专用的 reader 函数直接操作 SQLite（适合监控/报表场景，不进入调度主循环）

---

## 六、StateStore / DefinitionRepository 后端扩展

| 协议 | 当前实现 | 扩展场景 |
|------|----------|----------|
| `StateStore`（`store/protocol.clj:3-24`）| `SQLiteStateStore` | 分布式部署时换成 PostgreSQL / CockroachDB 实现 |
| `DefinitionRepository`（`defs/protocol.clj:3-11`）| `FilesystemDefinitionRepository` | 从数据库或远程配置中心加载定义，支持热更新 |

`FilesystemDefinitionRepository`（`defs/repository.clj:21-38`）使用 `atom` 缓存加载结果，进程生命周期内定义不变。若需要热更新，需将 `cache` 的失效策略替换为基于文件 mtime 或版本号的失效策略。

---

## 七、当前空缺的扩展点（技术债）

以下扩展点当前**未实现**，扩展时需从零构建：

### 7.1 可观测性

系统无 metrics endpoint、无分布式 trace。`run-scheduler-step` 的返回值（`scheduler/step.clj:157-161`）包含了每轮的完整统计信息，是接入 metrics 的最自然接缝：

```clojure
; scheduler/step.clj:157-161 — 返回值包含所有计数
{:created-runs created-runs
 :requeued-task-ids requeued-task-ids
 :escalated-task-ids escalated-task-ids
 :snapshot snapshot   ; 含 runnable-count、awaiting-validation-count 等
 ...}
```

在调用 `run-scheduler-step` 的外层循环处（`scheduler.clj` 或 `main.clj`）消费这个返回值，向 Prometheus / DataDog 等系统写入指标即可，**无需修改调度器内部**。

### 7.2 操作审计日志

当前无人工介入操作的审计记录。`store/protocol.clj:4`（`upsert-collection-state!`）是暂停/恢复调度的写入路径，扩展时需在此处增加操作日志。

### 7.3 多调度器协调

`scheduler/shared.clj:31-44` 的 `ensure-collection-state!` 假设 `collection/default` 是全局单例。水平扩展需要在 `StateStore` 层引入分布式锁或 leader election，或将 `collection_state` 表的写入改为 CAS（`store/protocol.clj:4` 的 `upsert-collection-state!` 已具备语义，但需要数据库级的原子性保证）。

---

## 扩展决策速查

| 想要做什么 | 修改哪里 | 影响范围 |
|-----------|---------|---------|
| 新增一种业务任务类型 | 6 个 EDN 文件 + 1 个 prompt | 零代码改动 |
| 新增执行后端（如 Kubernetes Job）| `runtime/<name>.clj` + `runtime/registry.clj` | 2 个文件 |
| 新增验证逻辑 | `service/validation.clj` + `scheduler/validation.clj` | 2 个文件 |
| 新增调度阶段 | `scheduler/step.clj` | 1 个文件 |
| 新增查询视图 | SQL migration + `control/projection.clj` | 2 个文件 |
| 定义来源改为数据库 | 实现 `DefinitionRepository` protocol | 新文件，无需改调用方 |
| 存储后端替换 | 实现 `StateStore` protocol | 新文件，无需改调用方 |
