# 实现 `meta-flow`：一个基于 Clojure 的受控工作流宿主程序

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `.agent/PLANS.md`.

## Purpose / Big Picture

这次工作要把当前仓库里分散的架构文档，收敛成一个可以直接实现的程序设计。实现完成后，使用者可以在当前仓库里启动一个本地的 Clojure 工作流宿主程序，把任务写入 SQLite，把一次执行尝试建模为 `run`，由调度器按资源策略启动短生命周期 worker，最终把产物写到文件系统并由验证器决定 `completed`、`retryable_failed` 或 `needs_review`。

这个程序不是把系统状态放进 agent 会话，而是把系统真相外置到 SQLite，把 worker 作为受控执行引擎。最小可见成功标准是：在本地执行一次演示命令后，能够看到一个任务从 `queued` 经过 `leased`、`running`、`awaiting_validation`，最后进入 `completed`，同时在 `var/artifacts/...` 下看到该次 run 的产物目录。更严格的成功标准是：同一套主程序还能切换到失败和重试路径，并在接入 Codex runtime 后保持相同的控制面模型。

## Progress

- [x] (2026-04-01 12:52Z) 读取 `.agent/PLANS.md`，确认必须产出自包含、可执行、可验证、面向新人的 ExecPlan。
- [x] (2026-04-01 12:56Z) 读取 `docs/CLOJURE-TOOLCHAIN.md`，确认本机已具备 JDK 21、Clojure CLI、babashka、clj-kondo、clojure-lsp、cljfmt，可按 `deps.edn` 工作流落地。
- [x] (2026-04-01 12:59Z) 读取 `docs/clojure-workflow-data-model.md`、`docs/abstract-workflow-architecture.md`、`docs/current-application-reference.md`、`docs/codex-controlled-execution-pattern.md`，收敛出程序必须保留的核心边界：定义层、运行时状态层、投影层，task 和 run 分离，状态外置，worker 短生命周期，Codex 通过 runtime adapter 接入。
- [x] (2026-04-01 13:06Z) 确定总体设计方向：先做一个通用 workflow host，再内置一个具体的 `:task-type/cve-investigation` 任务类型；实现顺序采用 “mock runtime 先通路，Codex runtime 后接入”。
- [x] (2026-04-01 13:12Z) 起草完整程序设计、模块边界、SQLite 模型、运行目录、CLI、测试与验收路径。
- [x] (2026-04-01 13:12Z) 完成架构 review 并收紧四项关键约束：SQLite 事务与数据库级不变量、definitions 与 StateStore 拆分、projection v1 明确为只读查询层、event ingestion contract 明确为单入口幂等写入。
- [x] (2026-04-01 14:07Z) 创建项目骨架文件，包括 `deps.edn`、`build.clj`、`bb.edn`、`src/`、`resources/`、`test/`、`script/`、`var/` 运行目录初始化逻辑，以及 Phase 1 所需的 definitions 资源与 SQL bootstrap 文件。
- [x] (2026-04-01 14:07Z) 完成 Phase 1 / Milestone 1：实现 Malli-backed definitions schema、Filesystem `DefinitionRepository`、`init` / `defs validate` CLI、SQLite bootstrap 初始化，并通过 `clojure -M:test` 与 `clj-kondo` 验证。
- [x] (2026-04-01 14:29Z) 加固 Milestone 1 基础约束：新增 per-connection SQLite pragma 应用入口、`002_align_keyword_literals.sql` 修复 migration、Codex runtime profile 必填字段校验，以及针对 pragma 和 keyword-state 索引的回归测试。
- [x] (2026-04-01 14:55Z) 完成 Phase 1 / Milestone 2：落地 `StateStore`、`ProjectionReader` 与统一事件写入口，并通过新增控制面测试证明任务入队去重、单 task 非终态 run 约束、单 run active lease 约束、事件幂等与 event sequence 分配均按计划生效。
- [x] (2026-04-01 16:36Z) 完成 Phase 1 / Milestone 3：修复 FSM 与 mock runtime 的编译/语义漂移，落地 `scheduler once`、`demo happy-path`、`inspect` CLI 与 happy-path 集成测试，证明最小控制面闭环已从 `queued` 收敛到 `completed`。
- [x] (2026-04-02 08:34Z) 完成 Phase 2 / Milestone 1：扩展 task/resource policy definitions，引入 `max-attempts`、`needs-review`、显式 `requeue` 调度阶段，以及与之对应的 projection、CLI summary 和重试/升级测试，`bb check` 通过。
- [x] (2026-04-02 09:51Z) 完成 Phase 2 / Milestone 2：将 heartbeat timeout 纳入 resource policy pinning 与 run snapshot，新增只读 timeout projection、scheduler recovery、CLI summary 和超时恢复测试，`bb check` 通过。
- [x] (2026-04-02 16:53Z) 完成 Phase 2 / Milestone 3：扩展 collection-level dispatch cooldown、按 task resource policy `queue-order` 的优先级排序、按 pinned `resource-policy-ref` 的 resource ceiling 统计，并同步补齐 scheduler summary、CLI 和测试；`bb test` 通过。
- [x] (2026-04-03 00:12Z) 完成 Phase 3 / Milestone 1：接入 `:runtime.adapter/codex` registry wiring，落地真实 run workdir snapshot、项目级 `CODEX_HOME` 模板安装与 `runtime init-codex-home` CLI，并把 Codex worker prompt 从 placeholder 收敛为受控运行说明；`bb fmt:check`、`bb lint`、`bb test` 通过。
- [x] (2026-04-03 03:20Z) 完成 Phase 3 / Milestone 2：实现 `script/worker_api.bb` 到 `meta-flow.runtime.codex.worker-api` 的 helper 桥接、真实外部 stub worker dispatch、helper 事件回写、poller 兜底恢复与去重边界，并通过新增 Codex runtime / helper 测试、`bb fmt:check`、`bb lint`、`bb test` 与 `bb check` 的 warning-level gate。
- [ ] 完成显式 opt-in 的 Codex smoke path，并把新增 Codex helper / worker namespace 的覆盖率从 warning 区间拉回治理线以上。

## Surprises & Discoveries

- Observation: 当前仓库没有任何应用代码，只有设计文档。
  Evidence: `rg --files` 只返回 `docs/...` 和 `.agent/PLANS.md`，没有 `deps.edn`、`src/`、`test/`。

- Observation: 当前目录不是 Git 仓库，不能依赖 Git 状态作为实现前提。
  Evidence: 在 `/home/mikewong/proj/main/meta-flow` 执行 `git status --short` 返回 `fatal: not a git repository (or any of the parent directories): .git`。

