# Phase 2：实现失败恢复、资源策略与调度收敛

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `.agent/PLANS.md`.

This document is a subordinate ExecPlan of `docs/meta-flow-program-execplan.md`. It assumes Phase 1 has already produced a working host skeleton and a passing mock happy path. If this document conflicts with the main plan, the main plan wins.

## Purpose / Big Picture

这一阶段的目标是让系统不只会成功，还会在失败、超时、冷却和重试场景下稳定收敛。完成后，使用者可以看到一个 task 因 artifact 不合格而进入 `retryable_failed`，随后被重新排队、重新创建 run，并在超过重试预算时进入 `needs_review`。使用者还可以看到 lease 超时、worker 无 heartbeat 和 provider cooldown 对调度行为的影响。

这一阶段不接入真实 Codex runtime。它专注于宿主控制面本身：状态机、资源边界、恢复逻辑和调度优先级。所有行为仍然应使用 mock runtime 或受控测试替身验证。

## Scope Boundaries

本阶段范围内的工作包括：retry path、validator rejection、disposition policy、run/lease timeout、takeover、collection-level resource policy、cooldown、projection 驱动的调度优先级、失败与恢复测试。

本阶段范围外的工作包括：真实 `codex exec` 集成、项目级 `CODEX_HOME` 模板、prompt 设计、MCP 能力面、worker helper 的外部进程启动细节、任何跨主机部署方案。即使本阶段会继续使用 runtime adapter 协议，也只允许 mock 或测试专用 adapter。

本阶段必须继承主 plan 和 Phase 1 的稳定边界：SQLite 仍然是单机真相源；definitions 仍然是只读静态数据；projection v1 仍然只读；事件 ingestion 仍然必须走单一幂等 API；任何新增控制逻辑都不能破坏数据库级不变量；任何新增 run state、policy 字段或 definition 内容都必须同步更新 SQLite 约束、definitions version 和对应测试。

## Progress

- [x] (2026-04-01 13:12Z) 从主 plan 提炼 Phase 2 的实现边界，并确认本阶段只处理宿主控制面的失败恢复与资源策略。
- [x] (2026-04-01 13:12Z) 为本阶段写出独立 subplan，明确 in-scope、out-of-scope、接口与验收标准。
- [ ] 基于 Phase 1 补齐 validator rejection 到 disposition 的完整失败路径。
- [ ] 实现 retry policy、max-attempts 和回退到 `queued` 的路径。
- [ ] 实现 lease 过期、heartbeat 超时和 run takeover 规则。
- [ ] 实现 collection state 中的 dispatch pause、resource ceilings 和 cooldown。
- [ ] 扩展 ProjectionReader 查询，使其能支持 backlog、expired lease、retry candidate 与 priority ordering。
- [ ] 补齐失败恢复集成测试和 property-like 调度测试。

## Surprises & Discoveries

- Observation: Phase 2 的难点不在于多写几个状态，而在于保证 fresh scheduler 多次运行后仍然单调收敛。
  Evidence: 主 plan 已将“重复执行 `scheduler once` 不制造第二套真相”列为核心验收标准。

## Decision Log

- Decision: 本阶段仍使用 mock 或 test runtime，不提前接入 Codex。
  Rationale: 失败与恢复路径本质上是控制面正确性问题。用真实外部 runtime 只会把排障维度扩大。
  Date/Author: 2026-04-01 / Codex

- Decision: retry、takeover、cooldown 的优先级必须通过 ProjectionReader 统一查询表达，而不是散落在 scheduler 内部临时计算。
  Rationale: 这样可以保持 projection 的“只读输入层”定位，同时让调度排序可测试、可解释。
  Date/Author: 2026-04-01 / Codex

## Outcomes & Retrospective

本阶段尚未实现代码。阶段完成时，本节应记录系统在失败场景下是否真正稳定收敛，是否出现过重复 run、重复 lease、重复 disposition 或事件吸收歧义，以及 ProjectionReader 是否保持了只读边界。

## Context and Orientation

Phase 1 已经建立 happy path。本阶段的工作是在不破坏 Phase 1 边界的前提下，把调度器扩展成真正可恢复的控制器。

这里要明确几个新术语。

`retry policy` 指 task policy 中关于最大尝试次数、失败后是否允许重排队、以及是否需要冷却窗口的规则。它不是 validator 的工作；validator 只产出 assessment facts。

`takeover` 指当某个 run 的 lease 已过期，或 worker 已长时间无 heartbeat，调度器通过 run state machine 把该 run 转入 `taken-over`，随后再把它推进到 `abandoned`。它不是“继续使用旧 run”，也不是 scheduler disposition。

