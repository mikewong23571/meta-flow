# Phase 1：实现宿主骨架、SQLite 控制面与 mock happy path

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `.agent/PLANS.md`.

This document is a subordinate ExecPlan of `docs/meta-flow-program-execplan.md`. If any statement in this phase plan conflicts with the main plan, the main plan wins. This phase plan exists to add executable detail without changing the architectural contract of the main plan.

## Purpose / Big Picture

这一阶段的目标是把仓库从纯文档状态变成一个可运行的本地 Clojure 宿主程序，并证明最小控制面闭环成立。完成后，使用者可以初始化 SQLite 和运行目录，加载 definitions，写入一个演示 task，运行调度器，并看到 task 经由 mock runtime 产出 artifact，再经 validator 接受后进入 `completed`。

本阶段不追求真实 Codex 集成，也不追求完整失败恢复矩阵。它的作用是建立一条最小且可靠的 happy path，并把四条主约束落实到代码骨架里：SQLite 是单机真相源，definitions 独立于 StateStore，projection v1 只读，event ingestion 统一入口且幂等。

## Scope Boundaries

本阶段范围内的工作包括：项目骨架、`deps.edn`、基础 CLI、DefinitionRepository、Malli schema、SQLite schema 与事务约束、StateStore、ProjectionReader、FSM 校验、统一事件写入 API、mock runtime adapter、artifact contract validator、happy path 演示命令和对应测试。

本阶段范围外的工作包括：retry path、takeover、lease timeout 恢复、provider cooldown、复杂 resource policy、真实 Codex runtime adapter、项目级 `CODEX_HOME` 安装脚本、真实 MCP 与 prompt 运行、跨主机部署、任何 projection cache。这里明确不做这些内容，是为了保证第一阶段只验证“控制面闭环成立”，而不是一次性完成全部调度复杂度。

本阶段还必须遵守主 plan 的稳定边界：单机单 SQLite 文件部署；definitions 只从 `resources/meta_flow/defs/` 读取；projection 只读；调度器的 runnable/awaiting-validation/expired-lease 输入只能来自 `ProjectionReader`；事件只能经 `ingest-run-event!` 写入；SQLite 初始化必须启用 `WAL` 和 `busy_timeout`；关键状态推进必须走显式事务与 compare-and-set。

## Progress

- [x] (2026-04-01 13:12Z) 从主 plan 提炼 Phase 1 的实现边界，并确认本阶段只覆盖宿主骨架与 mock happy path。
- [x] (2026-04-01 13:12Z) 为本阶段写出独立 subplan，明确 in-scope、out-of-scope、接口与验收标准。
- [x] (2026-04-01 14:07Z) 完成 Milestone 1：`deps.edn` 项目骨架、definitions 资源、Filesystem `DefinitionRepository`、Malli schema、`init` / `defs validate` CLI 与最小单元测试均已落地并可运行。
- [x] (2026-04-01 14:07Z) 创建 `deps.edn`、`build.clj`、`bb.edn` 和 `src/`、`resources/`、`test/`、`script/` 骨架。
- [x] (2026-04-01 14:07Z) 实现 DefinitionRepository 与默认 definitions 文件。
- [x] (2026-04-01 14:29Z) 修复 Milestone 1 review 发现的基础问题：SQLite pragma 改为每次应用连接都设置，state 过滤字面量统一为带前导 `:` 的 keyword 文本，并为 Codex runtime profile 补齐必填 definitions 字段与占位资源。
- [x] (2026-04-01 14:55Z) 完成 Milestone 2：新增 `StateStore` 协议与 SQLite 实现、只读 `ProjectionReader`、统一 `event_ingest` 入口，并用测试证明任务入队去重、同 task 单一非终态 run、同 run 单一 active lease、事件幂等与 `event/seq` 单调分配全部生效。
- [x] (2026-04-01 14:55Z) 完成 SQLite 控制面最小读写层：`upsert-collection-state!`、`enqueue-task!`、`create-run!`、`ingest-run-event!`、`attach-artifact!`、CAS `transition-task!` / `transition-run!` 与基于 view/query 的 scheduler projection 已落地。
- [ ] 实现 FSM、mock runtime adapter、artifact validator 与完整 happy path（已完成：任务入队、run 创建、lease 创建、事件吸收、artifact 挂接的持久化接口；剩余：FSM 驱动的状态推进、runtime/validator 闭环和调度器）。
- [ ] 实现 mock runtime adapter 和 artifact validator。
- [ ] 实现 `init`、`defs validate`、`demo happy-path`、`inspect` CLI（已完成：`init`、`defs validate`；剩余：`demo happy-path`、`inspect`）。
- [ ] 补齐单元测试和一个 happy-path 集成测试（已完成：definitions loader、db init、SQLite pragma、keyword-state 索引、StateStore 不变量与 ProjectionReader 的单元测试；剩余：happy-path 集成测试和后续控制面测试）。