- Observation: 本机已经具备现代 Clojure 工具链，因此项目不需要把“安装工具”当成首要里程碑。
  Evidence: `docs/CLOJURE-TOOLCHAIN.md` 明确列出了 JDK 21、Clojure CLI 1.12.4、babashka、clj-kondo、clojure-lsp、cljfmt、kaocha 等已安装工具。

- Observation: 当前环境没有可直接调用的 `cljfmt` 可执行命令，因此格式校验暂时不能依赖 `cljfmt check ...`。
  Evidence: 在 `/home/mikewong/proj/main/meta-flow` 执行 `cljfmt check src test` 返回 `/bin/bash: line 1: cljfmt: command not found`。

- Observation: `PRAGMA foreign_keys` 和 `PRAGMA busy_timeout` 都是连接级设置，不会因为 `init` 时执行过一次就自动作用于后续连接。
  Evidence: review 时用新连接检查 `var/meta-flow.sqlite3`，`sqlite3 ... 'PRAGMA foreign_keys;'` 与 `sqlite3 ... 'PRAGMA busy_timeout;'` 都返回 `0`；修复后通过 `db/open-connection` 复查 `PRAGMA foreign_keys` 返回 `1`。

- Observation: SQLite partial unique index 里的 state 字面量若不带前导 `:`，就无法匹配 Clojure keyword 的常规字符串化结果。
  Evidence: review 时在临时库中插入两条 `state=':run.state/created'` 的同 task `runs` 记录能够同时成功，证明旧索引条件不会命中；修复后新增测试覆盖该场景。

- Observation: 当 task 和 run 真正拆成两条持久化生命周期后，`create-run!` 不能顺手替调用方修改 `task/state`，否则 projection 会被存储层隐式污染。
  Evidence: 新增的 `sqlite_store_test` 只有在显式调用 `transition-task!` 后，`runnable_tasks_v1` 才会只返回仍处于 `:task.state/queued` 的 task。

- Observation: “Milestone 3 文件已经存在” 不等于最小闭环已经可运行；真正打通 happy path 之前，还需要修复代码生成和 definitions 之间的细小漂移。
  Evidence: `clojure -M -e "(require 'meta-flow.scheduler)"` 一开始因为 `src/meta_flow/fsm.clj` 语法错误而失败；修复后继续发现 mock runtime 发出的 `:run.event/worker-exit` 与 definitions 里 run FSM 的 `:run.event/worker-exited` 不一致，导致 run 无法进入 `:run.state/exited`。

- Observation: heartbeat timeout 与 lease expiry 虽然都走“释放 lease 并收敛到 retryable-failed”的同层级恢复路径，但测试夹具必须把两者的时间窗口明确分开，否则 lease expiry 会掩盖 heartbeat timeout。
  Evidence: 首轮 heartbeat timeout 测试把 lease expiry 时间设得过近，ProjectionReader 先命中了 expired lease 集合；把 lease TTL 拉远后，heartbeat timeout recovery 与 summary 计数按预期稳定出现。

- Observation: dispatch 一旦真正开始尊重 task resource policy 的 queue-order，部分“通过伪造 runnable id 顺序驱动语义”的测试就必须改成显式对齐 policy 排序。
  Evidence: Milestone 3 初版让 `dispatch_capacity_test` 中的 codex task 不再稳定地先于 mock task 被调度；把测试 work key 调整为与 `:resource-policy.queue-order/work-key` 对齐后，失败路径再次稳定可重复。

## Decision Log

- Decision: 整个程序采用 “通用 workflow engine + 内置一个具体 CVE task type” 的结构，而不是直接写死成 CVE 专用程序。
  Rationale: 仓库中的核心文档已经把 CVE 场景抽象成通用 workflow 架构；若一开始就把调度器、状态机和 SQLite 结构写死为 CVE 专用，将来很难复用 runtime adapter、resource policy、assessment/disposition 分离等核心设计。
  Date/Author: 2026-04-01 / Codex

- Decision: 运行时的规范表示使用 plain map 加 namespaced keyword，并用 Malli 校验边界。
  Rationale: 这与 `docs/clojure-workflow-data-model.md` 保持一致，便于在 EDN、JSON、SQLite、日志和测试之间移动数据，同时避免把核心语义藏在 `defrecord` 或面向对象结构里。
  Date/Author: 2026-04-01 / Codex

- Decision: SQLite 层采用 “控制字段列 + 原始 map blob” 的混合持久化方案。
  Rationale: 调度器需要高效过滤 `state`、`attempt`、`lease`、`updated_at`、`resource key` 等控制字段；同时任务输入、策略、结果、证据摘要又需要可扩展的 map 结构。只做纯 JSON/EDN blob 会让查询和约束过弱，只做完全范式化会让 schema 变得过重且演进成本高。
  Date/Author: 2026-04-01 / Codex

- Decision: 第一阶段必须先实现 mock runtime adapter，再实现 Codex runtime adapter。
  Rationale: 如果一开始就把验证闭环建立在真实 Codex 上，任何 prompt、权限、外部工具、搜索波动都可能掩盖宿主调度器自身的问题。mock runtime 可以先证明 task/run/lease/event/artifact/assessment/disposition 闭环成立。
  Date/Author: 2026-04-01 / Codex

- Decision: 调度器默认以 `schedule once` 的 fresh 会话形式运行，循环运行只是包装层，不先做常驻 daemon。
  Rationale: 现有文档强调 fresh `mgr` 模式。把一次调度周期做成幂等的单轮控制动作，更容易测试、恢复、无人值守和后续接管。
  Date/Author: 2026-04-01 / Codex

- Decision: worker 事件回写优先通过 babashka helper 脚本完成，而不是让 worker 直接内嵌复杂 JDBC 代码。
  Rationale: worker 需要快速、短命、可脚本化。babashka 更适合作为 Codex runtime 内调用的本地 helper，既快，又能共享同一个 SQLite 协议和事件格式。
  Date/Author: 2026-04-01 / Codex

- Decision: 本计划的部署边界在 v1 被固定为“单机单 SQLite 文件”，不为多主机共享调度做设计。
  Rationale: 当前架构同时依赖本地 SQLite、本地 artifact 目录、本地 run workdir 和项目级 `CODEX_HOME`。在这个边界内，SQLite 的简单性和可审计性优于更重的客户端/服务端数据库；一旦跨主机，这套边界就不成立，必须重新设计。
  Date/Author: 2026-04-01 / Codex

