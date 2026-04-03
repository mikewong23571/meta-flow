# Meta-Flow 仓库治理增强设计

> 设计日期：2026-04-03
> 基于源码版本：master（d29529c）
> 前置文档：`docs/architecture/code-review-issues.md`

---

## 一、当前治理体系现状

### 1.1 现有检查流水线

```
bb check
  ├── fmt:check        cljfmt 格式校验
  ├── lint             clj-kondo 静态分析 + 文件长度/目录宽度治理
  ├── test             kaocha 测试套件
  └── coverage         Cloverage 行覆盖率治理（<88% 警告, <85% 失败）
```

Pre-commit hook 执行完整的 `bb check`，提交前全量拦截。

### 1.2 现有规则清单

| 检查 | 工具 | 阈值 | 治理意图 |
|------|------|------|----------|
| 代码格式 | cljfmt | 任何不一致 → 失败 | 统一代码风格 |
| 静态分析 | clj-kondo | 任何问题 → 失败 | 类型安全、未使用引用等 |
| 文件长度 | file-length.clj | >240 警告, >300 失败 | 单一职责，防止命名空间膨胀 |
| 目录宽度 | file-length.clj | >7 警告, >12 失败 | 层次分明，防止平铺命名空间 |
| 测试覆盖率 | coverage.clj | <88% 警告, <85% 失败 | 行为与可执行检查对齐 |
| 定义验证 | defs/validation.clj | Schema + 引用完整性 | 定义文件结构和交叉引用正确 |

### 1.3 现有体系的盲区

当前治理覆盖了**格式、静态类型、结构度量、测试覆盖**四个维度。但 code-review-issues.md 中发现的 13 个代码问题和 7 个可测试性问题，**没有一个能被现有规则拦截**。原因是现有规则聚焦于代码的"外观"（格式、长度、覆盖率），而问题集中在代码的"语义"（事务边界、序列化耦合、依赖注入完整性）。

---

## 二、问题-规则映射分析

将 code-review-issues.md 中的问题按"什么规则能拦截"分组：

| 问题类别 | 问题编号 | 需要的治理能力 | 现有工具能否覆盖 |
|----------|----------|--------------|----------------|
| 数据一致性 | ISSUE-01, 03, 12 | 事务边界完整性检查 | 否 |
| 脆弱依赖 | ISSUE-04 | 禁止原始行比较序列化值 | clj-kondo 自定义规则可部分覆盖 |
| 脆弱依赖 | ISSUE-05 | 禁止异常消息子串匹配 | clj-kondo 自定义规则可部分覆盖 |
| 脆弱依赖 | ISSUE-02, 13 | SQL/文件解析健壮性 | 否（需架构测试） |
| 配置硬编码 | ISSUE-07, 08 | 禁止调度器中的魔术数字 | clj-kondo 可标记，但语义判断困难 |
| 防御性缺失 | ISSUE-06 | 协议查找后 nil 保护 | clj-kondo 有限支持 |
| 防御性缺失 | ISSUE-11 | 定义验证应校验 adapter-id 已注册 | 可扩展 defs/validation.clj |
| 防御性缺失 | ISSUE-10 | 路径穿越校验 | 需新增架构测试 |
| 代码重复 | ISSUE-09 | 重复逻辑检测 | 否 |
| 依赖注入 | TEST-01~07 | DI 完整性检查 | 需架构测试 |

**结论**：现有工具链无法覆盖这些问题。需要在两个层面增强——**定义验证层**扩展已有能力，**架构测试层**作为新增治理维度。

---

## 三、治理增强设计

### 3.1 新增治理维度总览

在现有四个维度基础上新增两个维度，形成六层治理体系：

```
bb check
  ├── fmt:check            [现有] 格式校验
  ├── lint                 [现有] 静态分析 + 结构度量
  │     └── (扩展) clj-kondo 自定义规则
  ├── defs:validate        [增强] 定义验证 — 补充 adapter 注册校验
  ├── arch                 [新增] 架构测试 — 语义级约束
  ├── test                 [现有] 功能测试
  └── coverage             [现有] 覆盖率治理
```

