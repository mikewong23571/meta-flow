# Phase 3：沿当前 runtime 基线接入 Codex adapter

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `.agent/PLANS.md`.

This document is a subordinate ExecPlan of `docs/meta-flow-program-execplan.md`. It assumes Phase 1 and Phase 2 have already produced a stable host control plane and that the current repository baseline already includes the runtime protocol, mock adapter, Codex runtime profile definition, runtime prompt/helper resource paths, and durable execution-handle pattern. It does not assume that the Codex adapter, registry wiring, CLI entrypoints, or worker helper semantics are already implemented. If this document conflicts with the main plan, the main plan wins.

## Purpose / Big Picture

这一阶段的目标不是重新设计 runtime 层，而是在当前 runtime 抽象已经成立的基础上，把真实 Codex 受控执行接进去。当前仓库已经具备这些前置条件：`RuntimeAdapter` 协议已经固定；mock adapter 已经证明了 `prepare-run!` / `dispatch-run!` / `poll-run!` / `cancel-run!` 的控制面形状；`task-type/cve-investigation` 已默认 pin 到 `:runtime-profile/codex-worker`；Codex runtime profile、prompt 路径和 helper 路径已经存在；调度层也已经会把 dispatch 返回值持久化为 `:run/execution-handle`。但这些前置条件里仍有几项只是 Phase 3 起点而不是完成项：runtime registry 还没有 `:runtime.adapter/codex`，`script/worker_api.bb` 仍是 placeholder，`resources/meta_flow/prompts/worker.md` 仍是 placeholder，CLI 也还没有 `runtime init-codex-home` 或 `demo codex-smoke`。

Phase 3 的工作是在这条基线上继续前进：实现 `meta-flow.runtime.codex`、接入 runtime registry、完成项目级 `CODEX_HOME` 模板安装、补齐 worker helper 与 durable process handle、以及提供显式 opt-in 的 smoke path。重要标准仍然是“Codex 成为现有控制面的一个 runtime adapter”，而不是“为了接入 Codex 改写控制面模型”。

## Scope Boundaries

本阶段范围内的工作包括：完成并验证 `:runtime-profile/codex-worker` 的真实落地、项目级 `CODEX_HOME` 模板安装、worker snapshot 写出、`codex exec` 启动包装、helper / poller 职责边界、durable process handle、Codex runtime adapter、CLI 集成与显式 opt-in smoke test。

本阶段范围外的工作包括：改变 SQLite 核心控制模型、改变 task/run/lease/event 的语义、改变 ProjectionReader 为可写缓存、把 definitions 搬进 SQLite、引入多主机调度、把 worker 改成长会话 agent，以及绕开当前 runtime 协议另起一套专用执行框架。

本阶段同样必须服从当前仓库的真实工程规则：沿现有目录和协议边界增量实现；默认以 `bb check` 为基础 gate；控制文件长度、目录宽度和覆盖率；不把新增 Codex 逻辑重新塞回顶层 orchestrator 文件。

## Progress

- [x] (2026-04-01 13:12Z) 从主 plan 提炼 Phase 3 的实现边界，并确认本阶段只做 Codex runtime 集成，不改控制面模型。
- [x] (2026-04-01 13:12Z) 为本阶段写出独立 subplan，明确 in-scope、out-of-scope、接口与验收标准。
- [x] (2026-04-02 00:00Z) 确认 `:runtime-profile/codex-worker` 已存在于 definitions 中，且 `:task-type/cve-investigation` 已默认引用该 profile。
- [x] (2026-04-02 00:00Z) 确认 mock runtime 已经实现 workdir snapshot 与 durable execution handle 模式，可作为 Codex adapter 的结构模板。
- [x] (2026-04-02 17:00Z) 复核当前代码事实并确认仓库已具备 Phase 3 开工条件：runtime seam、definitions pinning、`var/codex-home/` 目录基线与 durable handle 都已存在，不需要额外插入一个“Phase 2.5”清理阶段。
- [x] (2026-04-02 17:00Z) 收紧本阶段起点定义：当前 prompt/helper 资源仍是 placeholder，runtime registry 尚未接入 Codex，CLI 尚无 `runtime init-codex-home` / `demo codex-smoke` 入口；这些都应作为本阶段交付，而不是视为已完成基线。
- [x] (2026-04-03 00:12Z) 完成 Phase 3 / Milestone 1：实现 `src/meta_flow/runtime/codex.clj` 及 `runtime/codex/{fs,home,events,execution}.clj`，让 Codex runtime 按现有 seam 写真实 run snapshot 和 durable `process.json` handle。
- [x] (2026-04-03 00:12Z) 将 Codex adapter 接入 `meta-flow.runtime.registry`，使 scheduler 在 codex profile 下能解析到真实 adapter，而不再把 codex task 当成“缺失 adapter”路径。
- [x] (2026-04-03 00:12Z) 实现项目级 `CODEX_HOME` 模板安装命令 `runtime init-codex-home`，在现有 `var/codex-home/` 目录基线上幂等写入模板并保留已有用户文件。
- [x] (2026-04-03 03:20Z) 完成 Phase 3 / Milestone 2：实现 `script/worker_api.bb` 到 `meta-flow.runtime.codex.worker-api` 的 helper 桥接、外部 stub worker dispatch、helper 事件回写，以及基于 durable `process.json` 的 poller 兜底恢复与去重测试。
- [x] (2026-04-03 10:26Z) 完成 Phase 3 / Milestone 3：新增显式 opt-in 的 `demo codex-smoke` CLI、真实 `codex exec` wrapper worker、launch support / provider guardrail、`codex_smoke_test`，并通过 `bb fmt:check`、`bb lint`、`bb test`、`bb coverage`、`bb check`；默认 gate 继续不依赖外部 Codex 环境。

