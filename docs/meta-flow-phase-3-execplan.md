# Phase 3：接入 Codex runtime adapter，并保持控制面不变

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `.agent/PLANS.md`.

This document is a subordinate ExecPlan of `docs/meta-flow-program-execplan.md`. It assumes Phase 1 and Phase 2 have already produced a stable host control plane. If this document conflicts with the main plan, the main plan wins.

## Purpose / Big Picture

这一阶段的目标是把真实 Codex 受控执行接入已经稳定的宿主控制面，而不是重写控制面。完成后，使用者可以为某个 task type 指定 `:runtime-profile/codex-worker`，由调度器在项目级 `CODEX_HOME`、固定 prompt 和受控能力面下启动 `codex exec` worker。worker 会通过本地 helper 把 heartbeat、progress、worker-exit、artifact-ready 等事件写回 SQLite，最终让 artifact 进入已有的 validator 和 disposition 闭环。

最重要的成功标准不是“Codex 跑起来了”，而是“Codex 成为了一个受控 runtime adapter，且没有破坏已有 control plane 模型”。task、run、lease、event、artifact、assessment、disposition、collection state 的边界必须与前两个 phase 保持一致。

## Scope Boundaries

本阶段范围内的工作包括：Codex runtime profile、项目级 `CODEX_HOME` 目录模板、prompt 模板、worker snapshot 文件、`codex exec` 启动包装、babashka helper、Codex runtime adapter、显式 smoke test。

本阶段范围外的工作包括：改变 SQLite schema 的核心控制模型、改变 task/run/lease/event 的语义、改变 ProjectionReader 为可写缓存、把 definitions 搬进 SQLite、引入多主机调度、把 worker 直接改成长期会话 agent。本阶段也不负责设计新的业务 task type，只负责让现有 task type 可以用 Codex profile 运行。

## Progress

- [x] (2026-04-01 13:12Z) 从主 plan 提炼 Phase 3 的实现边界，并确认本阶段只做 Codex runtime 集成，不改控制面模型。
- [x] (2026-04-01 13:12Z) 为本阶段写出独立 subplan，明确 in-scope、out-of-scope、接口与验收标准。
- [ ] 实现 `:runtime-profile/codex-worker` 的完整定义。
- [ ] 实现项目级 `var/codex-home/` 初始化与安装脚本。
- [ ] 实现 prompt 模板、worker snapshot 与 `codex exec` 启动包装。
- [ ] 实现 `script/worker_api.bb`，用于事件回写与最小 helper 操作。
- [ ] 实现 `src/meta_flow/runtime/codex.clj` 并接入 RuntimeAdapter 注册表。
- [ ] 编写和运行显式启用的 Codex smoke test。

## Surprises & Discoveries

- Observation: 本阶段最大的风险不是 Codex 本身，而是让外部 runtime 事件进入现有控制面时保持幂等与可恢复。
  Evidence: 主 plan 已要求所有事件必须通过统一 ingestion API 写入，生产者不得自行分配 `event/seq`。

## Decision Log

- Decision: Codex worker 仍然是短生命周期执行单元，不设计成长会话控制器。
  Rationale: 主架构已经明确 fresh scheduler 和短生命周期 worker 是恢复与审计能力的基础。接入真实 runtime 不能回退到“把系统状态寄托在长期会话里”。
  Date/Author: 2026-04-01 / Codex

- Decision: Codex runtime adapter 只负责运行时装配与外部事件吸收，不拥有业务调度逻辑。
  Rationale: 如果把 retry、completion 或 escalation 逻辑放入 adapter，就会破坏宿主控制面与 runtime adapter 的边界。
  Date/Author: 2026-04-01 / Codex

## Outcomes & Retrospective

本阶段尚未实现代码。完成时，本节应明确回答三件事：Codex runtime 是否在不破坏主边界的前提下接入；事件回写是否稳定幂等；外部 runtime 的失败是否仍能被宿主控制面恢复和收敛。

## Context and Orientation

Phase 1 和 Phase 2 已经提供一个完整的本地 control plane。本阶段只是在 `RuntimeAdapter` 的具体实现上新增 `codex` 版本。

Codex runtime adapter 必须从 runtime profile 中读取所有运行时参数，而不是在 adapter 代码里写死。最少包括：

- `CODEX_HOME` 根目录
- worker prompt 路径
- 是否启用 Web search
- 允许的 MCP 集
- 环境变量白名单
- heartbeat 周期
- worker 超时
- helper 脚本路径
- artifact contract id

