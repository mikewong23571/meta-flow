# Meta-Flow 代码审查问题清单

> 审查日期：2026-04-03
> 基于源码版本：master（d29529c）

---

## 问题分类说明

### 代码问题分类

| 分类 | 含义 |
|------|------|
| **数据一致性** | 缺少事务保护或原子操作，可能导致数据库状态不一致 |
| **脆弱依赖** | 依赖字符串格式、错误消息文本等不稳定契约，版本升级后可能静默失效 |
| **配置硬编码** | 应由定义文件或策略驱动的值被硬编码在源码中 |
| **防御性缺失** | 缺少输入校验、nil 保护或边界检查，异常时错误信息不指向根因 |
| **代码重复** | 同一逻辑存在多份实现，修改时容易遗漏 |

### 可测试性问题分类

| 分类 | 含义 |
|------|------|
| **依赖注入不完整** | 组件的依赖通过硬编码创建而非参数传入，测试中需 `with-redefs` 绕行 |
| **测试基础设施缺失** | 缺少轻量级替代实现（如内存 store），测试被迫依赖重量级基础设施 |
| **状态管理** | 内部缓存或全局状态无法在测试中重置或控制 |
| **并发安全** | 测试隔离机制在并行或多线程场景下失效 |

---

## 问题列表

### ISSUE-01：assess-run! 多步写入缺少事务保护

- **分类**：数据一致性
- **严重度**：高
- **位置**：`scheduler/validation.clj:15-62`

**问题原因**

`assess-run!` 依次执行三个写操作：`record-assessment!`、`record-disposition!`、`emit-event!` + `apply-event-stream!`。这些操作各自独立提交，没有被包裹在同一个事务中。

**问题表现**

如果 assessment 写入成功，但后续 disposition 写入或事件发射失败（如进程崩溃、数据库锁超时），数据库将处于不一致状态——assessment 记录存在但 FSM 未推进。下次调度循环中 `existing-assessment` 不为 nil，会跳过验证逻辑直接使用旧 assessment，但 disposition 和事件仍然缺失。此时 run 停留在 `awaiting-validation` 状态无法推进，需要人工介入。

---

### ISSUE-02：SQL migration 语句按分号分割不安全

- **分类**：脆弱依赖
- **严重度**：高
- **位置**：`db.clj:135`

**问题原因**

```clojure
(str/split (slurp resource) #";\s*(?:\r?\n|$)")
```

使用正则按 `;` 分割 SQL 文件为独立语句。该分割不具备 SQL 语法感知能力，无法区分语句终止符与字符串字面量或注释中的分号。

**问题表现**

如果 migration SQL 中包含字符串字面量带分号（如 `INSERT INTO config VALUES ('key', 'a;b')`）或注释中带分号，会被错误拆分为多条不完整的 SQL 语句，执行时抛出语法错误，导致数据库初始化失败。当前 migration 文件中尚未出现此情况，但随着 migration 数量增加风险升高。

---

### ISSUE-03：migration 执行无事务包裹

- **分类**：数据一致性
- **严重度**：高
- **位置**：`db.clj:157-164`

**问题原因**

`apply-pending-migrations!` 逐条执行 migration 中的 SQL 语句，每条语句独立提交。migration 完成后才在 `schema_migrations` 表中记录 `migration_id`（记录逻辑在调用侧）。

**问题表现**

如果一条 migration 包含多条语句（如先 `CREATE TABLE` 再 `CREATE INDEX`），第一条成功但第二条失败时，数据库 schema 处于半成品状态。由于 `migration_id` 未被记录，下次启动会重试整个 migration，但因为表已存在而再次失败（`CREATE TABLE` 报 "table already exists"），导致数据库不可自动恢复，需要手动修复。

---

### ISSUE-04：Lease 状态使用字符串字面量比较

- **分类**：脆弱依赖
- **严重度**：中
- **位置**：`scheduler/runtime.clj:111,118`

**问题原因**

```clojure
(= ":lease.state/active" (:state lease-row))
```

直接与 SQLite 中存储的 keyword 文本形式比较。该文本形式由 `db/keyword-text` 序列化产生，包含前导冒号。比较方式绕过了反序列化层，在序列化格式与业务逻辑之间建立了隐式耦合。

**问题表现**