- Decision: StateStore 不负责加载 definitions；definitions 由独立的 DefinitionRepository 从 `resources/` 读取。
  Rationale: definitions 是随代码版本化的静态数据，SQLite 里的权威状态是 runtime truth。把二者塞进同一个接口会模糊定义层和运行时层的边界，违背数据模型文档的核心约束。
  Date/Author: 2026-04-01 / Codex

- Decision: projection v1 只允许是只读查询层，不允许引入 `projection_cache`、物化 snapshot 表或第二套可写派生真相。
  Rationale: 当前系统最需要的是边界清晰和恢复可靠，而不是预优化。先把 projection 实现成 SQL query 或只读 view，可以避免“派生状态反过来污染控制面真相”的经典问题。
  Date/Author: 2026-04-01 / Codex

- Decision: 所有 run 事件都必须经过单一 ingestion API 写入，事件序号由 StateStore 在事务内分配，生产者只提供幂等键而不直接指定序号。
  Rationale: worker helper、mock runtime、Codex runtime poller 都可能成为事件来源。如果不把事件写入入口收敛成一条路径，fresh scheduler 恢复时很容易制造重复 heartbeat、重复 artifact-ready 或重复 worker-exit 事件。
  Date/Author: 2026-04-01 / Codex

- Decision: 在 Milestone 1 就把 `resources/meta_flow/sql/001_init.sql` 建成接近 Phase 1 目标形态的 bootstrap schema，而不是只放 migration 占位符。
  Rationale: `init` 生成的 SQLite 文件要能直接承接后续 StateStore、ProjectionReader 和 scheduler 实现。提前把主表、只读 view 和关键 partial unique index 固定下来，可以减少后续迁移抖动，并让数据库不变量从第一天就可见。
  Date/Author: 2026-04-01 / Codex

- Decision: SQLite 控制列中的 keyword-like 文本统一持久化为带前导 `:` 的 `str` 结果，并通过后续 migration 修复已创建的 index/view 条件。
  Rationale: 主数据模型文档要求 enum-like 值使用 namespaced keywords。若数据库过滤条件使用去掉前导 `:` 的文本，而应用层用普通 keyword 序列化，partial unique index 和 view 会静默失效。
  Date/Author: 2026-04-01 / Codex

- Decision: 对 SQLite 的 `foreign_keys`、`busy_timeout` 和 `journal_mode` 不依赖一次性初始化，而是在每次应用连接建立时统一设置。
  Rationale: 其中至少 `foreign_keys` 与 `busy_timeout` 是连接级设置。若不把 pragma 应用逻辑收敛到统一连接入口，后续 StateStore 和 ProjectionReader 很容易在未受约束的连接上运行。
  Date/Author: 2026-04-01 / Codex

- Decision: Phase 1 / Milestone 2 直接复用 bootstrap schema 与只读 view，实现基于 `*_edn` canonical map 和结构化控制列的 SQLite StateStore，而不新增 projection cache 或额外控制面表。
  Rationale: 当前里程碑要证明的是单机 SQLite 控制面约束和接口边界，而不是 projection 物化策略。沿用现有 schema 可以最小化迁移面，并把测试注意力集中在幂等和唯一约束上。
  Date/Author: 2026-04-01 / Codex

- Decision: Phase 1 的 demo task 保持 `:task-type/cve-investigation` 的 task type pinning，但在任务实例层把 runtime profile 显式覆盖到 `:runtime-profile/mock-worker`。
  Rationale: 这样能同时满足两条约束：主 plan 继续保留“CVE task type 默认面向 Codex runtime”的真实集成方向；Phase 1 又能在完全本地、无外部 provider 依赖的环境里验证完整控制面闭环。
  Date/Author: 2026-04-01 / Codex

- Decision: Phase 2 / Milestone 2 将 heartbeat timeout 的生效预算定义在 resource policy 中，并在 create-run!/claim 时把值 pin 到 `run` 实体；ProjectionReader 只读地结合 pinned run data 与 `run_events` 计算 timeout 候选，而不新增 projection cache。
  Rationale: 这样既保留了 policy 受 definitions 管理和版本 pinning 的边界，也避免把 definitions lookup 或额外写路径混进 ProjectionReader。
  Date/Author: 2026-04-02 / Codex

## Outcomes & Retrospective

目前已经完成主 plan 在 Phase 1 的三个实现落点、Phase 2 的三个里程碑，以及 Phase 3 的第一个里程碑：仓库从纯文档状态变成了一个可运行的 Clojure 项目骨架，具备了 SQLite 控制面读写层，跑通了最小 mock happy path，并已补齐显式 requeue、max-attempts、needs-review、heartbeat-timeout recovery 与 Codex runtime 的基础装配边界。使用者现在不仅可以执行 `clojure -M -m meta-flow.main init` 创建或升级 `var/meta-flow.sqlite3` 与运行目录、执行 `clojure -M -m meta-flow.main defs validate` 验证 definitions schema、交叉引用和版本 pinning，还可以执行 `clojure -M -m meta-flow.main runtime init-codex-home` 安装项目级 `CODEX_HOME` 模板、执行 `clojure -M -m meta-flow.main scheduler once` 让 codex profile 任务创建真实 snapshot / durable handle，或通过 `demo happy-path`、`demo retry-path` 与 inspect 命令观察 task/run 在成功、验证拒绝和 heartbeat 超时场景下的控制面收敛。
目前已经完成主 plan 在 Phase 1 的三个实现落点、Phase 2 的三个里程碑，以及 Phase 3 的前两个里程碑：仓库从纯文档状态变成了一个可运行的 Clojure 项目骨架，具备了 SQLite 控制面读写层，跑通了最小 mock happy path，并已补齐显式 requeue、max-attempts、needs-review、heartbeat-timeout recovery，以及 Codex runtime 的外部 worker / helper / poller 协作边界。使用者现在不仅可以执行 `clojure -M -m meta-flow.main init` 创建或升级 `var/meta-flow.sqlite3` 与运行目录、执行 `clojure -M -m meta-flow.main defs validate` 验证 definitions schema、交叉引用和版本 pinning，还可以执行 `clojure -M -m meta-flow.main runtime init-codex-home` 安装项目级 `CODEX_HOME` 模板、执行 `clojure -M -m meta-flow.main scheduler once` 让 codex profile 任务创建真实 snapshot / durable handle 并由外部 managed worker 回写事件，或通过 `demo happy-path`、`demo retry-path` 与 inspect 命令观察 task/run 在成功、验证拒绝和 heartbeat 超时场景下的控制面收敛。

