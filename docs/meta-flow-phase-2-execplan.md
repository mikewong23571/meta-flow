# Phase 2：沿当前控制面补齐重试、超时与资源策略

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `.agent/PLANS.md`.

This document is a subordinate ExecPlan of `docs/meta-flow-program-execplan.md`. It assumes Phase 1 has already produced a working host skeleton, a passing mock happy path, and the current SQLite-backed control-plane baseline already present in this repository. If this document conflicts with the main plan, the main plan wins.

## Purpose / Big Picture

这一阶段的目标不是推翻当前实现，而是在当前实现已经建立的控制面语义上继续补齐失败恢复和调度策略。当前系统已经具备这些基础能力：validator 会为 artifact 写 assessment 和 disposition；rejected validation 会把 task/run 收敛到 `:task.state/retryable-failed` 和 `:run.state/retryable-failed`；lease 过期也会通过统一事件入口走到同一类可恢复失败状态；ProjectionReader 已经能提供 runnable、awaiting-validation、expired-lease 和 dispatch-paused 的只读视图。

Phase 2 的工作是在这条基线上继续前进：补上显式 requeue、max-attempts、`needs-review`、heartbeat timeout、cooldown、resource ceilings、priority ordering，以及与这些语义相匹配的测试与 CLI 可见性。除非文档显式记录为“设计升级”，否则本阶段不把现有 FSM 直接改写成另一套 takeover/abandon 模型。

## Scope Boundaries

本阶段范围内的工作包括：基于当前 rejected/expired-lease 基线补齐 retry policy、显式 requeue、基于现有 resource policy definition 的 `max-attempts`、`needs-review`、heartbeat timeout、collection-level cooldown、resource ceilings、ProjectionReader 优先级输入、以及对应的调度与集成测试。

本阶段范围外的工作包括：真实 `codex exec` 集成、项目级 `CODEX_HOME` 模板安装、prompt 设计、MCP 能力面、helper 外部进程启动细节、任何跨主机部署方案，以及未经明确 decision log 记录的 task/run FSM 重设计。

本阶段必须继承当前仓库已经生效的治理边界：SQLite 仍然是单机真相源；definitions 仍然是只读静态数据；projection v1 仍然只读；事件 ingestion 仍然必须走单一幂等 API；新增实现必须以当前目录结构和职责拆分为前提；`bb check` 是默认验收入口；文件长度、目录宽度和覆盖率治理已经生效，新增功能不能通过把逻辑重新塞回少数顶层大文件来完成。

## Progress

- [x] (2026-04-01 13:12Z) 从主 plan 提炼 Phase 2 的实现边界，并确认本阶段只处理宿主控制面的失败恢复与资源策略。
- [x] (2026-04-01 13:12Z) 为本阶段写出独立 subplan，明确 in-scope、out-of-scope、接口与验收标准。
- [x] (2026-04-02 00:00Z) 确认当前基线已经具备 rejection -> assessment/disposition -> `retryable-failed` 的收敛路径，并已有幂等测试覆盖。
- [x] (2026-04-02 00:00Z) 确认当前基线已经具备 expired lease recovery、dispatch pause snapshot 和 dispatch capacity 基础行为，并已有调度恢复测试覆盖。
- [x] (2026-04-02 08:34Z) 在当前 FSM 基线上补齐显式 requeue 与 max-attempts：task FSM 新增 `:task.state/needs-review`、`:task.event/requeued`、`:task.event/retry-exhausted`，resource policy 新增 `max-attempts`，scheduler 新增 retry stage 并通过 projection/CLI/tests 验证 `retryable-failed -> queued` 与 exhausted -> `needs-review`。
- [x] (2026-04-02 09:51Z) 完成 Milestone 2：resource policy 新增 `heartbeat-timeout-seconds` 并 bump definitions pinning；scheduler 新增 heartbeat-timeout projection/recovery/summary；相关 run/task FSM 增量支持 timeout 事件；`bb check` 通过。
- [x] (2026-04-02 16:53Z) 完成 Milestone 3：dispatch 现在会读取 collection-level cooldown、按 task resource policy 的 `queue-order` 做排序、按 task resource policy ceiling 计算 dispatch 容量，并继续保持 ProjectionReader 只读；新增 resource-policy active-run count query、cooldown snapshot 字段、scheduler summary 字段与相应测试覆盖。
- [x] (2026-04-02 16:53Z) 扩展 CLI / inspect / scheduler summary，使 retry、requeue、escalate、cooldown-skip / dispatch-block 对操作者可见。
- [x] (2026-04-02 16:53Z) 补齐失败恢复、资源策略和幂等性的测试矩阵；`bb test` 通过，`bb check` / `bb lint` / `bb coverage` 仍受外部依赖下载超时影响而未能在本轮环境内完整复跑。