`cooldown` 指 collection-level 的资源暂停状态。它作用于 dispatch 决策，而不阻止 validation、failure convergence 或 inspect。

本阶段的一个核心原则是：assessment 仍然只描述事实，disposition 才描述控制动作。不要把“该不该重试”塞回 validator。

本阶段还必须把 retry 状态机写死。Phase 2 采用的唯一允许语义是：

- validator 拒绝 artifact 后，scheduler 写 rejected assessment
- scheduler 基于 assessment 和 policy 决定 disposition 为 `retry` 或 `escalate`
- 如果 disposition 为 `retry`，task 必须先进入 `:task.state/retryable-failed`
- 之后 scheduler 通过显式 `requeue` 控制动作把 task 从 `:task.state/retryable-failed` 迁移回 `:task.state/queued`
- 只有处于 `:task.state/queued` 的 task 才允许被重新 lease

这里明确不允许 `:task.state/retryable-failed -> :task.state/leased` 的直连捷径。这样做是为了让 retry backlog 在调度输入中保持可见，并让 projection 与 task FSM 的语义一致。

本阶段也必须把 takeover 顺序写死。唯一允许的顺序是：

- 检测到 lease 过期或 heartbeat 超时
- run 进入 `:run.state/taken-over`
- 同一轮或后续轮由 scheduler 把旧 run 推进到 `:run.state/abandoned`
- 只有旧 run 已经是 terminal state `:run.state/abandoned` 后，才允许为该 task 创建新的 run

这里明确不允许“旧 run 仍处于 `taken-over` 时就先创建新 run”，因为那会破坏“一个 task 同时最多一个非终态 run”的全局不变量。

## Milestones

### Milestone 1: 实现 rejection 到 retry 的失败路径

这一小里程碑结束时，系统能通过一个故意不满足 contract 的 artifact 触发 rejected assessment，并由 scheduler 写出 retry disposition，把 task 先转到 `retryable_failed`，再通过显式 requeue 迁移回 `queued`。

### Milestone 2: 实现超时与接管

这一小里程碑结束时，系统能识别 lease 过期和 heartbeat 超时，通过状态机把旧 run 先推进到 `taken-over`，再推进到 `abandoned`，并且只有在旧 run 已 terminal 后才创建新 run。

### Milestone 3: 实现资源策略与冷却

这一小里程碑结束时，collection state 和 ProjectionReader 能共同支持 dispatch pause、provider cooldown、并发上限和 backlog 优先级。调度器会在 cooldown 期间停止新 dispatch，但继续 validation 和状态收敛。

## Plan of Work

先扩展 definition layer 中与 Phase 2 相关的内容。必要时增加 retry policy、cooldown policy、resource policy class 的字段，但不能改变“definitions 只读静态数据”的边界。task type、validator 和 resource policy 的版本绑定仍需显式保留。任何 definition 结构变化都必须 bump version，并让新创建或重新评估的 runtime state 显式 pin 到新版本。

然后扩展 validator 和 disposition 逻辑。validator 负责给出 accepted 或 rejected assessment 以及 finding；scheduler 根据 assessment、attempt 次数、policy/max-attempts 和 collection state 决定 `complete`、`retry` 或 `escalate`。这里要特别避免把“超过最大尝试次数就直接 rejected”这种控制逻辑塞进 validator。Phase 2 的 retry path 必须是 `awaiting-validation -> retryable-failed -> queued -> leased` 这一显式 task 路径，而不是隐藏在 scheduler 内的直连重试。

接着实现超时与接管。调度器每轮先扫描 ProjectionReader 给出的 expired lease run ids 和 heartbeat timeout run ids，再通过 run state machine 显式迁移推进 run state。`takeover` 和 `abandon` 在本阶段必须被视为 run control transition，而不是 disposition。这里的关键不是检测超时本身，而是保证重复执行 `scheduler once` 时不会重复接管同一个 run，并且不会在旧 run 仍非终态时创建新 run。

之后实现 collection-level 资源策略。`collections` 表中的 dispatch pause、resource ceilings、cooldown-until 和 budget-class 必须成为调度输入。ProjectionReader 应提供可解释的 snapshot，让调度器基于 backlog、active run 数和 cooldown 状态做有限决策。任何为了支持这些策略而新增的 run state、task policy 或 resource policy definition，都必须同步更新 SQLite 索引谓词、definitions version 和数据库测试。

最后补齐测试。Phase 2 的验收主要靠测试证明而不是手工 eyeballing：重试上限、冷却期间不 dispatch、validation 在 cooldown 期间继续进行、接管幂等、重复 scheduler 不制造第二个非终态 run。

## Concrete Steps

所有命令都在 `/home/mikewong/proj/main/meta-flow` 运行。