当前验证也已经从“存储层不变量成立”推进到“最小控制面闭环成立”：`clojure -M:test` 与 `clj-kondo --lint src test script` 继续通过，新增的 `scheduler_happy_path_test` 进一步证明 structured definition pinning 已落入 `tasks`、`runs`、`artifacts`、`assessments` 和 `dispositions` 表，并且重复执行 `scheduler once` 不会破坏 happy-path 收敛。接下来应把工作转向主 plan 剩余两段：失败/重试/接管矩阵和真实 Codex runtime adapter。

## Context and Orientation

当前仓库只有设计文档，没有应用代码，所以实现者必须从零搭出项目骨架。本计划要实现的程序名为 `meta-flow`，它是一个工作流宿主程序，不是单纯的库。这个宿主程序负责把任务写入状态存储、按状态机推进任务、启动 worker、记录事件、验证产物、写出调度决策，并通过本地 CLI 暴露这些操作。

这个程序的 v1 部署边界也必须说清楚：它是一个单机程序。SQLite 数据库文件、artifact 目录、run workdir 和项目级 `CODEX_HOME` 都假定在同一台机器上。不要把这份计划解释成多主机共享调度器设计，不要把 SQLite 文件放在网络文件系统上，也不要让多个独立宿主节点同时调度同一份数据库。

这里有几个必须先定义清楚的术语。

`task` 是长期存在的业务工作单元。它表示“要完成什么目标”，例如“为某个 CVE 产出一个证据包”。`task` 自身不等于某次执行尝试，它也不拥有 heartbeat、lease 或 worker 进度。

`run` 是某个 task 的一次执行尝试。只要重试一次，就会有一个新的 `run`。`run` 是控制面实体，负责承载 attempt 编号、当前执行状态、关联 lease、worker 身份、进度、heartbeat 和执行结果摘要。

`lease` 是调度器授予某个 run 的临时控制权。它的作用是避免多个 fresh 调度器同时把同一个 run 当成自己负责的执行对象。

`event` 是 append-only 事件流。worker 不应该覆盖一条“最新消息”，而应该持续追加 heartbeat、progress、worker-exit、artifact-ready、cooldown-detected 之类的事件。

`artifact` 是 worker 产出的文件系统结果，它是验证目标，不是调度真相。调度真相在 SQLite 里。artifact 至少要有一个 manifest、一个日志文件、一个人工可读的 notes 文件，以及可供验证器消费的证据或声明摘要。

`assessment` 是验证器给出的事实判断，比如“产物满足 contract”或“缺少必要证据”。`disposition` 是调度器在拿到 assessment 之后做出的控制决定，比如“complete”“retry”“escalate”。二者必须分开存储。

`projection` 是调度器可重建的视图，例如 runnable task 列表、active run 数、validation backlog。projection 不是系统真相，不能当作独立可写事实源。

这个程序应当明确分成几个区域。`src/meta_flow/` 下放 Clojure 应用代码。`resources/meta_flow/defs/` 下放版本化定义数据，包括 task type、task FSM、run FSM、artifact contract、validator、runtime profile、resource policy。`resources/meta_flow/sql/` 下放 SQLite schema 和只读 view。`resources/meta_flow/prompts/` 下放 Codex worker 与 mgr 使用的 prompt 模板。`script/` 下放 babashka helper。`test/meta_flow/` 下放单元和集成测试。`var/` 是运行时目录，里面放 SQLite 数据库、artifact、run workdir 和项目级 `CODEX_HOME`。

为了让后续实现简单且可验证，这个程序在逻辑上分为三层。第一层是 definition layer，也就是版本化、随代码提交的静态定义。第二层是 runtime state，也就是 SQLite 里的权威状态。第三层是 projection，也就是从 runtime state 推导出来的调度视图。不要把这三层混写在一个 namespace 里，更不要把 projection 写成另一套真相。

## Milestones

### Milestone 1: 建立宿主程序骨架并跑通 mock happy path

这个里程碑结束时，仓库将从纯文档状态变成一个可运行的 Clojure 应用。使用者可以执行初始化命令创建 SQLite、加载默认定义、生成运行目录；然后写入一个演示 task；接着运行一次调度；最后看到这个 task 通过 mock runtime 产生 artifact，并在验证后进入 `completed`。这一步的目标不是接入真实 Codex，而是证明控制面模型成立。

要做到这一点，需要实现 `deps.edn`、项目入口 CLI、SQLite schema、definition loader、Malli schema、FSM 校验器、任务入队逻辑、mock runtime adapter、artifact contract validator 和 `inspect` 命令。验收标准是：本地命令能在全新目录中跑通 happy path，并在 `var/artifacts/<task-id>/<run-id>/` 下生成 manifest、notes 和 run.log。

### Milestone 2: 补齐失败、重试、接管和资源策略

这个里程碑结束时，系统不只会成功，还会正确失败。使用者可以制造一个 contract 不完整的 artifact，让验证器写出 rejected assessment，调度器再据此写 disposition 并把 task 转入 `retryable_failed`，随后通过显式 requeue 动作回到 `queued`，再在后续调度中创建新的 run。系统还必须能识别 lease 超时、worker 无 heartbeat、provider cooldown 和并发上限，使调度器在 fresh 模式下依然能恢复收敛。

要做到这一点，需要补齐 run/lease 超时策略、resource policy 读取、projection 计算、retry 规则、takeover 规则、cooldown 事件和对应测试。验收标准是：系统能够在不改数据库的前提下多次执行 `scheduler once`，并最终把失败任务重试或升级到 `needs_review`。

### Milestone 3: 接入 Codex runtime adapter，并保持控制面不变

这个里程碑结束时，mock runtime 和 Codex runtime 都是 `RuntimeAdapter` 协议的具体实现。使用者可以为某个 task type 指定 `:runtime-profile/codex-worker`，系统会在项目级 `CODEX_HOME` 和受控 prompt 下启动 `codex exec` worker，由 worker 通过 babashka helper 回写 heartbeat、progress、artifact-ready 等事件。重要的是，SQLite 模式、状态机和调度主循环都不因此改变。

这一步要实现项目级 `CODEX_HOME` 模板、prompt 模板、worker snapshot 文件、Codex 启动包装、事件回写 helper、real artifact contract 和 smoke test。验收标准是：在具备 `codex` 命令和有效 provider 配置的机器上，一条真实 task 可以从 `queued` 跑到 `awaiting_validation` 或 `completed`，且中间状态完全由 SQLite 描述。