## Surprises & Discoveries

- Observation: Phase 3 也不是从零开始，Codex definitions 与 prompt 资源已经先于 adapter 实现落地。
  Evidence: `resources/meta_flow/defs/runtime-profiles.edn` 已包含 `:runtime-profile/codex-worker`，`resources/meta_flow/defs/task-types.edn` 已让 `:task-type/cve-investigation` 默认引用该 profile，`resources/meta_flow/prompts/worker.md` 也已存在。

- Observation: mock runtime 已经证明了 Phase 3 最关键的结构约束，即 workdir 文件约定与 `:run/execution-handle` 持久化，但它当前只是提供最小占位快照，而不是完整 Codex worker 契约。
  Evidence: `src/meta_flow/runtime/mock/execution.clj` 已在 prepare 阶段写出 task/run/definitions/runtime-profile 等文件路径并在 dispatch 阶段返回 durable handle；但其中 `runtime-profile.edn` 当前写入的是 ref，`artifact-contract.edn` 也是占位内容，因此 Codex adapter 需要沿用文件契约并补齐文件语义。

- Observation: 当前仓库里的 Codex helper 与 worker prompt 还不能被视为“基础设施已完成”，它们只是为了让 definitions 校验和路径 pinning 有落点的 placeholder。
  Evidence: `script/worker_api.bb` 目前只打印 `Phase 1 placeholder worker API helper`；`resources/meta_flow/prompts/worker.md` 也明确写着后续 milestone 才会替换成真实 prompt。

- Observation: 现有 CLI 和开发演示默认仍围绕 mock runtime 设计，因此 Phase 3 应新增显式 opt-in 路径，而不是把现有 happy-path / retry-path 演示直接改成默认走 Codex。
  Evidence: `src/meta_flow/cli.clj` 当前只有 `init`、`defs validate`、`enqueue`、`scheduler once`、`demo happy-path`、`demo retry-path` 和 inspect 命令；`src/meta_flow/scheduler/dev.clj` 里的 demo task builder 仍默认把 runtime profile override 到 `:runtime-profile/mock-worker`。

- Observation: helper 与 poller 都能观测到 `artifact-ready` 这个物理事实时，只靠“event type 是否已存在”判断不够，会在 helper 刚写完 run-level event、task-level event 还未落库的瞬间制造重复 task 事件。
  Evidence: Milestone 2 初版在 `bb test` 的 `meta-flow.runtime.codex-test/scheduler-codex-managed-worker-completes-through-the-existing-control-plane` 中出现第二条 `:task.event/artifact-ready`，其幂等键前缀分别为 `codex-helper:` 与 `codex-poll:`；修复方式是让 helper 在写 artifact-ready 事件前先把 `process.json` 的 `helperEvents.artifactReady` 置位，并让 poller 在看到该 ownership 标记后放弃补发 artifact-ready。

## Decision Log

- Decision: Phase 3 复用当前 runtime 协议、scheduler/runtime 装配层和 mock runtime 的 handle/snapshot 结构，不另起一套 Codex 专用控制通道。
  Rationale: 当前仓库已经有可工作的 runtime seam。Codex 应填充这个 seam，而不是绕过它。
  Date/Author: 2026-04-02 / Codex