### 3.2 各维度详细设计

---

#### 维度 A：扩展 clj-kondo 自定义规则

**治理目标**：在静态分析阶段拦截已知的危险模式。

**规则 A1：禁止对数据库行的原始字符串状态比较**

拦截问题：ISSUE-04

```
;; .clj-kondo/config.edn
{:linters
 {:meta-flow/raw-state-string-comparison
  {:level :error
   :message "不要直接比较数据库行的字符串状态值。使用反序列化后的 keyword 比较，或通过 store.sqlite.shared 的实体转换函数处理。"}}}
```

检测模式：在 `scheduler/` 命名空间下，`(= ":xxx.state/yyy" ...)` 形式的字符串字面量比较。

实现方式：clj-kondo hook 或 grep 规则（见维度 B 的 arch 测试替代方案）。

**规则 A2：禁止基于异常消息子串的分支判断**

拦截问题：ISSUE-05

检测模式：`(str/includes? ... .getMessage ...)` 组合使用。

实现方式：由于 clj-kondo 对跨表达式模式识别有限，更适合用架构测试实现。

---

#### 维度 B：新增架构测试（`bb arch`）

**治理目标**：通过可执行的架构约束，在测试阶段拦截语义级问题。架构测试不依赖运行时状态，通过扫描源码 AST 或文本模式判定约束是否满足。

**位置**：`test/meta_flow/arch/` 目录，作为独立测试命名空间。

##### 规则 B1：事务边界完整性

拦截问题：ISSUE-01, ISSUE-03, ISSUE-12

```clojure
;; test/meta_flow/arch/transaction_boundary_test.clj
;;
;; 治理意图：多步 store 写操作（transition + event + assessment 等）
;; 必须在 sql/with-transaction 内执行，防止部分失败导致数据不一致。
```

**检测逻辑**：
1. 扫描 `scheduler/` 下所有 `.clj` 文件
2. 识别包含两个及以上 `store.protocol/` 写操作调用的函数（`transition-task!`、`transition-run!`、`record-assessment!`、`record-disposition!`、`ingest-run-event!`）
3. 断言这些函数体被 `sql/with-transaction` 包裹，或函数自身在调用栈中被事务包裹

**豁免机制**：函数可通过 `^:arch/transaction-exempt` 元数据标注豁免，需附说明。

##### 规则 B2：序列化边界隔离

拦截问题：ISSUE-04

```clojure
;; test/meta_flow/arch/serialization_boundary_test.clj
;;
;; 治理意图：scheduler/ 和 control/ 层不应直接操作 SQL 行的字符串形式。
;; 所有数据库行必须经过 store.sqlite.shared 的实体转换后再使用。
```

**检测逻辑**：
1. 扫描 `scheduler/` 下所有 `.clj` 文件
2. 检测形如 `":keyword.namespace/value"` 的字符串字面量（以 `":` 开头，包含 `/`）
3. 此模式表明直接操作了序列化后的 keyword 文本，应使用反序列化后的 keyword 比较

##### 规则 B3：异常分类不依赖消息文本

拦截问题：ISSUE-05

```clojure
;; test/meta_flow/arch/exception_handling_test.clj
;;
;; 治理意图：异常是否可重试应通过异常类型（class）或 SQLState 判断，
;; 而非消息文本子串。消息文本是 driver 实现细节，跨版本不稳定。
```

**检测逻辑**：
1. 扫描所有 `.clj` 文件
2. 检测 `.getMessage` 与 `str/includes?` 或 `re-find` 在同一函数体中的组合使用
3. 豁免：日志输出中引用 `.getMessage` 不受限

##### 规则 B4：调度器无魔术数字

拦截问题：ISSUE-07, ISSUE-08

```clojure
;; test/meta_flow/arch/no_magic_numbers_test.clj
;;
;; 治理意图：调度器中的时间值、容量值、版本号等应来自定义文件或
;; resource-policy，不应硬编码在源码中。
```