## Plan of Work

第一步先创建项目骨架，但不要用脚手架覆盖仓库。直接在仓库根目录新增 `deps.edn`、`build.clj`、`bb.edn`、`src/`、`resources/`、`test/`、`script/`。这样做可控、幂等，也不会把已有 `docs/` 结构打乱。`deps.edn` 只放当前程序需要的最小依赖和本地 alias，不把全局工具配置复制进来。

第二步实现 definition layer。新增 `resources/meta_flow/defs/workflow.edn`、`task-types.edn`、`task-fsms.edn`、`run-fsms.edn`、`artifact-contracts.edn`、`validators.edn`、`runtime-profiles.edn`、`resource-policies.edn`。这些文件必须包含一个通用默认定义和一个具体的 `:task-type/cve-investigation`。同时在 `src/meta_flow/defs/loader.clj` 中实现加载与版本查找逻辑，在 `src/meta_flow/schema.clj` 中用 Malli 定义任务、run、lease、event、artifact、assessment、disposition 等 map schema。

第三步实现 SQLite 持久化层。新增 `resources/meta_flow/sql/001_init.sql` 创建权威状态表、只读 view 和必要索引，新增 `src/meta_flow/db.clj`、`src/meta_flow/store/sqlite.clj` 和 `src/meta_flow/projection.clj`。持久化规则采用混合模式：每张主表都保留调度必需的结构化列，同时保存一份完整的 canonical map 字段，以便审计和后续演进。不要把 SQLite 当成一个只存 blob 的黑盒。这里还要把数据库级不变量写死：初始化时开启 `journal_mode=WAL` 和合理的 `busy_timeout`；所有状态推进必须包在显式事务里；`runs` 和 `leases` 需要 partial unique index 保证“一 task 最多一个非终态 run”“一 run 最多一个 active lease”；所有迁移都必须采用 compare-and-set 条件更新，而不是先查再盲写。

第四步实现状态机、definition repository、projection reader 与应用服务层。新增 `src/meta_flow/fsm.clj` 校验 task 和 run 的合法迁移，新增 `src/meta_flow/defs/protocol.clj` 或等价模块定义 DefinitionRepository，新增 `src/meta_flow/service/tasks.clj`、`src/meta_flow/service/runs.clj`、`src/meta_flow/service/validation.clj`、`src/meta_flow/service/collections.clj` 封装高层操作。这里要坚持两个原则：业务服务先读 definition layer 中的 FSM 定义，再对 runtime state 做迁移，而不是在 service 代码里散落 if/else 状态判断；projection v1 只能作为只读查询输入，不允许通过服务层写入任何 projection 表。

第五步实现 runtime adapter 协议、event ingestion contract 和 mock adapter。新增 `src/meta_flow/runtime/protocol.clj`、`src/meta_flow/runtime/mock.clj`、`src/meta_flow/event_ingest.clj`、`src/meta_flow/artifact.clj`。mock adapter 的行为必须完全可预测：收到 dispatch 后创建 workdir，写出一个满足或故意不满足 contract 的 artifact，通过统一的 ingestion API 追加 worker-start、heartbeat、worker-exit、artifact-ready 等事件，再返回。事件生产者不允许自己写 `event/seq`；它们只能提交 event intent 和 `event/idempotency-key`，由 SQLite store 在事务内分配序号并去重。这样可以先证明主程序的闭环没有依赖 Codex。

第六步实现调度器。新增 `src/meta_flow/scheduler.clj`。单轮调度必须按固定顺序执行：读取 collection state 和 projection；回收超时 lease；通过 polling 和 ingestion API 吸收新的外部观察；推进 exited 或 awaiting-validation run；运行 validator；写 assessment；写 disposition；根据 disposition 迁移 task/run；在资源允许时选择 queued task 创建新 run 并 dispatch。调度器要做控制，不做深度业务处理。`poll-run!` 如果从文件系统或进程状态推导事件，必须使用稳定的 `event/idempotency-key`，确保同一个观察不会重复写成多条事件。

第七步实现 CLI 和 inspect 能力。新增 `src/meta_flow/main.clj` 和 `src/meta_flow/cli.clj`。CLI 至少要支持 `init`、`defs validate`、`enqueue`、`scheduler once`、`inspect task`、`inspect run`、`inspect collection`、`demo happy-path`、`demo retry-path`。这些命令是后面所有验收步骤的入口，不要把关键流程藏在 REPL 手动调用里。

第八步实现 Codex runtime adapter。新增 `src/meta_flow/runtime/codex.clj`、`resources/meta_flow/prompts/worker.md`、`resources/meta_flow/prompts/mgr-notes.md`、`script/worker_api.bb` 和 `script/install_codex_home.bb`。这部分只负责“如何受控地启动 worker 并回写事件”，不允许改动核心 SQLite 结构和状态机。worker 所需上下文要在 dispatch 前写入 run workdir，例如 `task.edn`、`run.edn`、`definitions.edn`、`runtime-profile.edn` 和 artifact contract 摘要。

对于真实外部 runtime，这一步还必须把 durable execution handle 做成主协议约束：`dispatch-run!` 成功后必须持久化写出后续 fresh scheduler 可恢复读取的 execution handle，例如结构化 run 字段或 `var/runs/<run-id>/process.json` 加 SQLite 指针。`poll-run!` 与 `cancel-run!` 只能依赖这份持久化句柄，而不能依赖宿主进程内存中的临时对象。

第九步实现测试。新增 `test/meta_flow/schema_test.clj`、`fsm_test.clj`、`sqlite_store_test.clj`、`scheduler_happy_path_test.clj`、`scheduler_retry_test.clj`、`projection_test.clj`、`codex_adapter_smoke_test.clj`。Codex smoke test 必须通过环境变量显式启用，避免没有 `codex` 命令的机器在默认测试中失败。

## Concrete Steps

以下步骤假定工作目录是 `/home/mikewong/proj/main/meta-flow`。所有命令都从这个目录执行。

先创建项目骨架并写入依赖文件。不要运行会改写整个目录的脚手架命令，而是直接创建文件。

    mkdir -p src/meta_flow
    mkdir -p src/meta_flow/store
    mkdir -p src/meta_flow/service
    mkdir -p src/meta_flow/runtime
    mkdir -p resources/meta_flow/defs
    mkdir -p resources/meta_flow/sql
    mkdir -p resources/meta_flow/prompts
    mkdir -p test/meta_flow
    mkdir -p script