- Decision: 当前 adapter id 命名以仓库事实为准，使用 `:runtime.adapter/codex`，并要求文档、definitions、registry 和测试保持一致。
  Rationale: 当前 definitions 已采用 `:runtime.adapter/codex`；计划不应再混入另一种拼写。
  Date/Author: 2026-04-02 / Codex

- Decision: `runtime init-codex-home` 视为对现有 `var/codex-home/` 目录基线的模板安装，而不是另一个平行初始化体系。
  Rationale: `init` 已经会创建 `var/codex-home/` 目录；Phase 3 只应补齐其中的模板与运行时文件。
  Date/Author: 2026-04-02 / Codex

- Decision: Phase 3 不把现有 `demo happy-path` / `demo retry-path` 从 mock runtime 改成默认走 Codex；Codex 集成通过新的显式入口或显式 runtime-profile override 暴露。
  Rationale: 当前 demo 和测试已经把 mock runtime 作为默认闭环锁定。继续保留这条稳定基线，才能让 Codex smoke 成为 opt-in 集成验证，而不是把默认 gate 变成依赖外部 provider 的路径。
  Date/Author: 2026-04-02 / Codex

- Decision: Milestone 2 先把 Codex adapter 的“外部执行 + helper + poller”闭环建立在受控 stub worker 上，而不是把真实 `codex exec` 启动作为默认测试路径。
  Rationale: 这一阶段的首要目标是证明 control plane seam、durable handle、helper 回写与 poller recovery 已经成立，并保持默认 `bb test` / `bb check` 不依赖 provider 凭证。真实 `codex exec` smoke 留给 Milestone 3 的显式 opt-in 路径。
  Date/Author: 2026-04-03 / Codex

## Outcomes & Retrospective

Phase 3 已全部完成。当前代码库已经从 “Codex runtime assembled but not yet executing real worker callbacks” 推进到 “Codex runtime 既能维持默认 stub-worker gate，也能在显式 opt-in 时跑真实 `codex exec` smoke” 的状态：Codex adapter 继续沿既有 runtime seam 写真实 run snapshot 与 durable `process.json` handle，`demo codex-smoke` 会在启用 `META_FLOW_ENABLE_CODEX_SMOKE=1` 后切到真实 launch mode，并在 `codex` 缺失或 provider 凭证缺失时给出可解释失败；同时默认 `bb test` / `bb check` 仍只依赖本地 gate，不被外部 provider 环境污染。新增的 smoke/launch/worker 测试也把 launch mode、`CODEX_HOME`、env allowlist、web search 标志和真实 worker wrapper 行为固定为可重复验证的边界。

## Context and Orientation

当前仓库的 runtime 基线已经很清楚。

`RuntimeAdapter` 协议当前固定为：

- `adapter-id`
- `prepare-run!`
- `dispatch-run!`
- `poll-run!`
- `cancel-run!`

`scheduler.runtime` 当前负责三件事：根据 runtime profile 找 adapter、构建 runtime context、把 dispatch 返回值持久化为 `:run/execution-handle`。这意味着 Codex adapter 不需要重新定义“谁负责持久化 handle”，而应与现有装配层配合。

mock runtime 当前已经提供了可复用的 workdir 文件约定。prepare 阶段当前会写出：

- `task.edn`
- `run.edn`
- `definitions.edn`
- `runtime-profile.edn`
- `artifact-contract.edn`
- `runtime-state.edn`

dispatch 阶段会返回可持久化到 `:run/execution-handle` 的 map。happy-path 测试已经把这件事锁定。因此，Codex runtime 最自然的延伸方式是：沿同样的 workdir contract 写 snapshot，并把外部执行信息补充到现有 handle 结构上，例如 pid、pidfile、wrapper execution id、启动命令摘要和 `process.json` 路径。这里还要尊重当前代码事实：mock runtime 写出的 `definitions.edn`、`runtime-profile.edn` 和 `artifact-contract.edn` 仍是最小占位内容，Phase 3 应补齐这些文件的真实语义，而不是把 placeholder 当成完整契约。

这里要特别强调一点：在当前 runtime seam 下，`dispatch-run!` 不只是“启动外部执行并返回 handle”，它还必须让 run 获得 `:run.event/dispatched` 这一状态推进输入。当前 mock adapter 是在 `dispatch-run!` 内写入 dispatched 事件，scheduler 随后只是持久化 handle 并应用事件流；Codex adapter 也必须满足这条契约，否则 run 会停在 `leased`。

此外，当前 definitions 层也已有明确基线。`runtime-profile/codex-worker` 已声明：