**检测逻辑**：
1. 扫描 `scheduler/` 下所有 `.clj` 文件（排除 `test_support.clj` 和 `dev.clj`）
2. 检测大于 1 的整数字面量（排除常见的 0, 1, -1）
3. 每个检测到的数字需通过 `^:arch/config-constant` 元数据或注释 `; arch:known-constant` 标注

**已知豁免**：`(atom [])` 中的空向量、`(inc idx)` 等惯用法不触发。

##### 规则 B5：协议查找 nil 保护

拦截问题：ISSUE-06

```clojure
;; test/meta_flow/arch/nil_safety_test.clj
;;
;; 治理意图：DefinitionRepository 的 find-* 方法可能返回 nil（定义不存在）。
;; 调用方必须在使用返回值前做 nil 检查或使用 some->，防止 NPE 掩盖配置错误。
```

**检测逻辑**：
1. 扫描 `scheduler/` 下所有 `.clj` 文件
2. 识别 `defs.protocol/find-*` 调用
3. 断言返回值被 `when-let`、`if-let`、`some->`、`some->>` 或显式 `nil?` 包裹
4. 或者函数入口处有 `assert` / `when-not` + `throw` 的 nil 守卫

##### 规则 B6：路径穿越防护

拦截问题：ISSUE-10

```clojure
;; test/meta_flow/arch/path_safety_test.clj
;;
;; 治理意图：所有文件路径拼接（io/file base relative）中的 relative 部分
;; 不应包含 ".." 或绝对路径前缀。定义文件中的路径在加载时校验。
```

**检测逻辑**：作为 `defs/validation.clj` 的扩展而非独立架构测试。在 `validate-definition-links!` 中增加对 `artifact-contract/required-paths` 和 `artifact-contract/optional-paths` 的路径安全校验。

---

#### 维度 C：扩展定义验证（`bb defs:validate`）

**治理目标**：在定义加载阶段尽早暴露配置错误，避免延迟到运行时。

##### 规则 C1：adapter-id 注册校验

拦截问题：ISSUE-11

```clojure
;; defs/validation.clj — validate-runtime-profile! 扩展
;;
;; 当前：仅对 :runtime.adapter/codex 做资源路径校验
;; 增强：校验所有 runtime-profile 的 adapter-id 在已注册列表中
```

实现方式：在 `validate-runtime-profile!` 中增加 adapter-id 白名单校验。白名单从 `runtime/registry.clj` 导出或定义为常量。

```clojure
(def registered-adapter-ids
  #{:runtime.adapter/mock})

(defn validate-runtime-profile! [runtime-profile artifact-contract-index]
  (let [adapter-id (:runtime-profile/adapter-id runtime-profile)]
    (when-not (contains? registered-adapter-ids adapter-id)
      (throw (ex-info (str "Runtime profile references unregistered adapter: " adapter-id)
                      {:runtime-profile/id (:runtime-profile/id runtime-profile)
                       :adapter-id adapter-id
                       :registered registered-adapter-ids}))))
  ;; ... 现有校验逻辑 ...
  )
```

##### 规则 C2：artifact-contract 路径安全校验

拦截问题：ISSUE-10

在 `validate-definition-links!` 中增加：

```clojure
(doseq [contract artifact-contracts]
  (doseq [path (concat (:artifact-contract/required-paths contract)
                        (:artifact-contract/optional-paths contract))]
    (when (or (str/includes? path "..")
              (str/starts-with? path "/"))
      (throw (ex-info "Artifact contract path must not traverse outside artifact root"
                      {:contract-id (:artifact-contract/id contract)
                       :path path})))))
```

##### 规则 C3：resource-policy 版本一致性校验

拦截问题：ISSUE-08

在 `validate-definition-links!` 中增加：对 workflow 引用的 `default-resource-policy-ref` 的版本号，校验其与代码中 `scheduler/shared.clj` 的 `ensure-collection-state!` 硬编码版本一致。

实现方式：将 `ensure-collection-state!` 中的版本号提取为 `def`，定义验证时读取该 `def` 并与 workflow 引用比对。

---

#### 维度 D：重复逻辑检测

拦截问题：ISSUE-09

##### 规则 D1：关键函数唯一性