然后写入 `deps.edn`。依赖至少包括 Clojure、next.jdbc、sqlite-jdbc、Malli、cheshire、tools.cli、babashka/process。alias 至少包括 `:test`、`:dev` 和 `:run`。`build.clj` 只需要支持最小打包或 classpath 校验，不要一开始引入复杂发布流程。

接着实现初始化命令，使下面的命令可以成功创建数据库和运行目录。

    clojure -M -m meta-flow.main init

期望输出应该类似下面这样，具体时间戳可以不同，但含义必须一致。

    Initialized database at var/meta-flow.sqlite3
    Loaded workflow definitions from resources/meta_flow/defs
    Ensured runtime directories: var/artifacts, var/runs, var/codex-home
    SQLite pragmas applied: journal_mode=WAL, busy_timeout=5000

然后实现 definition 校验命令。

    clojure -M -m meta-flow.main defs validate

期望输出应该说明所有 schema、FSM 和 version ref 均有效。

    Definitions valid
    Task types: 2
    Task FSMs: 2
    Run FSMs: 2
    Runtime profiles: 2

之后实现演示入队命令。

    clojure -M -m meta-flow.main demo happy-path

这个命令应完成三件事：初始化一个演示 collection state；插入一个 `:task-type/cve-investigation` task；运行一轮 `scheduler once`。如果 mock runtime 是同步的，那么一轮调度后就应产生 artifact 并完成验证；如果 mock runtime 被设计成 “dispatch 一轮，validation 下一轮”，那么命令应自行调用两次 `scheduler once`，并在输出中明确说明。

期望输出应该类似下面这样。

    Enqueued task 4df0... for CVE-2024-12345
    Created run 8a20... attempt 1
    Mock worker produced artifact var/artifacts/4df0.../8a20.../
    Assessment accepted
    Task 4df0... -> :task.state/completed

再实现 inspect 命令，使使用者可以直接看到权威状态。

    clojure -M -m meta-flow.main inspect task --task-id 4df0...
    clojure -M -m meta-flow.main inspect run --run-id 8a20...
    clojure -M -m meta-flow.main inspect collection

任务 inspect 的期望结果必须至少包含 `:task/state :task.state/completed`、definition ref、source、policy 和时间戳。run inspect 的期望结果必须至少包含 `:run/state :run.state/finalized`、attempt、runtime ref、result/artifact-id 和最后 heartbeat。collection inspect 的期望结果必须至少包含 dispatch pause 状态和 resource policy。

之后再实现 retry 演示命令。

    clojure -M -m meta-flow.main demo retry-path

期望输出应该包含 rejected assessment 和 retry disposition。

    Enqueued task 7bc1...
    Created run c33d... attempt 1
    Assessment rejected: missing :artifact/notes
    Disposition :disposition/retry
    Task 7bc1... -> :task.state/retryable-failed

最后，在接入 Codex runtime 后，再增加一个显式 smoke test 命令。

    META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main demo codex-smoke

如果环境中缺少 `codex` 命令、provider 凭据或项目级 `CODEX_HOME` 模板，这个命令应明确失败并解释原因，而不是挂起。

## Validation and Acceptance

本程序的最小验收是 mock happy path。执行 `clojure -M -m meta-flow.main init` 后，再执行 `clojure -M -m meta-flow.main demo happy-path`，应当看到一个 task 成功完成。随后执行 `clojure -M -m meta-flow.main inspect task --task-id <id>`，应能看到 `:task.state/completed`。同时文件系统中应出现 `var/artifacts/<task-id>/<run-id>/manifest.json`、`notes.md`、`run.log`。如果其中任一文件缺失，验证器就不应给出 accepted assessment。

第二层验收是 retry path。执行 `clojure -M -m meta-flow.main demo retry-path` 后，inspect 结果应显示该 task 进入 `:task.state/retryable-failed`，且数据库中至少有一条 rejected assessment 和一条 retry disposition。再次执行 `clojure -M -m meta-flow.main scheduler once`，如果 `policy/max-attempts` 允许，系统应先把 task 显式 requeue 到 `:task.state/queued`，并只允许从 `queued` 再次创建新的 run attempt；不允许 `retryable_failed -> leased` 的直连重试。

第三层验收是 fresh 调度器恢复能力。完成一次 dispatch 之后，直接再次运行 `clojure -M -m meta-flow.main scheduler once`，系统不能因为进程内状态丢失而失去上下文。它必须只依赖 SQLite 中的 task/run/lease/event/artifact/assessment/disposition/collection state 收敛到正确结果。

第四层验收是数据库级不变量、definition pinning 和事件幂等性。人为重复运行 `scheduler once`、重复触发同一个 poll 周期、或让 helper 发送同一个 `event/idempotency-key` 两次，都不能制造第二条等价事件，也不能让同一个 task 同时出现两个非终态 run，或让同一个 run 同时拥有两个 active lease。相关验证必须通过测试和直接 inspect 数据库结果共同证明。还必须证明 task type id/version、runtime profile id/version、run FSM id/version、artifact contract id/version、validator id/version 已结构化持久化，而不是只藏在 blob 字段里。

第五层验收是 Codex runtime smoke test。只有当环境显式启用时才运行。运行成功的标志不是“Codex 输出了一堆文本”，而是 SQLite 中出现合法且未重复的 heartbeat 和 artifact-ready 事件，artifact contract 被验证器接受，最终 task 状态收敛为 `completed` 或根据验证结果进入失败路径。这里还必须验证三件事：外部执行句柄已被持久化，fresh scheduler 可只依赖这份句柄恢复 `poll-run!` 与 `cancel-run!`；worker 使用的是项目级 `var/codex-home/` 而不是 `~/.codex`；helper 与 poller 对 `worker-exit` / `artifact-ready` 的职责归属不会制造重复事件。

测试命令至少包括以下几条。

    clojure -M:test
    cljt.cljfmt check
    clj-kondo --lint src test script

如果项目后续引入了 Kaocha，也要把 `clojure -M:kaocha` 或项目本地测试 alias 写进本节并给出通过标准。

## Idempotence and Recovery

`init` 命令必须是幂等的。重复执行时，只允许做“目录存在则跳过、表已存在则跳过、默认 collection state 不存在才插入”这类安全操作，不允许无提示覆盖已有数据库和 artifact。

任务入队必须依赖稳定的 `:task/work-key` 做去重或显式重入控制。默认策略是：如果同一个 work key 且同一 `input-revision` 已存在非终态 task，则拒绝重复入队并返回现有 task id；如果需要并行或 replay，必须通过显式 flag 打开。