- `:runtime-profile/adapter-id :runtime.adapter/codex`
- `:runtime-profile/codex-home-root "var/codex-home"`
- `:runtime-profile/allowed-mcp-servers`
- `:runtime-profile/web-search-enabled?`
- `:runtime-profile/worker-prompt-path`
- `:runtime-profile/helper-script-path`
- `:runtime-profile/worker-timeout-seconds`
- `:runtime-profile/heartbeat-interval-seconds`
- `:runtime-profile/env-allowlist`

Phase 3 不应忽略这份现有配置再去 shell 脚本里硬编码第二份等价参数。

## Milestones

### Milestone 1: 完成现有 Codex runtime profile 的真实装配

这一小里程碑结束时，`runtime-profile/codex-worker`、项目级 `CODEX_HOME` 模板、workdir snapshot 和 CLI 入口已形成完整的可运行边界。

### Milestone 2: 实现外部执行、helper 与 poller 协作

这一小里程碑结束时，Codex worker 可通过 helper 幂等回写 heartbeat、progress、worker-exit、artifact-ready；调度器也可通过 `poll-run!` 基于 durable handle 做兜底恢复。

### Milestone 3: 跑通显式 opt-in 的 smoke path

这一小里程碑结束时，在具备 `codex` 命令和有效 provider 配置的机器上，可显式运行一条最小任务并看到控制面按预期收敛；在环境不完整时，也会得到明确的不可运行原因。

## Plan of Work

先从 definitions 和 validation 边界开始复核，但原则是“补齐已有 profile，而不是再造一份”。如果需要增加 profile 字段，应在 `runtime-profiles.edn` 中显式扩展并 bump version，同时同步更新 definitions validation 与测试。

然后实现项目级 `CODEX_HOME` 模板安装。当前 `init` 已保证目录存在，Phase 3 需要补的是模板、配置和 helper 依赖文件，而不是重复发明目录初始化命令。该安装逻辑应可重复执行，并默认不覆盖用户手动改动。当前仓库里还没有 `script/install_codex_home.bb` 或等价入口，因此这部分应被视为本阶段新增交付，而不是现成能力。

接着实现 Codex adapter 本体。应优先复用当前 `scheduler.runtime` 的 context 与 handle persistence，沿 mock runtime 的 workdir 文件契约写出 run workdir，并补齐这些文件的真实语义；同时让 `dispatch-run!` 返回可被当前 `persist-dispatch-result!` 直接持久化的 handle map，并负责产出 `:run.event/dispatched` 这一状态推进输入。若 handle 过大或需要文件承载，应让 run handle 指向 `var/runs/<run-id>/process.json` 之类的持久化文件，而不是只保存在内存。当前 `meta-flow.runtime.registry` 还不认识 `:runtime.adapter/codex`，因此 adapter 本体与 registry 接线应被视为同一批落地工作。

之后实现 helper / poller 分工。helper 负责主动事件，poller 负责兜底恢复；二者必须使用不同前缀的 idempotency key，并在语义上避免重复吞同一物理事实。这里要明确以当前代码事实为起点：`script/worker_api.bb` 目前仍是 placeholder，所以 helper 的事件协议、参数界面和回写路径都属于 Phase 3 交付，而不是只做少量补丁。

最后实现 smoke path 与测试。默认 `bb check` 不应被 Codex 环境依赖污染；Codex smoke 必须显式 opt-in，并在 `codex` 缺失、provider 凭证缺失或 profile 配置不完整时给出明确报错或 skip 原因。

## Concrete Steps

所有命令都在 `/Users/mike/projs/main/meta-flow` 运行。

先补齐项目级 Codex runtime 模板安装，例如：

    clojure -M -m meta-flow.main runtime init-codex-home

期望输出：

    Ensured project CODEX_HOME at var/codex-home
    Installed runtime templates for codex worker

然后实现显式 smoke 入口：

    META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main demo codex-smoke

在环境完整时，期望输出类似：

    Enqueued task 91ab...
    Created run 2ef0... attempt 1
    Dispatched codex worker with :runtime-profile/codex-worker
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

一，Codex worker 的所有可观察行为都继续通过既有 control plane 表达：至少 `dispatched`、heartbeat、progress、artifact-ready、worker-exit 都以既有事件语义出现，并经过统一 ingestion API。

二，worker 失败、退出或中途无响应时，宿主控制面仍然可以靠 SQLite、持久化 execution handle 和文件系统状态恢复，不依赖宿主进程内存或会话记忆。

三，项目级 `CODEX_HOME` 与用户默认 `~/.codex` 隔离；删除 `var/codex-home/` 只影响后续 runtime 启动，不破坏既有 SQLite 权威状态。