如果 `keyword-text` 的序列化规则变化（如去掉前导 `:`、改用 namespace 分隔符），或者 SQL 查询返回的列经过了其他转换，比较将静默失败——`expired-lease?` 永远返回 false，`active-lease?` 永远返回 false。表现为过期 lease 不被回收、心跳超时不被检测，任务卡在 `running` 状态无法恢复。

---

### ISSUE-05：事件重试判断依赖错误消息子串

- **分类**：脆弱依赖
- **严重度**：中
- **位置**：`store/sqlite/run/events.clj:48-53`

**问题原因**

```clojure
(str/includes? message "database is locked")
(str/includes? message "busy")
(str/includes? message "run_events.run_id, run_events.event_seq")
```

通过检查异常消息中是否包含特定子串来判断是否为可重试异常。这些子串是 SQLite JDBC driver 当前版本的实现细节，不属于稳定 API 契约。

**问题表现**

- 如果 JDBC driver 升级后错误消息措辞变化（如 `"database is locked"` 变为 `"database table locked"`），本应重试的异常会被当作不可恢复错误抛出，导致事件丢失。
- 如果新版本的错误消息中碰巧包含 `"busy"` 子串但含义不同，不该重试的异常会被反复重试，浪费资源。

---

### ISSUE-06：task-policy / collection-policy 返回 nil 无保护

- **分类**：防御性缺失
- **严重度**：中
- **位置**：`scheduler/shared.clj:46-60`

**问题原因**

`task-policy` 和 `collection-policy` 从 `DefinitionRepository` 查找 resource-policy，如果 task 的 `resource-policy-ref` 指向一个不存在的 policy（版本不匹配或 id 拼写错误），`find-resource-policy` 返回 nil，函数不做任何检查直接返回 nil。

**问题表现**

下游 `task-heartbeat-timeout-seconds` 对 nil 取 `:resource-policy/heartbeat-timeout-seconds` 得到 nil，传入 `heartbeat-timed-out?` 后 `(long nil)` 抛出 `NullPointerException`。异常堆栈指向 `long` 类型转换，完全不提示真正原因是 policy 未找到。排查时需要逆向追溯整条调用链才能定位到定义引用错误。

---

### ISSUE-07：Lease 时长硬编码 30 分钟

- **分类**：配置硬编码
- **严重度**：中
- **位置**：`scheduler/shared.clj:14-18`

**问题原因**

```clojure
(defn lease-expires-at
  [now-value]
  (-> (java.time.Instant/parse now-value)
      (.plusSeconds 1800)
      str))
```

Lease 过期时间固定为当前时间 + 1800 秒（30 分钟）。系统已经在 `resource-policy` 中定义了 `heartbeat-timeout-seconds`（每种任务类型可不同），但 lease 时长没有走同样的配置路径。

**问题表现**

- 对于长时间运行的任务（如 extension-guide 中定义的 code-audit，worker-timeout 为 2400s），lease 在 30 分钟后过期，但任务可能仍在正常执行。调度器会将其判定为过期 lease 并触发恢复流程，中断正在运行的任务。
- 对于短任务（如预期 60 秒完成），lease 持有 30 分钟过长，任务异常退出后需等待 lease 过期才能被回收和重试，延迟了故障恢复。

---

### ISSUE-08：ensure-collection-state! 硬编码 policy 版本号

- **分类**：配置硬编码
- **严重度**：低
- **位置**：`scheduler/shared.clj:34-36`

**问题原因**

```clojure
(defs.protocol/find-resource-policy defs-repo :resource-policy/default 3)
```

默认 collection 的 resource-policy 版本号 `3` 硬编码在源码中。当 `resource-policies.edn` 中 `:resource-policy/default` 升级到新版本时，此处不会自动跟随。

**问题表现**

升级 resource-policy 版本后，需要同步修改源码中的版本号并重新部署。如果遗漏，新创建的 collection 仍使用旧版策略，与 EDN 中的最新定义不一致。问题不会报错，只会表现为调度行为与预期不符（如并发数、重试次数与新策略不同）。

---

### ISSUE-09：heartbeat-timed-out? 存在两份独立实现

- **分类**：代码重复
- **严重度**：低
- **位置**：`scheduler/step.clj:40-55`、`scheduler/runtime.clj:91-106`

