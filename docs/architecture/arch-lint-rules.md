# Meta-Flow 架构 Lint 规则手册

> 创建日期：2026-04-03
> 基于源码版本：master（d29529c）
> 前置文档：`docs/architecture/code-review-issues.md`、`docs/architecture/governance-enhancement.md`

---

## 概述

本文档记录 `test/meta_flow/arch/` 下的架构 lint 规则。这些规则通过源码扫描在测试阶段拦截语义级设计问题，补充 clj-kondo（语法级）和 file-length（结构级）无法覆盖的治理盲区。

每条规则均经过实际验证，能精确命中 `code-review-issues.md` 中记录的对应问题，且不产生误报。

### 运行方式

```bash
# 运行全部架构 lint
clojure -M:kaocha \
  --focus meta-flow.arch.serialization-boundary-test \
  --focus meta-flow.arch.exception-handling-test \
  --focus meta-flow.arch.no-magic-numbers-test \
  --focus meta-flow.arch.no-duplicate-logic-test \
  --focus meta-flow.arch.definition-safety-test

# 运行单条规则
clojure -M:kaocha --focus meta-flow.arch.serialization-boundary-test
```

### 设计原则

1. **每条规则声明治理意图** — 解释"为什么"而非只说"不允许"
2. **检测失败消息指向修复路径** — 告知如何修复，而非只报错
3. **支持显式豁免** — 通过 `known-exemptions` 集合或 `allowed-duplicates` 列表标注合理例外，豁免本身接受 code review
4. **零误报优先** — 宁可漏检也不误报，规则经过多轮迭代验证

---

## 规则清单

### ARCH-01：序列化边界隔离

| 属性 | 值 |
|------|-----|
| **文件** | `test/meta_flow/arch/serialization_boundary_test.clj` |
| **拦截问题** | ISSUE-04（lease 状态字符串字面量比较） |
| **治理范围** | `src/meta_flow/scheduler/`、`src/meta_flow/control/` |

#### 治理意图

scheduler 和 control 层不应直接比较数据库行中的序列化 keyword 字符串。所有数据库行应经过 `store.sqlite.shared` 的实体转换函数处理后，使用 keyword 比较。直接操作序列化文本在序列化格式与业务逻辑之间建立隐式耦合，格式变化时静默失效。

#### 检测实现

扫描治理范围内所有 `.clj` 文件的每一行，用正则匹配形如 `":keyword.ns/value"` 的字符串字面量：

```
正则：\":[a-z][a-z0-9_-]*\.[a-z][a-z0-9_-]*/[a-z][a-z0-9_-]*\"
```

该模式匹配以 `":` 开头、包含命名空间分隔符 `.` 和名称分隔符 `/` 的双引号字符串。这是 `db/keyword-text` 序列化 keyword 后的固定形式。

#### 验证结果

```
FAIL  src/meta_flow/scheduler/runtime.clj:111 → (= ":lease.state/active" (:state lease-row))
FAIL  src/meta_flow/scheduler/runtime.clj:118 → (= ":lease.state/active" (:state lease-row))))
```

#### 修复方式

使用 `store.sqlite.shared` 的实体转换函数将行转为实体后，用 keyword 比较。

#### 豁免机制

无。此规则不设豁免——scheduler/control 层不应出现序列化文本比较。

---

### ARCH-02：异常分类不依赖消息文本

| 属性 | 值 |
|------|-----|
| **文件** | `test/meta_flow/arch/exception_handling_test.clj` |
| **拦截问题** | ISSUE-05（事件重试判断依赖错误消息子串） |
| **治理范围** | `src/meta_flow/` |

#### 治理意图

异常是否可重试应通过异常类型（class）或 SQLState 判断，而非消息文本子串。错误消息是 driver 实现细节，跨版本不稳定。JDBC driver 升级后消息措辞变化，本应重试的异常可能被当作不可恢复错误抛出。

#### 检测实现

分两步：