调度器单轮执行必须可重复运行。若某一轮在 dispatch 之后崩溃，下一轮必须根据 lease 是否过期、run 是否有新 heartbeat、artifact 是否存在来恢复，而不是重新猜测上轮做了什么。

事件写入也必须可重复运行。统一的 `ingest-run-event!` API 必须以 `(run-id, event-type, event-idempotency-key)` 或等价唯一键去重；同一个物理事实只能被吸收一次。worker helper、mock adapter、Codex poller 不允许直接向 `events` 表裸写。

artifact 目录必须按 run 划分，推荐路径为 `var/artifacts/<task-id>/<run-id>/`。这样即使一个 task 被重试多次，也不会覆盖旧 run 的证据。清理策略必须做成单独命令，不要在调度成功路径中自动删除旧 artifact。

Codex runtime 相关目录必须与用户日常的 `~/.codex` 隔离。推荐运行目录为 `var/codex-home/`，由安装脚本或初始化命令创建。删除这个目录不应影响 SQLite 中的权威状态，只会让后续 Codex worker 无法继续启动，恢复方法是重新执行 runtime 初始化命令。

## Artifacts and Notes

运行完成后，仓库结构至少应接近下面这样。

    deps.edn
    build.clj
    bb.edn
    src/meta_flow/main.clj
    src/meta_flow/cli.clj
    src/meta_flow/schema.clj
    src/meta_flow/defs/loader.clj
    src/meta_flow/fsm.clj
    src/meta_flow/db.clj
    src/meta_flow/projection.clj
    src/meta_flow/scheduler.clj
    src/meta_flow/artifact.clj
    src/meta_flow/store/sqlite.clj
    src/meta_flow/service/tasks.clj
    src/meta_flow/service/runs.clj
    src/meta_flow/service/validation.clj
    src/meta_flow/service/collections.clj
    src/meta_flow/runtime/protocol.clj
    src/meta_flow/runtime/mock.clj
    src/meta_flow/runtime/codex.clj
    resources/meta_flow/defs/workflow.edn
    resources/meta_flow/defs/task-types.edn
    resources/meta_flow/defs/task-fsms.edn
    resources/meta_flow/defs/run-fsms.edn
    resources/meta_flow/defs/artifact-contracts.edn
    resources/meta_flow/defs/validators.edn
    resources/meta_flow/defs/runtime-profiles.edn
    resources/meta_flow/defs/resource-policies.edn
    resources/meta_flow/sql/001_init.sql
    resources/meta_flow/prompts/worker.md
    script/worker_api.bb
    test/meta_flow/scheduler_happy_path_test.clj

成功的 mock artifact 目录至少应包含下面这些文件。

    var/artifacts/<task-id>/<run-id>/manifest.json
    var/artifacts/<task-id>/<run-id>/notes.md
    var/artifacts/<task-id>/<run-id>/run.log
    var/artifacts/<task-id>/<run-id>/evidence/

SQLite 表至少应包含 `tasks`、`runs`、`leases`、`events`、`artifacts`、`evidence`、`claims`、`assessments`、`dispositions`、`collections`。如果实现者额外添加 `schema_migrations` 或 `scheduler_cursor`，必须说明它们是不是权威状态；除非有充分理由，否则不要让它们与已有权威表重复存储同一事实。

在 v1 中，不允许引入 `projection_cache`。projection 只能由 `projection.clj` 通过只读 SQL 查询或只读 SQLite view 生成。

## Interfaces and Dependencies

项目依赖必须保持克制，但下列库应被明确采用。

- `org.clojure/clojure` 作为语言运行时。
- `com.github.seancorfield/next.jdbc` 作为 SQLite 访问层。
- `org.xerial/sqlite-jdbc` 作为 JDBC 驱动。
- `metosin/malli` 作为 map schema 与边界校验库。
- `cheshire` 作为 JSON manifest 和 CLI 输出编码库。
- `org.clojure/tools.cli` 作为命令行解析器。
- `babashka/process` 作为启动 `codex exec` 和外部命令的进程包装。

不要在第一版引入大型生命周期框架。系统装配用普通 map 加显式构造函数即可，例如在 `src/meta_flow/system.clj` 中拼出 `{::db ... ::defs ... ::runtime-registry ...}`。这比一开始引入 Integrant 或 Component 更容易理解和调试。

在 `src/meta_flow/defs/protocol.clj` 中定义 definitions 读取接口，或用等价纯函数模块实现同样边界。

    (defprotocol DefinitionRepository
      (load-workflow-defs [repo])
      (find-task-type-def [repo task-type-id version])
      (find-run-fsm-def [repo run-fsm-id version])
      (find-task-fsm-def [repo task-fsm-id version])
      (find-artifact-contract [repo contract-id version])
      (find-validator-def [repo validator-id version])
      (find-runtime-profile [repo runtime-profile-id version])
      (find-resource-policy [repo resource-policy-id version]))

这个边界必须只从 `resources/meta_flow/defs/` 读取定义，不从 SQLite 读取。

在 `src/meta_flow/runtime/protocol.clj` 中定义以下协议。

    (defprotocol RuntimeAdapter
      (adapter-id [this])
      (prepare-run! [this ctx task run])
      (dispatch-run! [this ctx task run])
      (poll-run! [this ctx run now])
      (cancel-run! [this ctx run reason]))

`prepare-run!` 负责创建 run workdir 和 snapshot；`dispatch-run!` 负责真正启动 worker；`poll-run!` 负责在 fresh 调度器场景下读取外部进度或补充事件；`cancel-run!` 负责超时回收、takeover 或人工中止。mock 和 Codex adapter 都必须实现这一协议。

对于真实外部 runtime，这个协议还必须满足一条额外约束：`dispatch-run!` 成功后必须持久化写出 fresh scheduler 后续可恢复读取的 execution handle；`poll-run!` 与 `cancel-run!` 只能依赖这份持久化 execution handle，而不能依赖宿主进程内存中的临时对象。

在 `src/meta_flow/store/protocol.clj` 中定义权威状态存储接口，至少包含下面这些函数。

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

`ingest-run-event!` 必须在事务内完成三件事：按幂等键去重、为同一条 stream 分配单调递增 `event/seq`、把必要的 run 摘要字段同步推进到最新值。它的输入必须包含 `:event/run-id`、`:event/type`、`:event/payload`、`:event/caused-by` 和 `:event/idempotency-key`，但不能允许调用方传入 `:event/seq`。

`StateStore` 不允许暴露与 projection 重复语义的调度读取接口，例如 `list-runnable-tasks`。凡是 runnable candidate、awaiting validation candidate、expired lease candidate 之类的调度输入，都必须通过 `ProjectionReader` 读取。