此外，本阶段必须把“外部执行句柄”做成持久化契约，而不是只存在于内存中。每次 `dispatch-run!` 成功后，系统必须把后续 fresh scheduler 可恢复读取的最小执行句柄写入权威状态或由权威状态指向的 workdir 文件，并让 `poll-run!` 与 `cancel-run!` 只依赖这份持久化句柄工作。这个句柄至少要包含：

- run id
- 启动时间
- 启动命令摘要
- wrapper 生成的 execution id 或等价稳定标识
- 可用于 poll/cancel 的本地进程句柄信息，例如 pid 或 pidfile 路径
- 对应的 run workdir 路径

这里不允许把“当前外部进程句柄”只保存在宿主进程内存中。

worker 在 dispatch 前必须获得一个稳定的 run snapshot 目录，至少包含 `task.edn`、`run.edn`、`definitions.edn`、`runtime-profile.edn`、artifact contract 摘要，以及必要的输出目录约定。worker 不能依赖宿主进程内存得到这些信息。

本阶段还必须把 helper 与 poller 的事件归属写死。唯一允许的语义是：

- helper 优先负责主动事件：`heartbeat`、`progress`、`worker-exit`、`artifact-ready`
- poller 只负责兜底观察：当 helper 未成功写入事件，或者宿主需要从 pidfile / 进程状态 / 文件系统状态恢复时，poller 才生成等价 event intent
- helper 和 poller 必须使用不同前缀的 `event/idempotency-key`
- poller 在生成 `worker-exit` 或 `artifact-ready` 之前，必须先检查该 run 是否已经存在同语义事件，避免重复吸收

这里明确不允许 helper 和 poller 都把同一个物理事实当作自己的常规职责。

## Milestones

### Milestone 1: 实现受控运行时装配

这一小里程碑结束时，项目级 `CODEX_HOME`、prompt 模板、runtime profile、snapshot 写出逻辑都已存在，虽然还未必跑通完整 smoke test，但运行时边界已经是显式数据。

### Milestone 2: 实现事件回写与外部执行吸收

这一小里程碑结束时，Codex worker 可通过 helper 以幂等方式回写 heartbeat、progress、worker-exit 和 artifact-ready 事件；调度器可通过 `poll-run!` 吸收外部进程观察而不制造重复事件。

### Milestone 3: 跑通 Codex smoke test

这一小里程碑结束时，显式启用的 smoke test 能在存在 `codex` 命令和有效 provider 配置的机器上成功运行一条最小任务，最终让 task 收敛到 `completed` 或进入预期失败路径。

## Plan of Work

先扩展 definitions。新增或补全 `:runtime-profile/codex-worker`，并把运行时参数全部放进 definitions，而不是散落在 shell 脚本里。`task-type/cve-investigation` 在本阶段必须默认引用 Codex profile；测试中允许显式覆盖到 mock profile，但默认绑定不能留空或延后决定。

然后实现项目级 `CODEX_HOME` 初始化。安装脚本只负责在 `var/codex-home/` 下准备所需目录和基础配置，不应污染用户默认的 `~/.codex`。这个脚本要被明确设计成可重复执行。

接着实现 prompt 模板、snapshot 写出逻辑和 durable process handle。调度器或 adapter 在 dispatch 前把 run 上下文写入 workdir，使 worker 即使在 fresh shell 中也能完整获得任务快照。dispatch 成功后，adapter 必须把外部执行句柄写入 SQLite 可读的持久化位置，例如结构化 run 字段或 `var/runs/<run-id>/process.json` 加 SQLite 指针。prompt 必须说明 worker 的能力边界和回写方式，但不能承担控制面真相。

之后实现 `script/worker_api.bb` 和 Codex runtime adapter。worker helper 只提供窄接口，例如写 heartbeat、写 progress、写 worker-exit、写 artifact-ready。helper 本身不能直接写 `events` 表的任意字段，它必须调用宿主约定的事件吸收路径。`poll-run!` 必须只做兜底恢复，不应与 helper 竞争同一常规事件职责。

最后实现 smoke test。该测试必须显式通过环境变量打开，并在缺少 `codex` 命令或 provider 配置时给出明确跳过或失败说明，而不是静默挂起。

## Concrete Steps

所有命令都在 `/home/mikewong/proj/main/meta-flow` 运行。

先实现 Codex 运行时准备命令：

    clojure -M -m meta-flow.main runtime init-codex-home

期望输出：

    Ensured project CODEX_HOME at var/codex-home
    Installed runtime templates for codex worker