```clojure
;; test/meta_flow/arch/no_duplicate_logic_test.clj
;;
;; 治理意图：核心判定逻辑（如超时判断、状态检查）应有唯一实现。
;; 多处使用应通过函数引用而非复制逻辑。
```

**检测逻辑**：维护一份"唯一性约束清单"，列出不应存在多份实现的函数名模式。扫描源码检测同名（或高度相似签名）的 `defn-?` 定义。

**初始清单**：
- `heartbeat-timed-out?` — 应仅在一个命名空间中定义
- `expired-lease?` — 应仅在一个命名空间中定义
- `active-lease?` — 应仅在一个命名空间中定义

---

### 3.3 流水线集成

#### 新的 `bb check` 流程

```
bb check
  ├── fmt:check          [现有] 格式校验
  ├── lint               [现有+扩展] clj-kondo + 结构度量
  ├── defs:validate      [增强] 定义验证（含 C1-C3 新规则）
  ├── test               [现有] 功能测试
  ├── arch               [新增] 架构测试
  └── coverage           [现有] 覆盖率治理
```

`bb.edn` 变更：

```edn
arch
{:doc "Architecture constraint tests"
 :requires ([babashka.tasks :refer [shell]])
 :task (shell "clojure -M:kaocha --focus :arch")}

check
{:doc "Run all checks: fmt:check -> lint -> defs:validate -> test -> arch -> coverage"
 :requires ([babashka.tasks :refer [run]])
 :task (do (run 'fmt:check)
           (run 'lint)
           (run 'defs:validate)
           (run 'test)
           (run 'arch)
           (run 'coverage))}
```

`tests.edn` 变更：为架构测试增加独立 test suite，支持 `--focus :arch` 单独运行：

```edn
#kaocha/v1
{:tests [{:id :unit
          :kaocha/test-paths ["test"]
          :kaocha.filter/skip-meta [:arch]}
         {:id :arch
          :kaocha/test-paths ["test"]
          :kaocha.filter/focus-meta [:arch]}]
 :color? true
 :randomize? true}
```

架构测试文件通过 `^:arch` 元数据标记：

```clojure
(ns ^:arch meta-flow.arch.transaction-boundary-test
  (:require [clojure.test :refer [deftest is]]))
```

---

## 四、实施优先级

按"拦截价值 / 实现成本"排序：

### P0 — 立即实施（拦截高严重度问题，实现成本低）

| 规则 | 拦截问题 | 实现方式 | 预估工作量 |
|------|----------|----------|-----------|
| C1 adapter-id 注册校验 | ISSUE-11 | 扩展 `validate-runtime-profile!` | 0.5h |
| C2 路径安全校验 | ISSUE-10 | 扩展 `validate-definition-links!` | 0.5h |
| B2 序列化边界隔离 | ISSUE-04 | grep 检测 `":` 开头的字符串字面量 | 1h |

### P1 — 短期实施（拦截高/中严重度问题）

| 规则 | 拦截问题 | 实现方式 | 预估工作量 |
|------|----------|----------|-----------|
| B1 事务边界完整性 | ISSUE-01, 03, 12 | AST 扫描多步写操作 | 3h |
| B3 异常分类规则 | ISSUE-05 | grep 检测 getMessage + includes 组合 | 1h |
| B4 调度器无魔术数字 | ISSUE-07, 08 | 数字字面量扫描 | 2h |
| D1 关键函数唯一性 | ISSUE-09 | defn 名称重复检测 | 1h |

### P2 — 中期实施（治理体系完善）

| 规则 | 拦截问题 | 实现方式 | 预估工作量 |
|------|----------|----------|-----------|
| B5 协议查找 nil 保护 | ISSUE-06 | AST 分析调用-使用链 | 3h |
| C3 policy 版本一致性 | ISSUE-08 | 跨文件引用校验 | 2h |
| 流水线集成 | — | bb.edn + tests.edn 改造 | 1h |

---

## 五、架构测试设计原则

以下原则指导架构测试的编写，确保规则可维护、可理解：

### 5.1 每条规则必须声明治理意图

```clojure
;; 治理意图：<为什么这条规则存在>
;; 拦截问题：<对应的 ISSUE 编号>
;; 豁免方式：<如何合理绕过>
```