1. **函数级解析**：将源码按 `(defn` / `(defn-` 拆分为独立函数块，包含函数名、起始行号和函数体。解析器在遇到下一个 `defn` 时终止当前函数体的收集。

2. **模式检测**：在每个函数体内检查是否同时出现 `.getMessage` 和 `str/includes?`（或 `re-find`）。只有同一函数体内同时使用两者才视为违规。

初版设计使用文件级检测（同一文件中出现两者即报），会导致误报——一个文件中用 `.getMessage` 做日志、用 `str/includes?` 做无关判断会被错误标记。改为函数级粒度后消除了此类误报。

#### 验证结果

```
FAIL  src/meta_flow/store/sqlite/run/events.clj:48 retryable-event-ingest-exception?
```

#### 修复方式

使用异常类型（class）、SQLState 或 error code 判断可重试性。例如 SQLite JDBC 的 `org.sqlite.SQLiteException` 提供 `getResultCode()` 方法。

#### 豁免机制

无。此规则不设豁免——异常分类不应依赖消息文本。

---

### ARCH-03a：调度器无硬编码时间/容量值

| 属性 | 值 |
|------|-----|
| **文件** | `test/meta_flow/arch/no_magic_numbers_test.clj` |
| **断言** | `no-hardcoded-time-or-capacity-values` |
| **拦截问题** | ISSUE-07（lease 时长硬编码 30 分钟） |
| **治理范围** | `src/meta_flow/scheduler/`（排除 `dev.clj`、`test_support.clj`） |

#### 治理意图

调度器中的时间值（秒数、超时）和容量值应来自 `resource-policy.edn` 或定义文件，不应硬编码在源码中。硬编码掩盖了配置意图，不同任务类型无法使用不同的值，变更时需要改代码而非改配置。

#### 检测实现

扫描治理范围内所有 `.clj` 文件（排除注释行），用正则匹配独立的数字字面量：

```
正则：(?<!\w)(\d{2,})(?!\w)
阈值：解析后 >= 60 的数字视为潜在的时间/容量配置值
```

选择 60 作为阈值的理由：
- 小于 60 的数字通常是索引、计数器、状态码等合理常量
- >= 60 的数字在调度器上下文中几乎总是秒数（1 分钟起步）或容量值

初版设计使用 `([2-9]\d{1,}|[1-9]\d{2,})`（匹配 >= 20 或 >= 100），导致两个问题：漏检 ISSUE-08 的单位数版本号 `3`，同时误报 `step.clj` 中 5 处查询批量上限 `100`。修正后将大数字检测和版本号检测拆为两条独立规则（ARCH-03a 和 ARCH-03b），各自使用适合的检测策略。

#### 验证结果

```
FAIL  src/meta_flow/scheduler/shared.clj:17 数字 ["1800"] → (.plusSeconds 1800)
```

#### 修复方式

将数值移入 `resource-policy.edn`（如新增 `:resource-policy/lease-duration-seconds` 字段），或通过函数参数从 policy 传入。

#### 豁免机制

通过 `known-exemptions` 集合标注合理常量：

```clojure
(def known-exemptions
  #{"step.clj:100"}) ;; 查询批量上限，内部分页常量，非业务配置
```

格式为 `"文件名:行内容关键词"`。添加豁免时需附理由，接受 code review。

---

### ARCH-03b：调度器无硬编码定义版本号

| 属性 | 值 |
|------|-----|
| **文件** | `test/meta_flow/arch/no_magic_numbers_test.clj` |
| **断言** | `no-hardcoded-definition-versions` |
| **拦截问题** | ISSUE-08（collection 默认 policy 版本硬编码） |
| **治理范围** | `src/meta_flow/scheduler/`（排除 `dev.clj`、`test_support.clj`） |

#### 治理意图

`defs.protocol/find-*` 调用中的版本号应由 task/run 的 ref 携带，而非在调度器中硬编码。硬编码版本号意味着升级定义版本后需要同步改代码，遗漏时系统使用旧版策略但不报错。

#### 检测实现