## Surprises & Discoveries

- Observation: 主 plan 已经明确了四条关键约束，因此 Phase 1 不能把它当作“以后再补”的技术债，而必须在第一版骨架里落实。
  Evidence: `docs/meta-flow-program-execplan.md` 已把 SQLite 事务/约束、DefinitionRepository、ProjectionReader、event ingestion contract 写成硬约束。

- Observation: 当前环境没有可直接调用的 `cljfmt` 命令，所以本里程碑不能把格式检查当成默认可运行命令。
  Evidence: 在 `/home/mikewong/proj/main/meta-flow` 执行 `cljfmt check src test` 返回 `/bin/bash: line 1: cljfmt: command not found`。

- Observation: `foreign_keys` 与 `busy_timeout` 不是 SQLite 文件级属性，而是每个连接都要重新设置的 pragma。
  Evidence: review 时直接用新连接检查初始化后的数据库，`PRAGMA foreign_keys` 和 `PRAGMA busy_timeout` 都返回 `0`；修复后新增 `db/open-connection` 并用测试断言其返回 `1` 和 `5000`。

- Observation: Phase 1 的 partial unique index 若继续用无前导 `:` 的 state 文本，会和 Clojure keyword 的默认字符串表示脱节。
  Evidence: review 时在临时库中用 `state=':run.state/created'` 成功插入两条同 task run；修复后新增回归测试断言第二条插入抛出 `SQLException`。

- Observation: `create-run!` 不能顺手隐式推进 `task/state`，否则 task lifecycle 和 run lifecycle 的边界会被写层悄悄打穿。
  Evidence: 在 `sqlite_store_test` 中，为了让 projection 只暴露真正 runnable 的 task，必须显式调用 `transition-task!`；这验证了“task/run 分离”在写接口层面已经生效。

## Decision Log

- Decision: Phase 1 的成功标准只定义 mock happy path，不强行纳入 retry/takeover/cooldown。
  Rationale: 这些控制面复杂度属于后续 phase。如果第一阶段同时做失败恢复矩阵，工程反馈会混杂，无法清楚证明最小闭环是否成立。
  Date/Author: 2026-04-01 / Codex

- Decision: 即使本阶段只做 mock runtime，也必须把事件幂等写入、DefinitionRepository 和只读 projection 一起做完。
  Rationale: 这三条边界不是后续优化项，而是后面所有 phase 能否保持一致性的基础。如果 Phase 1 放松它们，后面再收紧的成本会更高。
  Date/Author: 2026-04-01 / Codex

- Decision: 在 Milestone 1 就把 `resources/meta_flow/sql/001_init.sql` 建成完整 bootstrap schema，并在 `init` 中实际创建主表、view 和关键索引。
  Rationale: 这让 `var/meta-flow.sqlite3` 从第一天起就是后续里程碑可复用的真实控制面数据库，而不是一次性占位文件；后面实现 StateStore 和 ProjectionReader 时可以直接接到同一份 schema 上。
  Date/Author: 2026-04-01 / Codex