## Surprises & Discoveries

- Observation: Phase 2 并不是从零开始，当前仓库已经提前落地了一部分失败恢复语义。
  Evidence: `src/meta_flow/scheduler/validation.clj` 已记录 assessment/disposition 并通过事件驱动推进状态；`test/meta_flow/scheduler/validation_test.clj` 与 `test/meta_flow/scheduler/recovery_test.clj` 已覆盖 rejection 幂等与 expired lease recovery。

- Observation: 当前最需要避免的不是“功能不够多”，而是让后续计划重新偏回单文件实现。
  Evidence: 仓库已经拆成 `control/`、`scheduler/`、`runtime/mock/`、`store/sqlite/run|lease|artifact/` 等层；同时 lint 已对文件长度与目录宽度实施治理。

- Observation: 若在同一轮调度里对“本轮刚刚失败”的 task 立即执行 requeue，失败事实会在 step 末尾被新的 lease 覆盖，操作者看不到显式 `retryable-failed -> queued` 控制动作。
  Evidence: 首轮实现把 retry stage 直接接在 validation/recovery 后面时，既有 rejection/expired-lease 测试会在下一次 inspect 中直接看到 `leased`，`demo retry-path` 也会继续滚到第二次 attempt。

- Observation: heartbeat timeout fixture 若复用短 lease TTL，会被现有 expired-lease recovery 抢先命中，导致测试看起来像“heartbeat timeout 没生效”。
  Evidence: 首轮 heartbeat timeout 测试把 lease 设到 2026-04-01T00:30:00Z，而调度运行时的真实 `now` 已晚于这个时间；ProjectionReader 因此先把 run 归入 expired-lease 集合。把 fixture 的 lease 到期时间推远后，heartbeat timeout 路径按预期独立命中。

- Observation: 一旦 dispatch 按 task policy 的 queue-order 排序，之前依赖“projection 返回顺序”的测试夹具会变成不稳定测试。
  Evidence: `dispatch_capacity_test` 原本通过 `projection/list-runnable-task-ids` 人工指定 blocked task 在前；启用 queue-order 之后，codex task 和 mock task 会按 work-key 重新排序，导致测试不再稳定地先命中失败路径。修复方式是让测试 work key 与 policy 排序显式对齐。

- Observation: 用 expired lease 来模拟“已占用 resource ceiling 的 active run”会被 recovery stage 提前清走，从而错误地放开 dispatch 容量。
  Evidence: 首轮 resource ceiling 测试复用了 `create-expired-leased-run!`，同一轮 scheduler 先把该 run 恢复到 `retryable-failed`，导致 collection capacity 和 policy-specific active count 都回落；改成 future lease 的 active leased run 后，resource ceiling 行为稳定命中。

## Decision Log

- Decision: Phase 2 以当前已存在的 direct-to-`retryable-failed` 基线为起点增量演进，而不是默认把 run/task FSM 重写成 `taken-over` / `abandoned` 模型。
  Rationale: 当前代码、definitions 和测试已经围绕 `retryable-failed` 建立一致语义。若要改成另一套状态机，应被视为一次明确的 schema/FSM 升级，而不是隐含在“补齐 Phase 2”里。
  Date/Author: 2026-04-02 / Codex

- Decision: 新增实现必须优先落在当前职责拆分的目录结构里，例如 `meta-flow.control.*`、`meta-flow.scheduler.*`、`meta-flow.store.sqlite.*`，避免回退到把 Phase 2 逻辑集中到少数顶层大文件。
  Rationale: 当前仓库已经通过 lint 对文件长度、目录宽度和层次职责做治理；计划必须服从这些约束。
  Date/Author: 2026-04-02 / Codex

- Decision: 本阶段默认以 `bb check` 作为验收门槛，并把 `bb coverage` 的通过作为功能拆分是否合理的信号之一。
  Rationale: 当前 repo 的真实开发入口已经是 babashka task，而不是散落的单独命令。
  Date/Author: 2026-04-02 / Codex