Clojure 代码通常跨行排版，`(defs.protocol/find-resource-policy defs-repo :resource-policy/default 3)` 中的函数名和版本号 `3` 分布在不同行，单行正则无法匹配。

检测策略：

1. 逐行扫描，找到包含 `defs.protocol/find-` 的行
2. 取该行及后续 3 行组成窗口（覆盖跨行调用的参数）
3. 在窗口文本中查找独立数字字面量（`>= 2`，排除 0 和 1）

```
窗口正则：(?<!\w)(\d+)(?!\w)
过滤条件：解析后 >= 2
```

初版设计使用单行正则 `find-\S+\s+\S+\s+:\S+\s+(\d+)`，假设调用在同一行，完全无法匹配跨行代码。滑动窗口方式解决了这个根本问题。

#### 验证结果

```
FAIL  src/meta_flow/scheduler/shared.clj:34 版本号 [3] → (let [default-policy (defs.protocol/find-resource-policy defs-repo
```

#### 修复方式

将版本号提取为 `def` 常量并与 workflow 引用联动，或从 workflow 定义中读取默认版本。

#### 豁免机制

无。`defs.protocol/find-*` 调用中不应出现硬编码版本号。

---

### ARCH-04：重复逻辑检测

| 属性 | 值 |
|------|-----|
| **文件** | `test/meta_flow/arch/no_duplicate_logic_test.clj` |
| **拦截问题** | ISSUE-09（heartbeat-timed-out? 两份实现） |
| **治理范围** | `src/meta_flow/scheduler/`、`src/meta_flow/control/`、`src/meta_flow/store/` |

#### 治理意图

核心判定逻辑应有唯一实现点。多处需要时应通过函数引用共享，而非复制逻辑。重复实现在维护时容易遗漏——修改一处而忘记另一处，导致同一判定在不同代码路径中产生不一致结果。

**治理范围说明**：`src/meta_flow/runtime/` 目录被明确排除。该目录包含 `RuntimeAdapter` 协议的多份实现（mock、codex），协议方法（`prepare-run!`、`dispatch-run!` 等）在不同实现中同名是设计要求，不是质量问题。将 `runtime/` 纳入治理范围只会产生大量误报，掩盖真正的重复问题。

#### 检测实现

分三步：

**步骤 1：函数提取**

解析治理范围内所有 `.clj` 文件，提取 `defn` / `defn-` 定义。解析器在遇到任何顶级 form（`defrecord`、`defprotocol`、`def`、`defonce`、`ns` 等）时终止当前函数体的收集，避免将后续 form 的内容错误归入前一个函数。

```clojure
(def top-level-form-prefixes
  #{"(defn " "(defn- " "(defrecord " "(defprotocol " "(defmulti "
    "(defmethod " "(def " "(defonce " "(defmacro " "(ns "})
```

初版解析器只按 `(defn` 分割，导致 `sqlite.clj` 中 `ingest-run-event-via-connection!` 后面的整个 `defrecord SQLiteStateStore` 都被当作函数体，委托检测因函数体过长而失败。

**步骤 2：委托过滤**

识别纯委托函数（facade 模式）并排除。判定标准：

- 函数体 <= 6 行
- 函数体中包含对同名函数的限定调用（`namespace/function-name`）

```clojure
;; 例：以下是纯委托，不视为重复
(defn find-run-row [connection run-id]
  (run-rows/find-run-row connection run-id))
```

本项目的 store 层和 scheduler 层大量使用此模式（`sqlite/runs.clj` → `sqlite/run/rows.clj`，`scheduler/runtime.clj` → `scheduler/runtime/timeout.clj`）。

**步骤 3：白名单排除**

同名但语义独立的函数通过 `allowed-duplicates` 集合排除：

```clojure
(def allowed-duplicates
  #{"create-run!"}) ;; scheduler 层编排（claim+dispatch+stream）vs store 层持久化，职责不同
```

#### 验证结果