- Decision: `:task-type/cve-investigation` 在 definitions 中默认 pin 到 `:runtime-profile/codex-worker`，同时保留 `:runtime-profile/mock-worker` 作为 workflow 默认 profile 与后续测试覆盖目标。
  Rationale: 主 plan 明确要求 CVE task type 的默认绑定面向真实 Codex runtime；同时本阶段的 `init` / `defs validate` 和后续 mock happy path 仍需要一个完全本地、可预测的 profile，因此两者都必须从第一版 definitions 就并存。
  Date/Author: 2026-04-01 / Codex

- Decision: Milestone 1 先用 `002_align_keyword_literals.sql` 修复 state 字面量和相关 index/view，而不去重写已记录的 `001_init` 历史 migration。
  Rationale: 这样既能保持已有数据库的可升级路径，也能让 `init` 在现有 workspace 上幂等修复已创建的 schema，而不是要求人工删库重建。
  Date/Author: 2026-04-01 / Codex

- Decision: `defs validate` 必须对 `:runtime-profile/codex-worker` 做 adapter-specific 校验，并要求占位 prompt/helper 路径真实存在。
  Rationale: 如果让不完整的 Codex profile 在 Phase 1 就通过 definitions 校验，后续 Milestone 3 的失败会被推迟到 runtime，定位成本更高；提早在 definitions 层报错更符合主 plan 的边界。
  Date/Author: 2026-04-01 / Codex

- Decision: Milestone 2 直接沿用 `001_init.sql` / `002_align_keyword_literals.sql` 已定下的主表和 view，不再为 StateStore 额外引入 projection cache 或新的 bootstrap migration。
  Rationale: 现有 schema 已足够承载 Phase 1 所需的 SQLite 不变量和只读 projection；继续加新表只会扩大迁移面，却不能增加 happy-path 证明力度。
  Date/Author: 2026-04-01 / Codex

- Decision: `run_events.event_payload_edn` 在 Phase 1 先持久化完整 canonical event map，而不是只保存 `:event/payload` 子树。
  Rationale: 这样 `list-run-events` 能直接返回可审计、可测试的完整事件实体，同时保持当前 schema 不变；若后续要把列名改成更精确的 `event_edn`，可以通过 migration 演进。
  Date/Author: 2026-04-01 / Codex

## Outcomes & Retrospective

Milestone 1 和 Milestone 2 现在都已经完成。当前结果是：仓库除了项目骨架与 definitions 装载以外，还具备了可直接落到 SQLite 的 `StateStore`、只读 `ProjectionReader` 和统一事件写入口。`clojure -M -m meta-flow.main init` 与 `clojure -M -m meta-flow.main defs validate` 继续通过，`clojure -M:test` 与 `clj-kondo --lint src test script` 也已通过；其中新增的 `sqlite_store_test` 已证明任务入队去重、同 task 单一非终态 run、同 run 单一 active lease、事件幂等与 `event/seq` 单调递增都真实受数据库约束保护。

本阶段仍未完成 mock happy path，所以当前回顾结论已经覆盖“宿主骨架、definitions 装载和 SQLite 控制面成立”，但还不能证明完整调度闭环。下一步应把工作集中到 FSM 服务层、mock runtime、artifact validator、`scheduler once` 和 `demo happy-path`，而不是继续扩展基础存储接口。

## Context and Orientation

本阶段要实现的程序是一个本地 workflow host。它不是库优先设计，而是可从 CLI 驱动的应用。应用的最小控制面组件包括：

`DefinitionRepository`：从 `resources/meta_flow/defs/` 读取静态定义，例如 task type、task FSM、run FSM、artifact contract、validator、runtime profile 和 resource policy。这个边界不能从 SQLite 读取 definitions。

`StateStore`：把 task、run、lease、event、artifact、assessment、disposition、collection state 持久化到 SQLite。SQLite 是权威真相源。

`ProjectionReader`：通过只读 SQL query 或只读 SQLite view 生成调度器需要的派生读模型，例如 runnable task ids、awaiting validation run ids、expired lease run ids。

`RuntimeAdapter`：为调度器提供执行平面。本阶段只实现 `mock` adapter，它的行为完全可预测，用于证明宿主控制面。