- Decision: retry stage 只处理 scheduler step 开始时 snapshot 中已经存在的 `retryable-failed` tasks，而不处理本轮刚生成的失败。
  Rationale: 这样可以保持 failure convergence 与 retry control 的语义分离，让 `retryable-failed` 先稳定落库并对 inspect 可见；下一轮再显式迁回 `queued` 或升级到 `needs-review`。
  Date/Author: 2026-04-02 / Codex

- Decision: heartbeat timeout 预算继续放在 `resource-policy` definitions 里，但在 claim run 时把生效值 pin 到 `run` 实体上，再由 ProjectionReader 只读地结合 `run_events` 推导 timeout 候选。
  Rationale: 这样既满足“policy 由 definitions 定义并随 task pinning 演进”，又避免为了 heartbeat timeout 把 definitions lookup 拉进 ProjectionReader 或新增 projection cache / 控制面列。
  Date/Author: 2026-04-02 / Codex

- Decision: Phase 2 / Milestone 3 的 resource ceiling 继续复用 task 已经 pin 下来的 `resource-policy-ref` 作为资源分组键，而不是在 collection state 外再引入第二套 resource identity。
  Rationale: 当前表结构已经把 `resource_policy_id/version` 结构化存进 `tasks`，ProjectionReader 可以只读地按这个键统计 active run 数；这样能在不新增写模型和不改 SQLite 真相结构的前提下落地 ceilings 与 queue-order。
  Date/Author: 2026-04-02 / Codex

- Decision: collection-level cooldown 先落在 `:collection/dispatch :dispatch/cooldown-until`，并只阻断新 dispatch，不阻断 retry / validation / recovery。
  Rationale: 这与本阶段文档里“cooldown 只是 dispatch 抑制”的边界一致，也能让 inspect 与 scheduler summary 直接暴露单一、可审计的 cooldown 事实。
  Date/Author: 2026-04-02 / Codex

## Outcomes & Retrospective

Phase 2 现在已经完成。最终结果是：一，现有 direct-to-`retryable-failed` 基线已扩展成“下一轮显式 requeue 或升级到 `needs-review`”的可解释失败路径；二，lease expiry 之外，scheduler 还能把 heartbeat timeout 作为同层级恢复输入，通过只读 ProjectionReader 候选集、统一事件入口和相同的 release-lease 收敛路径推进到 `retryable-failed`；三，dispatch 现在会读取 collection-level cooldown、按 task resource policy 的 `queue-order` 对 runnable tasks 排序，并按同一个 pinned `resource-policy-ref` 统计 active run 以实施 resource ceilings；四，CLI summary 与 inspect 现在会暴露 dispatch cooldown / blocked state / capacity skipped 信息；五，新增的 cooldown、priority ordering、resource ceiling 与 projection 测试已经通过，`bb test` 通过，但 `bb check` 中的 `fmt:check`、`lint` 和 `coverage` 在本轮环境里仍被 Clojars 依赖下载超时阻断。

## Context and Orientation

当前仓库的失败路径语义已经有清晰基线。

`validator rejection` 当前语义是：run 在 `:run.state/awaiting-validation` 时写 assessment 和 disposition，随后通过 `run/task assessment-rejected` 事件把 task/run 推进到 `:task.state/retryable-failed` 和 `:run.state/retryable-failed`。这条路径已经被现有测试锁定。

`lease expiry recovery` 当前语义是：调度器通过 ProjectionReader 找到 expired lease run ids，发出统一的 scheduler event intent，释放 lease，并把 task/run 收敛到 `retryable-failed`。这同样已经被现有恢复测试锁定。

`dispatch pause` 当前语义是：collection state 已可持久化 `:dispatch/paused?`，ProjectionReader 已能把它暴露到 snapshot，调度器在 dispatch 阶段会尊重它，但 validation 和 recovery 仍继续运行。

因此，Phase 2 不是从“成功系统”走向“第一次失败”，而是从“已有基础失败收敛能力”走向“可运营、可重试、可限流、可解释的失败控制面”。

这里需要明确本阶段准备新增的语义。

`requeue` 指一个显式控制动作：task 已经处于 `:task.state/retryable-failed`，调度器或 CLI 根据 policy 判断允许再次尝试后，把 task 明确迁回 `:task.state/queued`。它是对当前基线的增量补充，而不是对 rejected validation 的替代。

`max-attempts` 指当前 `resource-policy` definition 中关于重试预算的上限。Phase 2 默认把它放在现有 policy pinning 结构里，由 scheduler 用来在 `requeue` 与 `needs-review` 之间做控制决策，而不是塞回 validator；如果将来需要独立的 task-level retry policy，应被记录为单独的 schema 设计升级。