**问题原因**

心跳超时判定逻辑在两个文件中各有一份实现。`step.clj` 版本接收 enriched run 对象（含 `:run/last-progress-at`），`runtime.clj` 版本接收 `(run, progress-at, now)` 三个独立参数。两者核心逻辑相同但签名不同，没有共享实现。

**问题表现**

修改超时判定规则时（如增加宽限期、调整状态过滤条件），需要同时修改两处。如果只改了一处，`recover-heartbeat-timeouts!`（使用 step.clj 版本）和 `current-timeout-context`（使用 runtime.clj 版本）的判定结果会不一致——一个认为超时、另一个认为未超时，导致超时恢复流程中断或重复触发。

---

### ISSUE-10：artifact 验证存在路径穿越风险

- **分类**：防御性缺失
- **严重度**：低
- **位置**：`service/validation.clj:4-6`

**问题原因**

```clojure
(defn artifact-path
  [contract-root required-path]
  (io/file contract-root required-path))
```

`required-path` 直接来自 `artifact-contracts.edn` 定义中的 `:artifact-contract/required-paths`，未校验是否包含 `..` 或绝对路径。`io/file` 会直接拼接路径。

**问题表现**

如果定义文件中（无论是配置错误还是被恶意修改）包含 `../../../etc/passwd` 这样的路径，验证逻辑会检查 artifact 目录外的文件是否存在。当前影响有限——仅是 `.exists` 检查，不会读取文件内容。但如果未来验证逻辑扩展为读取文件内容做 schema 校验，风险会升级。

---

### ISSUE-11：runtime registry 仅支持 mock 但 schema 定义了 codex

- **分类**：防御性缺失
- **严重度**：低
- **位置**：`runtime/registry.clj:4-9`、`schema.clj:77-101`

**问题原因**

`schema.clj` 中定义了 `:runtime.adapter/codex` 的完整 Malli schema（含 `codex-home-root`、`allowed-mcp-servers` 等字段），EDN 定义验证可以通过。但 `runtime/registry.clj` 的 `case` 只有 `:runtime.adapter/mock` 分支，codex adapter 未注册。

**问题表现**

如果按照 extension-guide 的指引配置了 codex runtime-profile 并入队任务，定义加载阶段不会报错（schema 验证通过），任务成功入队。但在调度器尝试 dispatch 时，`runtime-adapter-for-run` 调用 `registry/runtime-adapter` 抛出 `"Unsupported runtime adapter :runtime.adapter/codex"` 异常。错误延迟到运行时才暴露，且已入队的任务会反复失败直到重试耗尽。应在定义加载阶段校验 adapter-id 是否已注册。

---

### ISSUE-12：process-retryable-failures! 无事务保护

- **分类**：数据一致性
- **严重度**：低
- **位置**：`scheduler/retry.clj:28-51`

**问题原因**

`process-retryable-failures!` 遍历所有 `retryable-failed` 状态的任务，逐个执行 requeue 或 escalate。每个任务的状态转换是独立事务，但整批任务的处理没有原子性保证。

**问题表现**

如果调度器在处理到列表中间时崩溃，部分任务已被 requeue、部分仍为 `retryable-failed`。下次调度周期会继续处理剩余任务，因此最终状态是正确的。但在中间状态下，已 requeue 的任务可能在同一周期内被 dispatch，而剩余任务需等到下一个周期。对于时间敏感的场景（如 SLA 要求），这种延迟可能不可接受。

---

### ISSUE-13：migration-id 提取逻辑重复计算且假设扩展名长度

- **分类**：脆弱依赖
- **严重度**：低
- **位置**：`db.clj:126-128`

**问题原因**

```clojure
(subs (last (str/split resource-path #"/")) 0 (- (count (last (str/split resource-path #"/"))) 4))
```

`(last (str/split resource-path #"/"))` 被调用了两次。通过截掉末尾 4 个字符来去除 `.sql` 扩展名，硬编码假设扩展名恰好为 4 字符。

**问题表现**

- 轻微性能浪费（重复字符串分割）。
- 如果未来出现非 `.sql` 扩展名的 migration 文件（如 `.edn`、`.clj`），截取长度错误会导致 migration-id 不正确，影响已应用 migration 的判重逻辑，可能导致 migration 被重复执行或被跳过。