`Scheduler`：执行单轮控制动作，也就是 `schedule-once!`。它负责选择任务、创建 run 和 lease、dispatch mock worker、吸收事件、验证 artifact、写 assessment 和 disposition，并推进状态机。

这里必须再次强调本阶段的边界：只做 `queued -> leased -> running -> awaiting_validation -> completed` 的 happy path，不做失败重试矩阵；但所有状态迁移接口必须从第一天起按“未来可支持 retry/takeover”的方式设计。

## Milestones

### Milestone 1: 建立最小可运行项目与 definitions 装载

这一小里程碑结束时，仓库将具备标准 `deps.edn` Clojure 项目骨架，可以运行 `init` 和 `defs validate`。使用者可以看到 SQLite 文件和运行目录被创建，definitions 从 `resources/meta_flow/defs/` 成功加载并通过 schema 校验。

### Milestone 2: 实现 SQLite 控制面与事件吸收

这一小里程碑结束时，StateStore、ProjectionReader 和事件 ingestion API 已经存在，数据库级不变量也生效。使用者虽然还看不到完整 happy path，但已经可以通过测试证明：不能为同一 task 同时创建两个非终态 run，不能为同一 run 同时创建两个 active lease，同一个 event idempotency key 不会产生两条事件。

### Milestone 3: 跑通 mock happy path

这一小里程碑结束时，`demo happy-path` 会完成完整闭环。使用者将看到 task 进入 `completed`，run 进入 `finalized`，artifact 目录包含 contract 要求的最小文件集，inspect 命令可以读取权威状态。

## Plan of Work

先创建项目骨架。新增 `deps.edn`、`build.clj`、`bb.edn`，并创建 `src/meta_flow/`、`resources/meta_flow/defs/`、`resources/meta_flow/sql/`、`test/meta_flow/` 和 `script/`。依赖保持最小：Clojure、next.jdbc、sqlite-jdbc、Malli、cheshire、tools.cli、babashka/process。

然后实现 definitions 层。创建默认 workflow 定义和一个 `:task-type/cve-investigation`。本阶段只要求这个 task type 能跑 happy path，不要求覆盖 CVE 全部业务语义。DefinitionRepository 必须支持按 id/version 查找 definitions，并在 `defs validate` 中交叉验证所有引用的存在性与版本绑定。

接着实现 SQLite 层。`001_init.sql` 必须创建主表、必要索引和只读 view，并显式说明初始化时应用的 PRAGMA。关键约束要落到数据库层：`runs` 对 Phase 1 的非终态 state 集合建 partial unique index，`leases` 对 active state 建 partial unique index，`events` 对 `(run_id, event_idempotency_key)` 建唯一约束。StateStore 的所有复合写操作必须使用显式事务。

Phase 1 的非终态 run state 必须在文档和 SQL 中写死为同一组值：`:run.state/created`、`:run.state/leased`、`:run.state/dispatched`、`:run.state/running`、`:run.state/exited`、`:run.state/awaiting-validation`。本阶段不允许使用其他 run state 参与 partial unique index 逻辑；后续 phase 如果引入新 run state，必须同时更新 SQL 索引、FSM 定义和测试。

然后实现纯函数状态机与服务层。`fsm.clj` 只做 transition 判定和 apply，不做 IO。服务层负责用 definitions 驱动状态迁移。`enqueue-task!` 必须通过稳定的 `:task/work-key` 做去重或返回现有任务。

之后实现事件 ingestion 与 mock runtime。`event_ingest.clj` 负责把 event intent 转成持久化事件；mock runtime 只通过这个 API 写入 worker-start、heartbeat、worker-exit、artifact-ready。调用方不允许提供 `event/seq`；StateStore 必须在事务内按同一 event stream 分配单调递增序号，并按幂等键去重。mock runtime 的 artifact 输出必须是确定性的，方便测试和验收。

最后实现 `scheduler once` 和 CLI。调度器按主 plan 约定的固定顺序推进状态。CLI 必须至少支持 `init`、`defs validate`、`demo happy-path`、`inspect task`、`inspect run`、`inspect collection`。happy path demo 可以内部调用多次 `scheduler once`，但必须对用户透明说明。