然后实现显式 smoke test：

    META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main demo codex-smoke

在环境完整时，期望输出类似：

    Enqueued task 91ab...
    Created run 2ef0... attempt 1
    Dispatched codex worker with runtime-profile/codex-worker
    Ingested heartbeat event
    Ingested artifact-ready event
    Assessment accepted
    Task 91ab... -> :task.state/completed

如果环境不完整，输出必须明确指出原因，例如：

    Codex smoke test cannot start: `codex` command not found

或：

    Codex smoke test cannot start: missing provider credentials for configured runtime profile

## Validation and Acceptance

本阶段验收标准至少包括以下行为：

一，Codex worker 的所有可观察行为都通过既有 control plane 表达：heartbeat、progress、artifact-ready、worker-exit 都以事件形式出现，并经过统一 ingestion API。

二，worker 失败、退出或中途无响应时，宿主控制面仍然可以靠 SQLite、持久化的外部执行句柄和文件系统状态恢复，不依赖会话记忆。

三，项目级 `CODEX_HOME` 与用户默认 `~/.codex` 隔离；删除 `var/codex-home/` 只会影响后续 runtime 启动，不会破坏既有 SQLite 权威状态。

四，运行时能力边界必须可验证，而不是只在 definitions 中声明。至少要证明：worker 进程使用的是 `var/codex-home/` 而不是 `~/.codex`；只继承 runtime profile 白名单中的环境变量；只启用 runtime profile 允许的 MCP 集；Web search 开关与 runtime profile 一致。

五，helper 与 poller 的职责边界必须可验证。至少要证明：helper 已写入的 `artifact-ready` 或 `worker-exit` 不会被 poller 再次吸收成重复事件；当 helper 缺失时，poller 仍可用不同前缀的 idempotency key 兜底生成一次事件。

六，Codex smoke test 必须显式 opt-in，不影响默认测试命令。

测试命令：

    clojure -M:test
    cljt.cljfmt check
    clj-kondo --lint src test script
    META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main demo codex-smoke

## Idempotence and Recovery

`runtime init-codex-home` 必须幂等。重复执行只允许补齐缺失文件，不允许无提示覆盖使用者手动修改过的关键配置；如需覆盖，必须通过显式 flag。

worker helper 的事件回写必须幂等。即使 Codex worker 因重试或 shell 波动重复提交同一 heartbeat 或 artifact-ready，也不得造成重复事件或错误状态迁移。

如果 Codex worker 已退出但 artifact 已经写完，调度器仍应能在后续 `scheduler once` 中通过持久化执行句柄、polling 和 event ingestion 收敛到正确 run state。

## Artifacts and Notes

本阶段最重要的新增文件应包括：

    src/meta_flow/runtime/codex.clj
    resources/meta_flow/prompts/worker.md
    resources/meta_flow/prompts/mgr-notes.md
    script/worker_api.bb
    script/install_codex_home.bb
    test/meta_flow/codex_adapter_smoke_test.clj

run workdir 在本阶段至少应包含：

    var/runs/<run-id>/task.edn
    var/runs/<run-id>/run.edn
    var/runs/<run-id>/definitions.edn
    var/runs/<run-id>/runtime-profile.edn
    var/runs/<run-id>/artifact-contract.edn
    var/runs/<run-id>/process.json

## Interfaces and Dependencies

本阶段仍然使用主 plan 批准的依赖集合，不增加新的数据库或异步框架。

`RuntimeAdapter` 的 Codex 实现必须满足：

    (adapter-id [this])                 ;; => :runtime-adapter/codex
    (prepare-run! [this ctx task run])  ;; 写出 workdir 和 snapshot
    (dispatch-run! [this ctx task run]) ;; 启动 codex exec
    (poll-run! [this ctx run now])      ;; 吸收外部进程与文件系统观察
    (cancel-run! [this ctx run reason]) ;; 尝试中止外部执行

`dispatch-run!` 必须在成功启动外部执行后，持久化写出后续 fresh scheduler 可恢复读取的 execution handle。`poll-run!` 不能直接篡改 run state；它只能基于 execution handle 生成 event intent 或外部观察，由宿主控制面继续推进状态机。`cancel-run!` 也必须只依赖 execution handle，不依赖宿主进程内存中的临时对象。

## Revision Note

2026-04-01：创建 Phase 3 subplan。该文档从主 plan 中提炼出“Codex runtime adapter 集成”的执行细节，并明确本阶段不允许改变已有控制面模型。