```
FAIL  dispatch-cooldown-until 有 2 份独立实现:
        src/meta_flow/scheduler/dispatch/core.clj:16
        src/meta_flow/control/projection/snapshot.clj:12
FAIL  cooldown-active? 有 2 份独立实现:
        src/meta_flow/scheduler/dispatch/core.clj:10
        src/meta_flow/control/projection/snapshot.clj:16
FAIL  heartbeat-timed-out? 有 2 份独立实现:
        src/meta_flow/scheduler/runtime/timeout.clj:41
        src/meta_flow/scheduler/step.clj:41
```

#### 修复方式

- `dispatch-cooldown-until`、`cooldown-active?`：`dispatch/core.clj` 应直接使用 `projection.snapshot/dispatch-cooldown-until` 和 `projection.snapshot/cooldown-active?`，删除自己的副本。
- `heartbeat-timed-out?`：`step.clj` 中的版本应删除，改为使用 `scheduler.runtime.timeout` 中的实现。

#### 豁免机制

通过 `allowed-duplicates` 集合标注语义独立的同名函数。添加时需附理由。

---

### ARCH-05a：runtime adapter 注册校验

| 属性 | 值 |
|------|-----|
| **文件** | `test/meta_flow/arch/definition_safety_test.clj` |
| **断言** | `all-runtime-profiles-reference-registered-adapters` |
| **拦截问题** | ISSUE-11（codex adapter 未注册但 schema 已定义） |
| **治理范围** | `resources/meta_flow/defs/runtime-profiles.edn` + `src/meta_flow/runtime/registry.clj` |

#### 治理意图

所有 runtime-profile 引用的 `adapter-id` 必须在 `runtime/registry.clj` 中已注册。如果 profile 引用了未注册的 adapter，定义加载和 schema 验证都能通过，任务也能成功入队，但在运行时 dispatch 阶段会抛出异常。错误延迟暴露，且已入队的任务会反复失败直到重试耗尽。

#### 检测实现

分两步：

**步骤 1：从 registry.clj 自动提取已注册 adapter-id**

```clojure
(defn extract-registered-adapter-ids []
  (let [content (slurp (io/file "src/meta_flow/runtime/registry.clj"))
        matches (re-seq #":runtime\.adapter/[a-z][a-z0-9-]*" content)]
    (set (map #(keyword (subs % 1)) matches))))
```

从源码中提取 `:runtime.adapter/xxx` 形式的 keyword。`(subs % 1)` 去掉前导 `:`，再用 `keyword` 转换。

初版设计在测试中硬编码 `#{:runtime.adapter/mock}` 作为已注册列表，导致双重维护——新增 adapter 时需同时更新 `registry.clj` 和测试。修正后从源码自动提取，消除了同步问题。

初版还有 keyword 转换 bug：`(keyword ":runtime.adapter/mock")` 产生 `::runtime.adapter/mock`（双冒号），与 profile 中的 `:runtime.adapter/mock` 不匹配。通过 `(subs s 1)` 去前导冒号修复。

**步骤 2：交叉校验**

加载 `runtime-profiles.edn` 中所有 profile，检查每个 `adapter-id` 是否在已注册集合中。

测试还包含一个自检断言 `(is (seq registered))`，确保提取逻辑本身工作正常。如果 `registry.clj` 的代码结构变化导致正则不再匹配，此断言会提前报错。

#### 验证结果

```
FAIL  :runtime-profile/codex-worker → :runtime.adapter/codex (已注册: #{:runtime.adapter/mock})
PASS  :runtime-profile/mock-worker → :runtime.adapter/mock ✓
```

#### 修复方式

在 `runtime/registry.clj` 中注册 adapter 实现，或修正 profile 的 `adapter-id`。

#### 豁免机制

无。未注册的 adapter-id 必定导致运行时失败，不应豁免。

---

### ARCH-05b：artifact-contract 路径安全校验