## Concrete Steps

所有命令都在 `/home/mikewong/proj/main/meta-flow` 运行。

先创建目录骨架：

    mkdir -p src/meta_flow
    mkdir -p src/meta_flow/defs
    mkdir -p src/meta_flow/store
    mkdir -p src/meta_flow/service
    mkdir -p src/meta_flow/runtime
    mkdir -p resources/meta_flow/defs
    mkdir -p resources/meta_flow/sql
    mkdir -p test/meta_flow
    mkdir -p script

写入最小 `deps.edn` 后，先实现初始化命令：

    clojure -M -m meta-flow.main init

期望输出：

    Initialized database at var/meta-flow.sqlite3
    Loaded workflow definitions from resources/meta_flow/defs
    Ensured runtime directories: var/artifacts, var/runs, var/codex-home
    SQLite pragmas applied: journal_mode=WAL, busy_timeout=5000

然后实现 definitions 校验：

    clojure -M -m meta-flow.main defs validate

期望输出：

    Definitions valid
    Task types: 2
    Task FSMs: 2
    Run FSMs: 2
    Runtime profiles: 2

之后实现 happy path demo：

    clojure -M -m meta-flow.main demo happy-path

期望输出：

    Enqueued task 4df0... for CVE-2024-12345
    Created run 8a20... attempt 1
    Mock worker produced artifact var/artifacts/4df0.../8a20.../
    Assessment accepted
    Task 4df0... -> :task.state/completed

最后通过 inspect 验证权威状态：

    clojure -M -m meta-flow.main inspect task --task-id 4df0...
    clojure -M -m meta-flow.main inspect run --run-id 8a20...
    clojure -M -m meta-flow.main inspect collection

## Validation and Acceptance

本阶段完成的最低标准是 mock happy path。`init` 和 `defs validate` 必须成功；`demo happy-path` 必须让 task 进入 `:task.state/completed`、run 进入 `:run.state/finalized`；artifact 目录必须包含 `manifest.json`、`notes.md`、`run.log`。

本阶段还必须通过 inspect 或测试证明 definition pinning 已结构化落库，而不是只藏在 blob 中。至少要证明以下字段存在并可读取：`tasks` 中的 task type id/version，`runs` 中的 runtime profile id/version 与 run FSM id/version，`artifacts` 中的 artifact contract id/version，`assessments` 中的 validator id/version。

本阶段还必须用测试证明 SQLite 约束不是只写在文档里。至少要有这些测试：重复为同一 task 创建 Phase 1 非终态 run 会失败；重复为同一 run 创建 active lease 会失败；同一个 event idempotency key 重复 ingest 不会产生第二条事件；同一 event stream 的 `event/seq` 单调递增且由 store 分配；重复执行 `scheduler once` 不会破坏 happy path 收敛。

测试命令：

    clojure -M:test
    clj-kondo --lint src test script

本里程碑实际已验证以下命令成功：

    clojure -M -m meta-flow.main defs validate
    clojure -M -m meta-flow.main init
    clojure -M:test
    clj-kondo --lint src test script

执行时发现当前环境没有可直接调用的 `cljfmt` 命令，因此格式检查需要在后续里程碑中补一个 repo-local alias 或修复环境暴露方式后再纳入默认验收。

## Idempotence and Recovery

`init` 必须幂等。`defs validate` 必须只读。`demo happy-path` 可以重复运行，但每次应创建新的演示 task 或显式说明复用旧任务。

本阶段不要求完整的 crash recovery 行为，但要求所有数据库写入都是可重试的。尤其是 `ingest-run-event!` 必须具备幂等性并在事务内分配单调递增 `event/seq`，`transition-task!` 与 `transition-run!` 必须采用 compare-and-set。

## Artifacts and Notes