先实现一个失败演示命令：

    clojure -M -m meta-flow.main demo retry-path

期望输出：

    Enqueued task 7bc1...
    Created run c33d... attempt 1
    Assessment rejected: missing :artifact/notes
    Disposition :disposition/retry
    Task 7bc1... -> :task.state/retryable-failed

然后重复执行调度器，验证重试或升级路径：

    clojure -M -m meta-flow.main scheduler once
    clojure -M -m meta-flow.main scheduler once

如果 `policy/max-attempts` 允许，期望先看到 task 被 requeue 到 `:task.state/queued`，再在后续调度中创建新 run attempt；如果上限已到，期望最终看到 `:task.state/needs-review`。

再实现一个冷却演示命令或测试 fixture，使 collection 进入 cooldown，再运行：

    clojure -M -m meta-flow.main inspect collection
    clojure -M -m meta-flow.main scheduler once

期望输出中应说明 dispatch 被暂停，但 validation 或 failure convergence 仍在继续。

## Validation and Acceptance

本阶段验收标准至少包括以下行为：

一，contract 不完整的 artifact 会被 validator 拒绝，并产生 rejected assessment，而不是直接由 runtime 自行决定失败。

二，scheduler 根据 rejected assessment 和 policy/max-attempts 写出 retry 或 escalate disposition；重复执行 `scheduler once` 不会重复写入等价 disposition。

三，retry path 必须是显式的 `retryable-failed -> queued -> leased`，不允许 `retryable-failed -> leased` 直连。

四，lease 过期或 heartbeat 超时后，旧 run 会先进入 `taken-over` 再进入 `abandoned`；系统不会让同一 task 同时出现两个非终态 run，新 run 只能在旧 run terminal 后创建。

五，cooldown 生效时，新的 dispatch 会暂停，但 validation、inspection 和 failure convergence 继续运行。

六，ProjectionReader 仍然只读，不引入任何 projection cache 表。

七，任何新增 run state、retry policy、cooldown policy 或 resource policy definition，都必须伴随对 SQLite 索引谓词、definition version pinning 和数据库测试的同步更新。

测试命令：

    clojure -M:test
    cljt.cljfmt check
    clj-kondo --lint src test script

推荐新增至少这些测试文件：

    test/meta_flow/scheduler_retry_test.clj
    test/meta_flow/scheduler_takeover_test.clj
    test/meta_flow/resource_policy_test.clj
    test/meta_flow/projection_test.clj

## Idempotence and Recovery

本阶段所有新增路径都必须是幂等的。重复 rejection 观察不应生成重复 retry disposition；重复 takeover 检查不应让旧 run 被多次 abandon；重复 cooldown 检查不应反复创建相同控制事件。

如果某个 run 已被接管，任何后续迟到 heartbeat 都不能让它重新变成 active run。计划内允许把这类迟到信号保留为事件记录，但不允许它们覆盖当前控制面真相。

本阶段还必须保持语义分离：`takeover` 和 `abandon` 属于 run state machine transition，不属于 disposition；`retry`、`complete`、`escalate` 属于 disposition，不属于 run transition。

## Artifacts and Notes

本阶段最重要的新增成果将集中在以下文件：

    src/meta_flow/service/validation.clj
    src/meta_flow/service/runs.clj
    src/meta_flow/service/collections.clj
    src/meta_flow/projection.clj
    src/meta_flow/scheduler.clj
    resources/meta_flow/defs/resource-policies.edn
    test/meta_flow/scheduler_retry_test.clj
    test/meta_flow/scheduler_takeover_test.clj
    test/meta_flow/resource_policy_test.clj

## Interfaces and Dependencies

本阶段不新增新的基础设施依赖。重点是扩展既有接口语义。

`ProjectionReader` 至少应能回答这些问题：

    (load-scheduler-snapshot [reader now])
    (list-runnable-task-ids [reader now limit])
    (list-awaiting-validation-run-ids [reader now limit])
    (list-expired-lease-run-ids [reader now limit])

如有必要，可以增加只读查询函数，例如：

    (list-retry-candidate-task-ids [reader now limit])
    (list-heartbeat-timeout-run-ids [reader now limit])

但不允许增加任何写 projection 的接口。

调度器本体 `schedule-once!` 仍必须返回结构化 summary map。Phase 2 应扩展 summary，使其能显示 `:summary/retried`、`:summary/requeued`、`:summary/escalated`、`:summary/taken-over`、`:summary/abandoned`、`:summary/cooldown-skipped` 等字段。

## Revision Note

2026-04-01：创建 Phase 2 subplan。该文档从主 plan 中提炼出“失败恢复、资源策略与调度收敛”的执行细节，并明确本阶段不包含真实 Codex runtime 集成。
