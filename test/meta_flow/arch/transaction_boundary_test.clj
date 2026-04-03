(ns meta-flow.arch.transaction-boundary-test
  "治理意图：scheduler 层中包含两种及以上不同 store 写操作的函数必须使用
   sql/with-transaction 包裹。写操作之间若缺少事务保护，部分失败后数据库将
   处于不一致状态，且下次调度周期可能无法自动恢复。
   拦截问题：ISSUE-01"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def governed-root "src/meta_flow/scheduler")

(def excluded-files
  #{"dev.clj" "test_support.clj"})

(def write-markers
  "scheduler 层中标志 store 写操作的函数名片段。
   包含 store.protocol/ 直接调用，以及封装了写操作的 state 层代理。
   只包含写操作（名称以 ! 结尾的变更方法），读操作不计入。"
  ["store.protocol/record-assessment!"
   "store.protocol/record-disposition!"
   "store.protocol/transition-task!"
   "store.protocol/transition-run!"
   "store.protocol/ingest-run-event!"
   "store.protocol/enqueue-task!"
   "store.protocol/attach-artifact!"
   "store.protocol/upsert-collection-state!"
   "store.protocol/claim-task-for-run!"
   "store.protocol/recover-run-startup-failure!"
   "state/emit-event!"
   "state/apply-event-stream!"])

(def transaction-marker "sql/with-transaction")

(def known-exemptions
  "函数名 → 豁免理由。豁免本身接受 code review，添加时必须附理由。"
  {"create-run!" (str "写操作跨越外部 runtime dispatch（side effect）："
                      "claim → dispatch-run! → persist-result，"
                      "dispatch 是进程外副作用，三步无法置于同一 DB 事务。")})

;; --- 解析器（复用 ARCH-04 的设计）---

(defn clojure-source? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".clj")))

(defn excluded? [^java.io.File f]
  (contains? excluded-files (.getName f)))

(def top-level-form-prefixes
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
            (defn-form? trimmed)
            (let [fname (second (str/split trimmed #"\s+"))
                  saved (when current
                          (assoc current :body (str/join "\n" (:lines current))))]
              (recur (rest remaining)
                     {:path path :function-name fname :line (inc line-num) :lines [line]}
                     (if saved (conj result (dissoc saved :lines)) result)))

            (and current (top-level-form? trimmed))
            (let [saved (assoc current :body (str/join "\n" (:lines current)))]
              (recur (rest remaining)
                     nil
                     (conj result (dissoc saved :lines))))

            :else
            (recur (rest remaining)
                   (when current (update current :lines conj line))
                   result)))
        (if current
          (conj result (-> current
                           (assoc :body (str/join "\n" (:lines current)))
                           (dissoc :lines)))
          result)))))

;; --- 检测逻辑 ---

(defn present-write-markers
  "返回函数体中出现的所有写标记（去重，保持顺序）。"
  [body]
  (filterv #(str/includes? body %) write-markers))

(defn has-transaction? [body]
  (str/includes? body transaction-marker))

(defn scan-violations [root]
  (let [dir (io/file root)]
    (when (.exists dir)
      (for [^java.io.File f (file-seq dir)
            :when (and (clojure-source? f) (not (excluded? f)))
            :let [path (.getPath f)
                  content (slurp f)]
            {:keys [function-name line body]} (extract-functions path content)
            :let [markers (present-write-markers body)]
            :when (and (>= (count markers) 2)
                       (not (has-transaction? body))
                       (not (contains? known-exemptions function-name)))]
        {:path path
         :line line
         :function-name function-name
         :write-markers markers}))))

;; --- 断言 ---

(deftest multi-write-functions-require-transaction
  (let [violations (scan-violations governed-root)]
    (is (empty? violations)
        (str "以下函数包含多步 store 写操作但未使用 sql/with-transaction 包裹。\n"
             "修复方式：将多步写操作移入 sql/with-transaction，\n"
             "或在 known-exemptions 中登记并说明为何不适合事务包裹。\n\n"
             (str/join "\n"
                       (map (fn [{:keys [path line function-name write-markers]}]
                              (str "  " path ":" line " " function-name "\n"
                                   "    写操作: " (str/join ", " write-markers)))
                            violations))))))