Milestone 1 完成后，当前最重要的文件包括：

    deps.edn
    src/meta_flow/main.clj
    src/meta_flow/schema.clj
    src/meta_flow/defs/loader.clj
    src/meta_flow/defs/protocol.clj
    src/meta_flow/db.clj
    resources/meta_flow/sql/001_init.sql
    resources/meta_flow/defs/workflow.edn
    resources/meta_flow/defs/task-types.edn
    test/meta_flow/defs_loader_test.clj
    test/meta_flow/db_test.clj

后续进入 Milestone 2 / 3 时，还需要新增 `src/meta_flow/store/sqlite.clj`、`src/meta_flow/projection.clj`、`src/meta_flow/fsm.clj`、`src/meta_flow/event_ingest.clj`、`src/meta_flow/runtime/protocol.clj`、`src/meta_flow/runtime/mock.clj`、`src/meta_flow/scheduler.clj` 和对应 happy-path 集成测试。

## Interfaces and Dependencies

本阶段必须实现以下接口边界：

    (defprotocol DefinitionRepository
      (load-workflow-defs [repo])
      (find-task-type-def [repo task-type-id version])
      (find-run-fsm-def [repo run-fsm-id version])
      (find-task-fsm-def [repo task-fsm-id version])
      (find-artifact-contract [repo contract-id version])
      (find-validator-def [repo validator-id version])
      (find-runtime-profile [repo runtime-profile-id version])
      (find-resource-policy [repo resource-policy-id version]))

    (defprotocol StateStore
      (upsert-collection-state! [store collection-state])
      (enqueue-task! [store task])
      (find-task [store task-id])
      (find-task-by-work-key [store work-key])
      (create-run! [store task run lease])
      (find-run [store run-id])
      (ingest-run-event! [store event-intent])
      (list-run-events [store run-id])
      (attach-artifact! [store run-id artifact])
      (record-assessment! [store assessment])
      (record-disposition! [store disposition])
      (transition-task! [store task-id transition now])
      (transition-run! [store run-id transition now]))

    (defprotocol ProjectionReader
      (load-scheduler-snapshot [reader now])
      (list-runnable-task-ids [reader now limit])
      (list-awaiting-validation-run-ids [reader now limit])
      (list-expired-lease-run-ids [reader now limit]))

    (defprotocol RuntimeAdapter
      (adapter-id [this])
      (prepare-run! [this ctx task run])
      (dispatch-run! [this ctx task run])
      (poll-run! [this ctx run now])
      (cancel-run! [this ctx run reason]))

本阶段依赖只允许使用主 plan 已批准的库，不引入额外生命周期框架或异步框架。

`StateStore` 在 Phase 1 不允许暴露与 projection 重复语义的读取接口，例如 `list-runnable-tasks`。凡是调度排序、runnable 候选、awaiting validation 候选、expired lease 候选，都必须通过 `ProjectionReader` 读取。

## Revision Note

2026-04-01：创建 Phase 1 subplan。该文档从主 plan 中提炼出“宿主骨架 + SQLite 控制面 + mock happy path”的执行细节，并明确本阶段不包含 retry/takeover/cooldown/Codex runtime 集成。

2026-04-01：执行 Milestone 1 后更新本 subplan：项目骨架、definitions loader、bootstrap SQL、`init` / `defs validate` CLI、最小单元测试与 lint 已落地；同时记录当前环境缺少 `cljfmt` 可执行命令，格式检查需在后续里程碑补上 repo-local 入口或环境修复。

2026-04-01：根据 review 修订本 subplan：补记 SQLite pragma 的连接级语义、keyword state 字面量与 partial unique index 的匹配约束，并记录通过 `002_align_keyword_literals.sql` 与 adapter-specific runtime profile 校验对 Milestone 1 做的 hardening。

2026-04-01：执行 Milestone 2 后更新本 subplan：新增 `src/meta_flow/store/protocol.clj`、`src/meta_flow/store/sqlite.clj`、`src/meta_flow/projection.clj`、`src/meta_flow/event_ingest.clj` 与 `src/meta_flow/sql.clj`，并通过 `test/meta_flow/sqlite_store_test.clj` 把 SQLite 控制面约束固化为可重复运行的回归测试。