| 属性 | 值 |
|------|-----|
| **文件** | `test/meta_flow/arch/definition_safety_test.clj` |
| **断言** | `artifact-contract-paths-are-safe` |
| **拦截问题** | ISSUE-10（artifact 验证路径穿越风险） |
| **治理范围** | `resources/meta_flow/defs/artifact-contracts.edn` |

#### 治理意图

artifact-contract 中的 `required-paths` 和 `optional-paths` 必须是相对路径且不包含目录穿越。`service/validation.clj` 使用 `(io/file contract-root required-path)` 拼接路径，如果 `required-path` 包含 `..` 或以 `/` 开头，验证逻辑会访问 artifact 目录外的文件。

#### 检测实现

加载 `artifact-contracts.edn`，遍历所有 contract 的 `required-paths` 和 `optional-paths`，检查三种危险模式：

| 模式 | 风险 |
|------|------|
| `..` | 目录穿越，可访问 artifact-root 外的文件 |
| `/` 开头 | 绝对路径，不受 artifact-root 限制 |
| `~` 开头 | home 目录展开，不受 artifact-root 限制 |

#### 验证结果

```
PASS  当前定义文件中无穿越路径
```

此规则为预防性规则。当前定义文件干净，但能拦截未来的配置错误。

#### 修复方式

路径必须是相对路径且不包含 `..`。例如 `report.md`、`findings/summary.json`。

#### 豁免机制

无。路径穿越在任何情况下都不应被允许。

---

### ARCH-06：多步 store 写操作事务保护

| 属性 | 值 |
|------|-----|
| **文件** | `test/meta_flow/arch/transaction_boundary_test.clj` |
| **拦截问题** | ISSUE-01（assess-run! 多步写入缺少事务保护） |
| **治理范围** | `src/meta_flow/scheduler/`（排除 `dev.clj`、`test_support.clj`） |

#### 治理意图

scheduler 层中包含两种及以上不同 store 写操作的函数必须使用 `sql/with-transaction` 包裹。写操作之间若缺少事务保护，部分失败后数据库将处于不一致状态（如 assessment 已写入但 disposition 和事件缺失），且下次调度周期可能无法自动恢复（`existing-assessment` 非 nil 会跳过重新验证，run 永久卡在 `awaiting-validation`）。

#### 检测实现

复用 ARCH-04 的函数级解析器（按 `defn`/`defn-` 拆分，遇到任意顶级 form 截断）。

对每个函数体检查两项条件：

1. **写标记数量**：在函数体文本中查找以下写操作标记，统计出现的**种类数**（去重）：

```
store.protocol/record-assessment!      store.protocol/record-disposition!
store.protocol/transition-task!        store.protocol/transition-run!
store.protocol/ingest-run-event!       store.protocol/enqueue-task!
store.protocol/attach-artifact!        store.protocol/upsert-collection-state!
store.protocol/claim-task-for-run!     store.protocol/recover-run-startup-failure!
state/emit-event!                      state/apply-event-stream!
```

2. **事务标记缺失**：函数体中不含 `sql/with-transaction`

两项条件同时满足（写标记种类 >= 2 且无事务）即为违规。

设计选择：统计**种类**而非出现次数——`process-retryable-failures!` 在 `doseq` 中反复调用 `transition-task!`，属于批处理模式（ISSUE-12，最终状态一致），不应触发此规则。

#### 验证结果

```
FAIL  src/meta_flow/scheduler/validation.clj:15 assess-run!
      写操作: store.protocol/record-assessment!, store.protocol/record-disposition!,
              state/emit-event!, state/apply-event-stream!
```

#### 修复方式

将多步写操作移入 `sql/with-transaction`，通过 connection-level API（`*-via-connection!`）执行。参考 `runtime/recover-timeout!` 的实现模式。

#### 豁免机制

通过 `known-exemptions` map 登记函数名和豁免理由：

```clojure
(def known-exemptions
  {"create-run!" "写操作跨越外部 runtime dispatch（side effect）：
                  claim → dispatch-run! → persist-result，
                  dispatch 是进程外副作用，三步无法置于同一 DB 事务。"})
```