---

---

## 可测试性设计评估

### 整体评价

系统测试基础设施成熟，共 29 个测试文件，覆盖单元、集成、端到端三个层次。协议驱动的分层设计为可测试性提供了良好基础，但 `scheduler-env` 作为事实上的 DI 容器，未完全开放所有依赖的注入口，导致部分测试场景需要依赖 `with-redefs` 绕行。

### 优势

#### 协议驱动的分层隔离

`StateStore`、`ProjectionReader`、`RuntimeAdapter`、`DefinitionRepository` 四个核心协议边界清晰。Store 和 Reader 都通过 `db-path` 参数注入，测试可以用临时数据库完全隔离。协议的存在使得每一层都可以独立替换实现而不影响上层调用方。

#### 每测试独立数据库

所有 scheduler/store 测试通过 `test-support/temp-system` 或 `sqlite-test-support/test-system` 创建临时目录和独立 SQLite 文件。不存在跨测试状态污染。数据库级别的约束（唯一索引、外键）在测试中同样生效，能捕获生产环境中才会暴露的一致性问题。

#### 测试层次分明

- **纯单元测试**（FSM、schema、lint）：无 DB 依赖，仅测试纯函数逻辑
- **集成测试**：用真实 SQLite + mock runtime adapter，验证完整的状态流转
- **端到端测试**（`demo_test.clj`）：覆盖 happy-path（入队 → 派发 → 执行 → 验证 → 完成）和 retry-path（验证拒绝 → 重试 → 成功）

#### Mock 边界合理

只在系统边界 mock（runtime adapter、文件系统路径），内部逻辑使用真实实现验证。避免了 mock 过度导致测试与实现脱节——测试验证的是真实的 SQLite 行为和真实的 FSM 转换，而非模拟行为。

#### 并发测试覆盖

`sqlite_projection_test.clj` 使用 `CountDownLatch` 测试快照加载的事务一致性；`sqlite_run_events_test.clj` 测试并发事件写入的序号分配和幂等性。这些测试能捕获单线程测试无法暴露的竞态条件。

### 问题

#### TEST-01：scheduler-env 中 defs-repo 不可注入

- **分类**：依赖注入不完整
- **严重度**：中
- **位置**：`scheduler/step.clj:13-19`

**问题原因**

```clojure
(defn scheduler-env [db-path]
  {:store (store.sqlite/sqlite-state-store db-path)
   :defs-repo (defs.loader/filesystem-definition-repository)  ;; 硬编码
   :reader (projection/sqlite-projection-reader db-path)
   :now (shared/now)})
```

`defs-repo` 始终通过 `defs.loader/filesystem-definition-repository` 从默认资源路径加载，无法通过参数传入替代实现。

**问题表现**

测试中如需不同的定义集（如测试 FSM 边界条件、不同 resource-policy 配置、异常定义组合），只能依赖 `with-redefs` 替换函数，或者直接使用默认 EDN 文件。无法构建如"max-attempts 为 1 时的重试行为"或"自定义 FSM 转换规则"等针对定义变体的测试场景。

---

#### TEST-02：runtime adapter registry 硬编码，无法注入测试 adapter

- **分类**：依赖注入不完整
- **严重度**：中
- **位置**：`runtime/registry.clj:4-9`

**问题原因**

```clojure
(defn runtime-adapter [adapter-id]
  (case adapter-id
    :runtime.adapter/mock (mock/mock-runtime)
    (throw (ex-info ...))))
```

通过 `case` 硬编码 adapter 映射，无法在测试中注册自定义 adapter。

**问题表现**

无法注入特定行为的 adapter（如故意慢响应、故意抛异常、返回特定事件序列的 adapter）来测试调度器对不同 runtime 行为的处理。当前测试绕过方式是 `with-redefs` 替换整个 `runtime-adapter` 函数，但这种方式脆弱且不可组合——无法同时替换 adapter 和其他依赖。

---

#### TEST-03：时间（now）注入依赖 with-redefs

- **分类**：依赖注入不完整
- **严重度**：低
- **位置**：`scheduler/step.clj:13-19`、`scheduler/shared.clj:10-12`

**问题原因**