`heartbeat timeout` 指对“lease 仍 active，但 worker 长时间没有可观察进展”的检测。它应成为调度恢复输入，并尽量复用现有 recovery 结构，而不是平行造一套难以验证的隐藏状态。

`cooldown` 指 collection 级别的短期 dispatch 抑制。它只影响新 dispatch，不阻断 validation、inspect 或既有失败收敛。

## Milestones

### Milestone 1: 在当前 rejection 基线上补齐 retry policy

这一小里程碑结束时，系统在保留当前 rejection -> `retryable-failed` 路径的前提下，能够显式判断某个 task 是应被 requeue 还是升级到 `needs-review`。

### Milestone 2: 扩展超时恢复到 heartbeat timeout

这一小里程碑结束时，系统除了 lease expiry 之外，还能把 heartbeat timeout 作为恢复输入纳入调度，并保持重复执行 `scheduler once` 时的幂等性与单调收敛。

### Milestone 3: 实现资源策略与冷却

这一小里程碑结束时，collection state 和 ProjectionReader 能共同支持 dispatch pause、cooldown、并发上限和 priority ordering；调度器会在 cooldown 期间停止新 dispatch，但继续 validation 和 failure convergence。

## Plan of Work

先从 definitions 入手，但以“增量扩展当前 schema”为原则。若需要新增 `policy/max-attempts`、cooldown 配置或 priority ordering，应优先扩展现有 `resource-policies.edn`、相关 task type ref 和 validation，而不是一边沿用 `resource-policy-ref`、一边再隐含引入第二套 retry-policy 机制。任何 definition 结构变化都必须 bump version，并与当前 runtime state pinning 机制对齐。

然后扩展 validation 与 disposition 的后半段控制逻辑。validator 继续只产出 assessment facts；scheduler 基于 assessment、attempt、policy 和 collection state 决定后续动作。这里要延续当前语义分层，不把“是否还能重试”塞回 validator。

接着扩展调度恢复输入。现有 `expired lease` 查询与恢复流程已经存在，heartbeat timeout 应优先按相同控制面模式接入：通过 ProjectionReader 暴露候选集合，通过统一 ingestion / transition 路径推进状态，并保证重复调度幂等。

之后扩展 collection-level 资源策略。当前 collection state 已有 `dispatch paused` 基线，Phase 2 应在此基础上增加 cooldown、resource ceilings、priority ordering 和更丰富的 scheduler snapshot，而不是引入 projection cache 或把 collection 逻辑散落到 CLI / scheduler 顶层。

最后补齐测试与 CLI 可见性。新增行为应优先落在现有测试分层里，例如 `test/meta_flow/scheduler/`、`test/meta_flow/store/` 和 `test/meta_flow/cli_test.clj`，同时避免把单个测试文件推到治理阈值之上。

## Concrete Steps

所有命令都在 `/Users/mike/projs/main/meta-flow` 运行。

先保留并复用当前失败演示入口：

    clojure -M -m meta-flow.main demo retry-path

当前基线期望输出是 rejected assessment 与 `retryable-failed` 收敛；Phase 2 完成后，这个演示或配套新命令应进一步说明当前 task 还能否被 requeue。

然后实现并验证显式重试控制，例如：

    clojure -M -m meta-flow.main scheduler once
    clojure -M -m meta-flow.main inspect task --task-id <task-id>

如果 `policy/max-attempts` 允许，期望在某一轮后看到 task 从 `:task.state/retryable-failed` 明确回到 `:task.state/queued`，并在后续调度中创建新的 run attempt；如果上限已到，期望最终看到 `:task.state/needs-review`。

再实现 cooldown / resource policy fixture，并验证：

    clojure -M -m meta-flow.main inspect collection
    clojure -M -m meta-flow.main scheduler once

期望输出中应说明 dispatch 被暂停或跳过，但 validation 与 failure convergence 仍继续进行。

## Validation and Acceptance

本阶段验收标准至少包括以下行为：

一，contract 不完整的 artifact 继续由 validator 拒绝，并通过已有 assessment/disposition 基线收敛，而不是由 runtime 自行决定失败。

二，scheduler 能在当前 `retryable-failed` 基线上继续判断 `requeue` 或 `needs-review`；重复执行 `scheduler once` 不会重复制造等价 disposition 或重复 requeue。