`create-run!` 的写操作跨越了 `runtime.protocol/dispatch-run!` 这一进程外调用，前后的写入操作无法置于同一 DB 事务，属于合理例外。添加豁免时必须附理由，接受 code review。

---

## 验证汇总

全部规则在当前代码库上的运行结果：

| 规则 | 断言数 | 结果 | 命中内容 | 对应问题 |
|------|--------|------|---------|---------|
| ARCH-01 | 1 | FAIL | `runtime/timeout.clj:61,68` 两处 `":lease.state/active"` | ISSUE-04 |
| ARCH-02 | 1 | FAIL | `events.clj:48` + `sql.clj:77` 两处消息子串判断 | ISSUE-05 |
| ARCH-03a | 1 | FAIL | `shared.clj:17` `1800` | ISSUE-07 |
| ARCH-03b | 1 | FAIL | `shared.clj:34` `find-resource-policy ... 3` | ISSUE-08 |
| ARCH-04 | 1 | FAIL | `dispatch-cooldown-until`、`cooldown-active?`、`heartbeat-timed-out?` 各两份实现 | ISSUE-09 |
| ARCH-05a | 2 | PASS | codex adapter 已在 `registry.clj` 注册 | ISSUE-11 已修复 |
| ARCH-05b | 1 | PASS | 当前定义文件无穿越路径 | ISSUE-10 防护 |
| ARCH-06 | 1 | FAIL | `validation.clj:15` `assess-run!` 四步写入无事务 | ISSUE-01 |

8 个断言，5 个 FAIL（精确命中未修复问题），2 个 PASS（1 个已修复，1 个预防性通过）。零误报。

---

## 迭代记录

以下记录规则从初版到定稿的关键修正，作为后续新增规则时的设计参考。

| 规则 | 初版问题 | 修正 |
|------|----------|------|
| ARCH-02 | 文件级粒度：同一文件中 `.getMessage` 用于日志 + `str/includes?` 用于无关判断 → 误报 | 改为函数级粒度，解析器按 `defn` 拆分函数体 |
| ARCH-03a | 正则 `[2-9]\d{1,}` 漏检单位数（`3`），同时误报分页常量 `100` | 拆为两条规则：大数字（>= 60）+ 版本号（`find-*` 上下文窗口） |
| ARCH-03b | 单行正则 `find-\S+\s+...\s+(\d+)` 无法匹配跨行的 Clojure 调用 | 改为 4 行滑动窗口扫描 |
| ARCH-04 | 手动维护唯一性清单，只防回归不防新增 | 改为自动扫描全部 `defn` + 委托过滤 + 豁免列表 |
| ARCH-04 | 解析器只按 `defn` 分割，`defrecord` 等 form 被错误归入前一个函数体 | 解析器在所有顶级 form 处断开 |
| ARCH-05a | 测试中硬编码 `#{:runtime.adapter/mock}`，与 `registry.clj` 双重维护 | 从 `registry.clj` 源码自动提取 |
| ARCH-05a | `(keyword ":runtime.adapter/mock")` 产生 `::runtime.adapter/mock` | 改用 `(keyword (subs s 1))` 去前导冒号 |
| ARCH-06 | 初版统计写操作出现次数，`process-retryable-failures!`（doseq 循环）被误报 | 改为统计写标记**种类数**，单种写操作的循环不触发 |
| ARCH-04 | 范围 `src/meta_flow/` 全域：Codex runtime 引入后，协议方法（`prepare-run!`、`dispatch-run!` 等）在 mock/codex 各有实现，产生大量误报（18+ → 3 真实违规） | 治理范围缩减为 `scheduler/`、`control/`、`store/` 三个核心层，排除 `runtime/`（多实现区）；`allowed-duplicates` 清理至仅保留 `create-run!` |
| ARCH-03a | `dispatch/core.clj` 新增 `default-runnable-limit = 100` 被误报为容量配置值 | 加入 `known-exemptions`，理由同 `step.clj:100`：内部分页常量，非业务配置 |