`scheduler-env` 中 `now` 在构建时调用 `shared/now` 求值一次，整个调度步骤内所有阶段共享同一时间戳。这个设计本身是正确的（保证单步内时间一致性），但测试中控制时间的唯一方式是 `with-redefs [shared/now (constantly "2026-...")]`。

**问题表现**

测试心跳超时、lease 过期等时间敏感场景时，需要精确控制 `now` 的值。`with-redefs` 是全局替换，如果测试并行执行，不同测试的时间设定会互相干扰。更好的方式是将 `now` 作为 `scheduler-env` 的可选参数，或通过 clock 协议注入。

---

#### TEST-04：DefinitionRepository 缓存不可控

- **分类**：状态管理
- **严重度**：低
- **位置**：`defs/repository.clj:14-19`

**问题原因**

```clojure
(defn ensure-loaded! [cache resource-base]
  (or @cache
      (let [loaded (build-loaded-definitions resource-base)]
        (reset! cache loaded)
        loaded)))
```

`FilesystemDefinitionRepository` 内嵌 `(atom nil)` 作为缓存。一旦首次加载完成，后续所有 `find-*` 调用都返回缓存结果，无重置或失效机制。

**问题表现**

在同一测试进程中如果需要测试定义变化场景（如版本升级、定义热更新），无法清除缓存。测试只能每次创建新的 repository 实例来规避，增加了 test setup 的复杂度。对于需要在同一测试内多次修改定义的场景（如验证版本演化规则），测试编写成本较高。

---

#### TEST-05：缺少 StateStore 的内存实现

- **分类**：测试基础设施缺失
- **严重度**：中
- **位置**：`store/protocol.clj`（协议定义处）

**问题原因**

`StateStore` 协议目前只有 `SQLiteStateStore` 一个实现。所有涉及 store 的测试都必须创建真实的临时 SQLite 数据库。

**问题表现**

- **性能**：每个测试创建临时目录、初始化数据库、执行 migration，增加了测试执行时间。对于纯业务逻辑的单元测试（如 retry 策略判定、调度容量计算），这些 I/O 开销是不必要的。
- **耦合**：store 层的 SQLite 特有行为（如 busy timeout、WAL 模式）可能影响上层逻辑测试的结果。一个 `InMemoryStateStore` 可以让业务逻辑测试与存储实现完全解耦。
- **速度**：随着测试数量增长，数据库初始化的累积时间会成为 CI 瓶颈。

---

#### TEST-06：validation 逻辑无法通过协议注入替代实现

- **分类**：依赖注入不完整
- **严重度**：低
- **位置**：`scheduler/validation.clj:25`

**问题原因**

```clojure
(outcome (service.validation/assess-required-paths artifact-root contract))
```

`assess-run!` 直接调用 `service.validation/assess-required-paths` 函数，没有通过协议或参数注入验证策略。

**问题表现**

如果要测试不同的验证策略（如 schema 校验通过但路径校验失败、部分文件缺失等复杂场景），需要在文件系统上构造对应的目录结构，或使用 `with-redefs` 替换函数。无法像 runtime adapter 那样通过协议注入不同的验证实现。这与 extension-guide 中提到的"按 validator/type 分派"的扩展方向也不一致。

---

#### TEST-07：mock runtime 的动态变量在并行场景下不安全

- **分类**：并发安全
- **严重度**：低
- **位置**：`runtime/mock/fs.clj`

**问题原因**

```clojure
(def ^:dynamic *artifact-root-dir* "var/artifacts")
(def ^:dynamic *run-root-dir* "var/runs")
```

测试通过 `binding` 设置临时路径：

```clojure
(binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
          runtime.mock.fs/*run-root-dir* runs-dir] ...)
```

Clojure 动态变量是线程绑定的，`binding` 建立的值不会传播到子线程。

**问题表现**

当前调度器是单线程执行，不存在问题。但如果未来引入并行执行（如并行 poll 多个 run、并行 dispatch 多个任务），在子线程中 `*artifact-root-dir*` 会回退到默认值 `"var/artifacts"` 而非测试设定的临时路径。测试会读写到非隔离的目录，导致跨测试污染和不可复现的测试失败。

---

### 可测试性评估汇总