三，retry path 必须是显式可见的控制动作；task 从 `retryable-failed` 回到 `queued` 必须能通过 inspect / state / tests 被观察到，而不是隐藏在内存里的即时跳转。

四，lease expiry 与 heartbeat timeout 都必须通过统一控制面路径收敛；系统不会让同一 task 同时出现两个违反当前 SQLite 不变量的非终态 run。

五，cooldown 生效时，新的 dispatch 会暂停，但 validation、inspection 和 failure convergence 继续运行。

六，ProjectionReader 仍然只读，不引入任何 projection cache 表。

七，任何新增 policy 字段、state、query 或 summary，都必须伴随 definitions pinning、SQLite 约束 / 查询、以及数据库与调度测试的同步更新。

八，默认验收命令必须保持通过：

    bb fmt:check
    bb lint
    bb test
    bb coverage
    bb check

## Idempotence and Recovery

本阶段所有新增路径都必须是幂等的。重复 rejection 观察不应生成重复 requeue 或重复 upgrade；重复 timeout 检查不应反复制造相同控制结果；重复 cooldown 检查不应让调度 summary 和 persisted control state 漂移。

如果某个 run 已经是 `:run.state/retryable-failed` 或其他 terminal state，任何迟到 heartbeat 或 poll 观察都不能把它重新变成 active run。计划内允许保留迟到观察为事件事实，但不允许它覆盖当前控制面真相。

本阶段还必须保持语义分离：assessment 记录事实；disposition 记录控制判断；scheduler 执行重排队或升级；ProjectionReader 只提供调度输入。

## Artifacts and Notes

本阶段最重要的新增成果预计会集中在当前职责层次下的这些文件或同层新拆分文件：

    src/meta_flow/control/projection.clj
    src/meta_flow/scheduler/step.clj
    src/meta_flow/scheduler/runtime.clj
    src/meta_flow/scheduler/validation.clj
    src/meta_flow/scheduler/dev.clj
    src/meta_flow/store/sqlite/tasks.clj
    src/meta_flow/store/sqlite/runs.clj
    resources/meta_flow/defs/task-fsms.edn
    resources/meta_flow/defs/run-fsms.edn
    resources/meta_flow/defs/resource-policies.edn
    test/meta_flow/scheduler/validation_test.clj
    test/meta_flow/scheduler/recovery_test.clj
    test/meta_flow/scheduler/dispatch_capacity_test.clj

如果 Phase 2 增长超过当前文件治理阈值，应优先新增更细粒度的 `scheduler/` 或 `control/` 子模块，而不是继续做单文件累积。

## Interfaces and Dependencies

本阶段不新增新的基础设施依赖。重点是扩展既有接口语义，并维持当前目录与协议边界。

`ProjectionReader` 当前已经支持：

    (load-scheduler-snapshot [reader now])
    (list-runnable-task-ids [reader now limit])
    (list-awaiting-validation-run-ids [reader now limit])
    (list-expired-lease-run-ids [reader now limit])
    (list-active-run-ids [reader now limit])
    (count-active-runs [reader now])

如有必要，可以增加只读查询函数，例如：

    (list-heartbeat-timeout-run-ids [reader now limit])
    (list-requeue-candidate-task-ids [reader now limit])

但不允许增加任何写 projection 的接口。

调度器公开入口当前是 `meta-flow.scheduler/run-scheduler-step`。Phase 2 应扩展其返回 summary，使其能显示 `:summary/requeued`、`:summary/escalated`、`:summary/cooldown-skipped`、`:summary/heartbeat-timeouts` 等字段，同时保持与当前 `scheduler/step.clj` 的职责拆分一致。

## Revision Note

2026-04-01：创建 Phase 2 subplan。

2026-04-02：将文档调整为“沿当前实现增量扩展”的版本，明确以当前 FSM、目录结构、`bb check` 验收和 lint / coverage 治理为基础，而不是假定一套尚未存在的新控制模型。

2026-04-02：完成 Milestone 2：将 heartbeat timeout 预算纳入 `resource-policies.edn` 与 task/run pinning，新增 heartbeat-timeout projection/recovery/CLI summary 与对应测试，并确认 `bb check` 通过。

2026-04-02：完成 Milestone 3：新增 collection-level `dispatch/cooldown-until`、按 task resource policy `queue-order` 的 dispatch 排序、按 pinned `resource-policy-ref` 的 active-run ceiling 统计，以及对应 CLI / projection / scheduler 测试；`bb test` 通过，`bb check` 其余环节受依赖下载超时影响未完整重跑。