与文件长度治理和覆盖率治理的消息风格保持一致：解释"为什么"而非只说"不允许"。

### 5.2 检测失败消息必须指向修复路径

```clojure
(is (empty? violations)
    (str "以下函数包含多步 store 写操作但未被事务包裹。\n"
         "修复方式：将多步写操作移入 sql/with-transaction 内，"
         "或标注 ^:arch/transaction-exempt 并说明原因。\n"
         (str/join "\n" (map format-violation violations))))
```

### 5.3 支持显式豁免

每条规则都应有豁免机制（元数据标注、注释标记、或白名单文件），避免规则成为无法通过的阻碍。豁免本身作为代码存在，接受 code review。

### 5.4 规则即文档

架构测试本身是"为什么这样设计"的可执行文档。不需要额外维护架构决策记录——测试名称和治理意图注释就是记录。

### 5.5 渐进式引入

新规则先以 `:warning` 级别引入（测试打印警告但不失败），确认无误报后升级为 `:error`（测试失败）。与文件长度治理的 warning/error 双阈值模式一致。

---

## 六、问题拦截覆盖矩阵

实施后，每个已知问题是否能被拦截：

| 问题编号 | 问题概要 | 拦截规则 | 拦截阶段 |
|----------|----------|----------|----------|
| ISSUE-01 | assess-run! 无事务 | B1 事务边界 | arch |
| ISSUE-02 | migration 分号分割 | — (需代码修复，非规则可拦截) | — |
| ISSUE-03 | migration 无事务 | B1 事务边界 | arch |
| ISSUE-04 | lease 字符串比较 | B2 序列化边界 | arch |
| ISSUE-05 | 异常消息子串 | B3 异常分类 | arch |
| ISSUE-06 | policy nil 无保护 | B5 nil 保护 | arch |
| ISSUE-07 | lease 硬编码 30 分钟 | B4 无魔术数字 | arch |
| ISSUE-08 | policy 版本硬编码 | B4 无魔术数字 + C3 版本一致性 | arch + defs:validate |
| ISSUE-09 | heartbeat 重复逻辑 | D1 函数唯一性 | arch |
| ISSUE-10 | 路径穿越 | C2 路径安全 | defs:validate |
| ISSUE-11 | codex 未注册 | C1 adapter 注册 | defs:validate |
| ISSUE-12 | retry 无事务 | B1 事务边界 | arch |
| ISSUE-13 | migration-id 扩展名 | — (需代码修复，非规则可拦截) | — |

**覆盖率**：13 个问题中 11 个可被规则拦截（84.6%）。ISSUE-02 和 ISSUE-13 属于实现缺陷，需直接修复代码，不适合用规则拦截。

---

## 七、与现有治理体系的关系

```
┌─────────────────────────────────────────────────────────┐
│  格式层 (fmt:check)                                      │
│  "代码长什么样" — 缩进、空格、括号对齐                       │
├─────────────────────────────────────────────────────────┤
│  静态分析层 (lint: clj-kondo)                             │
│  "代码有没有明显错误" — 未使用引用、类型不匹配、语法问题        │
├─────────────────────────────────────────────────────────┤
│  结构度量层 (lint: file-length)                           │
│  "代码组织是否合理" — 文件长度、目录宽度                      │
├─────────────────────────────────────────────────────────┤
│  定义验证层 (defs:validate) [增强]                         │
│  "配置是否完整正确" — Schema、引用完整性、adapter 注册、路径安全 │
├─────────────────────────────────────────────────────────┤
│  架构约束层 (arch) [新增]                                  │
│  "代码是否遵循设计契约" — 事务边界、序列化隔离、DI 完整性       │
├─────────────────────────────────────────────────────────┤
│  行为验证层 (test + coverage)                              │
│  "代码是否按预期工作" — 功能正确性、覆盖率                     │
└─────────────────────────────────────────────────────────┘
```

六层从上到下，检查成本递增、反馈延迟递增。新增的两层填补了"配置正确性"和"设计契约"的空白——这正是当前问题集中的区域。