| 维度 | 评价 | 说明 |
|------|------|------|
| 协议分层 | 好 | 四个核心协议边界清晰，可独立替换 |
| 数据库隔离 | 好 | 每测试独立临时 DB，无状态泄漏 |
| Mock 策略 | 好 | 仅在系统边界 mock，内部用真实实现 |
| 并发测试 | 好 | 有 CountDownLatch 覆盖竞态条件 |
| 依赖注入 | **不足** | `scheduler-env` 中 defs-repo 和 registry 硬编码 |
| 时间控制 | **不足** | 依赖 `with-redefs`，非参数化注入 |
| 定义变体测试 | **不足** | 无法轻松构建不同定义集的测试场景 |
| 纯逻辑单元测试 | **不足** | 缺少 InMemoryStateStore，业务逻辑测试绑定 SQLite |
| 验证扩展测试 | **不足** | 无分派机制，新验证器测试需 `with-redefs` |

**核心改进方向**：将 `scheduler-env` 改为接受可选的 overrides map，允许测试注入自定义的 `defs-repo`、`now`、以及未来的 validation service。这一个改动可以解决 TEST-01、TEST-02、TEST-03、TEST-06 四个问题。

---

## 按严重度汇总

### 代码问题

| 严重度 | 编号 | 分类 | 概要 |
|--------|------|------|------|
| 高 | ISSUE-01 | 数据一致性 | assess-run! 多步写入无事务 |
| 高 | ISSUE-02 | 脆弱依赖 | SQL migration 按分号分割不安全 |
| 高 | ISSUE-03 | 数据一致性 | migration 执行无事务包裹 |
| 中 | ISSUE-04 | 脆弱依赖 | lease 状态字符串字面量比较 |
| 中 | ISSUE-05 | 脆弱依赖 | 事件重试判断依赖错误消息子串 |
| 中 | ISSUE-06 | 防御性缺失 | policy 查找返回 nil 无保护 |
| 中 | ISSUE-07 | 配置硬编码 | lease 时长硬编码 30 分钟 |
| 低 | ISSUE-08 | 配置硬编码 | collection 默认 policy 版本硬编码 |
| 低 | ISSUE-09 | 代码重复 | heartbeat-timed-out? 两份实现 |
| 低 | ISSUE-10 | 防御性缺失 | artifact 验证路径穿越风险 |
| 低 | ISSUE-11 | 防御性缺失 | codex adapter 未注册但 schema 已定义 |
| 低 | ISSUE-12 | 数据一致性 | retry 批处理无原子性 |
| 低 | ISSUE-13 | 脆弱依赖 | migration-id 扩展名长度硬编码 |

### 可测试性问题

| 严重度 | 编号 | 分类 | 概要 |
|--------|------|------|------|
| 中 | TEST-01 | 依赖注入不完整 | scheduler-env 中 defs-repo 不可注入 |
| 中 | TEST-02 | 依赖注入不完整 | runtime adapter registry 硬编码 |
| 中 | TEST-05 | 测试基础设施缺失 | 缺少 InMemoryStateStore |
| 低 | TEST-03 | 依赖注入不完整 | 时间（now）注入依赖 with-redefs |
| 低 | TEST-04 | 状态管理 | DefinitionRepository 缓存不可控 |
| 低 | TEST-06 | 依赖注入不完整 | validation 逻辑无法通过协议注入 |
| 低 | TEST-07 | 并发安全 | mock runtime 动态变量不传播到子线程 |

## 按分类汇总

### 代码问题

| 分类 | 问题编号 | 数量 |
|------|----------|------|
| 数据一致性 | ISSUE-01, ISSUE-03, ISSUE-12 | 3 |
| 脆弱依赖 | ISSUE-02, ISSUE-04, ISSUE-05, ISSUE-13 | 4 |
| 配置硬编码 | ISSUE-07, ISSUE-08 | 2 |
| 防御性缺失 | ISSUE-06, ISSUE-10, ISSUE-11 | 3 |
| 代码重复 | ISSUE-09 | 1 |

### 可测试性问题

| 分类 | 问题编号 | 数量 |
|------|----------|------|
| 依赖注入不完整 | TEST-01, TEST-02, TEST-03, TEST-06 | 4 |
| 测试基础设施缺失 | TEST-05 | 1 |
| 状态管理 | TEST-04 | 1 |
| 并发安全 | TEST-07 | 1 |