四，运行时能力边界必须可验证，而不是只在 definitions 中声明。至少要证明：worker 进程使用的是 `var/codex-home/`；只继承 runtime profile allowlist 中的环境变量；只启用 runtime profile 允许的 MCP 集；Web search 开关与 profile 一致。

五，helper 与 poller 的职责边界必须可验证。至少要证明：helper 已写入的 `artifact-ready` 或 `worker-exit` 不会被 poller 再次吸收成重复事件；当 helper 缺失时，poller 仍可用不同前缀的 idempotency key 兜底生成一次事件。

六，Codex smoke 必须显式 opt-in，不影响默认测试和 `bb check`。

七，默认 gate 仍需保持通过：

    bb fmt:check
    bb lint
    bb test
    bb coverage
    bb check

八，Codex 专用验证应额外通过显式 smoke 命令或等价 opt-in 测试：

    META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main demo codex-smoke

## Idempotence and Recovery

`runtime init-codex-home` 必须幂等。重复执行只允许补齐缺失模板，不允许无提示覆盖使用者手动修改过的关键文件；如需覆盖，必须通过显式 flag。

worker helper 的事件回写必须幂等。即使 Codex worker 因 shell 波动、wrapper 重试或用户中断后重启而重复提交同一 heartbeat 或 artifact-ready，也不得造成重复事件或错误状态迁移。

如果 Codex worker 已退出但 artifact 已写完，调度器仍应能在后续 `scheduler once` 中通过 durable handle、polling 和 event ingestion 收敛到正确 run state。

## Artifacts and Notes

本阶段最重要的新增文件预计应包括当前结构下的这些文件或同层细分模块：

    src/meta_flow/runtime/codex.clj
    src/meta_flow/runtime/registry.clj
    src/meta_flow/scheduler/runtime.clj
    src/meta_flow/cli.clj
    resources/meta_flow/defs/runtime-profiles.edn
    resources/meta_flow/prompts/worker.md
    script/worker_api.bb
    script/install_codex_home.bb
    test/meta_flow/runtime/codex_smoke_test.clj

如新增实现逼近文件长度治理阈值，应优先在 `src/meta_flow/runtime/codex/` 或 `test/meta_flow/runtime/` 下继续按职责拆分，而不是把 process management、helper invocation、polling 和 env assembly 全塞进 `runtime/codex.clj`。当前仓库还没有这些路径，因此应把目录拆分视为本阶段首选扩展方式，而不是继续加宽顶层 `runtime/` 目录。

run workdir 在本阶段至少应延续当前已有文件契约，并把其中当前仍是占位内容的文件补齐为真实 worker 输入：

    var/runs/<run-id>/task.edn
    var/runs/<run-id>/run.edn
    var/runs/<run-id>/definitions.edn
    var/runs/<run-id>/runtime-profile.edn
    var/runs/<run-id>/artifact-contract.edn
    var/runs/<run-id>/process.json

## Interfaces and Dependencies

本阶段仍然使用当前已批准的依赖集合，不增加新的数据库或异步框架。

`RuntimeAdapter` 的 Codex 实现必须满足当前协议：

    (adapter-id [this])                 ;; => :runtime.adapter/codex
    (prepare-run! [this ctx task run])  ;; 写出 workdir 和 snapshot
    (dispatch-run! [this ctx task run]) ;; 启动 codex exec
    (poll-run! [this ctx run now])      ;; 吸收外部进程与文件系统观察
    (cancel-run! [this ctx run reason]) ;; 尝试中止外部执行

`dispatch-run!` 必须返回可被当前 `scheduler.runtime/persist-dispatch-result!` 直接持久化的 execution handle，并负责提供把 run 从 `leased` 推进到 `dispatched` 的事件输入。`poll-run!` 不能直接篡改 run state；它只能基于 handle 生成 event intent 或外部观察，由宿主控制面继续推进状态机。`cancel-run!` 同样必须只依赖持久化 handle，而不依赖宿主进程内存中的临时对象。

## Revision Note

2026-04-01：创建 Phase 3 subplan。

2026-04-02：将文档调整为“沿当前 runtime 基线接入 Codex adapter”的版本，明确复用现有 runtime protocol、mock snapshot/handle 结构、babashka gate 与 lint / coverage 治理，而不是假定一套尚未存在的新 runtime 架构。

2026-04-02：按当前代码库重新收紧 Phase 3 起点定义，明确仓库已达到 “Phase 3 ready but not implemented” 状态；同时把 placeholder helper/prompt、缺失的 registry wiring 与 CLI 入口重新标记为本阶段交付，而不是误记为既有基线。
