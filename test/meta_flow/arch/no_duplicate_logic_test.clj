(ns meta-flow.arch.no-duplicate-logic-test
  "治理意图：核心判定逻辑应有唯一实现点。多处需要时应通过函数引用共享，
   而非复制逻辑。重复实现在维护时容易遗漏导致行为不一致。
   纯委托函数（函数体仅调用另一个同名函数）不视为重复。

   治理范围：scheduler/、control/、store/ 三个核心层。
   排除 runtime/：该目录包含 RuntimeAdapter 协议的多份实现（mock、codex），
   协议方法名重复是设计要求，不是质量问题。
   排除 lint/、cli/ 等辅助目录：其函数与业务逻辑无关。

   拦截问题：ISSUE-09"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def governed-roots
  ["src/meta_flow/scheduler"
   "src/meta_flow/control"
   "src/meta_flow/store"])

(def allowed-duplicates
  ;; 同名但语义独立的函数（附理由）。
  ;; 所有条目必须附理由，添加时接受 code review。
  #{"create-run!"}) ;; scheduler 层编排（claim+dispatch+stream）vs store 层持久化，职责不同      ;; mock/events 和 scheduler/state 语义不同

(defn clojure-source? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".clj")))

(def top-level-form-prefixes
  ;; 任何顶级 form 开头都应终止前一个 defn 的函数体收集
  #{"(defn " "(defn- " "(defrecord " "(defprotocol " "(defmulti " "(defmethod "
    "(def " "(defonce " "(defmacro " "(ns "})

(defn top-level-form? [trimmed-line]
  (some #(str/starts-with? trimmed-line %) top-level-form-prefixes))

(defn defn-form? [trimmed-line]
  (or (str/starts-with? trimmed-line "(defn ")
      (str/starts-with? trimmed-line "(defn- ")))

(defn extract-functions
  "提取文件中所有 defn/defn- 定义，包含函数名、位置和函数体。
   在遇到任何其他顶级 form 时终止当前函数体的收集。"
  [path content]
  (let [lines (str/split-lines content)]
    (loop [remaining (map-indexed vector lines)
           current nil
           result []]
      (if-let [[line-num line] (first remaining)]
        (let [trimmed (str/trim line)]
          (cond
            ;; 遇到 defn/defn- 开头：保存前一个，开始新的
            (defn-form? trimmed)
            (let [fname (second (str/split trimmed #"\s+"))
                  saved (when current
                          (assoc current :body (str/join "\n" (:lines current))))]
              (recur (rest remaining)
                     {:path path :function-name fname :line (inc line-num) :lines [line]}
                     (if saved (conj result (dissoc saved :lines)) result)))

            ;; 遇到其他顶级 form：保存前一个，清空 current
            (and current (top-level-form? trimmed))
            (let [saved (assoc current :body (str/join "\n" (:lines current)))]
              (recur (rest remaining)
                     nil
                     (conj result (dissoc saved :lines))))

            ;; 普通行：追加到当前函数体
            :else
            (recur (rest remaining)
                   (when current (update current :lines conj line))
                   result)))
        (if current
          (conj result (-> current
                           (assoc :body (str/join "\n" (:lines current)))
                           (dissoc :lines)))
          result)))))

(defn delegation-only?
  "函数体是否仅仅是调用另一个同名函数（纯委托/facade 模式）。
   例如: (defn foo [x] (bar/foo x)) 是纯委托。
   取函数定义后紧接的几行（到下一个顶级 form 或文件末尾），
   检测是否包含对同名函数的限定调用。"
  [{:keys [function-name body]}]
  (let [;; 只取 defn 行之后、到第一个空行或闭合括号的短函数体
        all-lines (str/split-lines body)
        ;; 短函数（<= 6 行）中包含 ns/function-name 调用即视为委托
        short-body? (<= (count all-lines) 6)
        has-qualified-call? (re-find (re-pattern (str "[a-z][a-z0-9.-]*/" (java.util.regex.Pattern/quote function-name)))
                                     body)]
    (and short-body? (some? has-qualified-call?))))

(defn scan-all-duplicates [roots]
  (let [all-defs (for [root roots
                       :let [dir (io/file root)]
                       :when (.exists dir)
                       ^java.io.File f (file-seq dir)
                       :when (clojure-source? f)
                       :let [path (.getPath f)
                             content (slurp f)]
                       func (extract-functions path content)]
                   func)
        ;; 按函数名分组
        grouped (group-by :function-name all-defs)]
    (->> grouped
         ;; 只关注出现 >1 次的
         (filter (fn [[_ defs]] (> (count defs) 1)))
         ;; 排除白名单
         (remove (fn [[fname _]] (contains? allowed-duplicates fname)))
         ;; 排除：所有"重复"中至少 n-1 个是纯委托
         (remove (fn [[_ defs]]
                   (let [non-delegations (remove delegation-only? defs)]
                     (<= (count non-delegations) 1))))
         (into {}))))

(deftest no-unexpected-duplicate-functions
  (let [duplicates (scan-all-duplicates governed-roots)]
    (is (empty? duplicates)
        (str "以下函数名在多个命名空间中有独立实现（非纯委托），可能存在逻辑重复。\n"
             "修复方式：合并为唯一实现并通过 require 共享引用。\n"
             "如果同名但语义不同，添加到 allowed-duplicates 并附理由。\n\n"
             (str/join "\n" (map (fn [[fname defs]]
                                   (str "  " fname " 有 " (count defs) " 份独立实现:\n"
                                        (str/join "\n" (map (fn [{:keys [path line]}]
                                                              (str "    " path ":" line))
                                                            defs))))
                                 duplicates))))))