在 `src/meta_flow/projection.clj` 中定义只读查询接口。

    (defprotocol ProjectionReader
      (load-scheduler-snapshot [reader now])
      (list-runnable-task-ids [reader now limit])
      (list-awaiting-validation-run-ids [reader now limit])
      (list-expired-lease-run-ids [reader now limit]))

v1 中这个 reader 只能由 SQL query 或 SQLite view 实现，不能有任何写路径。

在 `src/meta_flow/fsm.clj` 中定义纯函数接口，不要把数据库操作混进去。

    (defn valid-transition? [fsm current-state event-key])
    (defn apply-transition [entity fsm event-key now payload])

在 `src/meta_flow/service/tasks.clj` 中定义任务入队和幂等规则。

    (defn enqueue-task! [system task-request])

在 `src/meta_flow/scheduler.clj` 中定义单轮调度入口。

    (defn schedule-once! [system now])

这个函数必须返回一份结构化 summary map，而不是只打印日志。CLI 可以打印，但调度器本体必须返回可测试数据，例如：

    {:summary/leased 1
     :summary/dispatched 1
     :summary/validated 1
     :summary/completed 1
     :summary/retried 0}

在 `src/meta_flow/artifact.clj` 中定义 artifact contract 验证函数。

    (defn validate-artifact-contract [artifact-root contract])

返回值必须能直接转成 assessment finding，而不是仅返回布尔值。

SQLite schema 要遵循下面的约束。`tasks` 表至少需要 `task_id`、`work_key_hash`、`task_type`、`state`、`definition_version`、`source_kind`、`created_at`、`updated_at`、`task_edn`。`runs` 表至少需要 `run_id`、`task_id`、`attempt`、`state`、`runtime_profile_id`、`run_fsm_version`、`active_lease_id`、`worker_id`、`last_heartbeat_at`、`artifact_id`、`created_at`、`updated_at`、`run_edn`。其他表也必须保留关键过滤列和完整 map 字段。所有时间使用 UTC ISO-8601 字符串或 SQLite 可比较时间格式，不要混用本地时区。

SQLite 初始化必须额外执行以下规则。第一，启用 `PRAGMA journal_mode=WAL;` 和明确的 `PRAGMA busy_timeout=5000;`，并在代码里说明这套参数是为“单机多进程有限并发写入”服务。第二，所有调度状态推进都必须在显式事务内完成；创建 run 与 lease、吸收事件、验证并写 disposition、重试迁移都不能拆成多条无事务语义的独立写入。第三，必须建立数据库级唯一约束或 partial unique index 来守住关键不变量，至少包括：一个 `task_id` 同时最多一个非终态 `run`，一个 `run_id` 同时最多一个 `lease.state/active` lease，一个 `(run_id, event_idempotency_key)` 最多一条事件。第四，`transition-task!` 和 `transition-run!` 必须是 compare-and-set 更新，也就是 `where id=? and state=?` 成功后才算完成迁移。

artifact、assessment 和 run 也必须把 definition pinning 落到持久化字段里，而不是只留在 blob 内。`artifacts` 表至少需要 contract id/version；`assessments` 表至少需要 validator id/version；`runs` 表至少需要 runtime profile id/version 与 run FSM id/version；`tasks` 表至少需要 task type id/version。

Definition layer 中至少要内置两个 runtime profile。一个是 `:runtime-profile/mock-worker`，供测试和本地演示使用。另一个是 `:runtime-profile/codex-worker`，供真实受控执行使用。`task-type/cve-investigation` 默认应指向 Codex profile，但测试中可以通过参数覆盖到 mock profile。

Codex runtime profile 必须定义清楚这些字段：`CODEX_HOME` 根目录、允许的 MCP 集、是否启用 Web search、worker prompt 路径、helper 脚本路径、artifact contract id、worker 超时时间、heartbeat 周期、环境变量白名单。不要让 adapter 自己猜这些值。

## Revision Note

2026-04-01：创建本 ExecPlan，目的是把仓库中分散的抽象 workflow、Clojure 数据模型和 Codex 受控执行文档收敛成一份可以直接实施的整体程序设计，并明确 mock-first、state-externalized、fresh-scheduler 的实现路线。

2026-04-01：根据深度 review 收紧四项架构约束：把 SQLite 的事务和数据库级不变量写死；把 definitions 从 StateStore 拆到独立 DefinitionRepository；把 projection v1 固定为只读 query/view 层；把 event ingestion contract 固定为单入口、幂等、由 store 分配 stream sequence。

2026-04-01：为与三个 subplan 保持一致，最小同步修订主 plan：删除 `StateStore` 中与 `ProjectionReader` 重复的 runnable 读取接口；把 retry 语义固定为 `retryable_failed -> queued -> leased`；把真实 runtime 的 durable execution handle 纳入主协议约束；把 definition pinning、运行时隔离和 helper/poller 事件归属提升到总体验收标准。

2026-04-01：执行 Phase 1 / Milestone 1 后更新主 plan：项目骨架、definitions loader、bootstrap SQL、`init` / `defs validate` CLI 与最小单元测试已落地；同时记录当前环境缺少 `cljfmt` 可执行命令，后续格式校验需要 repo-local alias 或环境修复。

2026-04-01：在 review 后加固 Milestone 1：把 SQLite pragma 应用收敛到统一连接入口，新增 `002_align_keyword_literals.sql` 修复旧 schema 的 state 过滤字面量，并收紧 Codex runtime profile 的 definitions 校验与占位资源要求。

2026-04-01：执行 Phase 1 / Milestone 2 后更新主 plan：新增 SQLite StateStore、只读 ProjectionReader 与统一 event ingestion 入口，并补充 `sqlite_store_test` 作为控制面数据库不变量与 projection 边界的回归测试。

2026-04-01：执行 Phase 1 / Milestone 3 后更新主 plan：修复 `fsm.clj` 与 mock runtime 的 happy-path 漂移，补齐 `scheduler once`、`demo happy-path`、`inspect` CLI，并新增 `scheduler_happy_path_test` 把 artifact contract、状态收敛和结构化 definition pinning 固化为可重复验证的集成测试。

2026-04-02：执行 Phase 2 / Milestone 2 后更新主 plan：heartbeat timeout 已进入 resource policy pinning、run snapshot、ProjectionReader、scheduler recovery 与 CLI summary，并通过新增 projection/recovery/CLI 回归测试和 `bb check` 验证。
