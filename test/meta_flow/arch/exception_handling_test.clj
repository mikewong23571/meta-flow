(ns meta-flow.arch.exception-handling-test
  "治理意图：异常是否可重试应通过异常类型（class）或 SQLState 判断，
   而非消息文本子串。消息文本是 driver 实现细节，跨版本不稳定。
   拦截问题：ISSUE-05"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def governed-roots
  ["src/meta_flow"])

(defn clojure-source? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".clj")))

(defn extract-functions
  "将源码按 top-level (defn 拆分为 [{:name :body :start-line} ...]"
  [content]
  (let [lines (str/split-lines content)
        indexed (map-indexed vector lines)]
    (loop [remaining indexed
           current nil
           result []]
      (if-let [[line-num line] (first remaining)]
        (let [trimmed (str/trim line)]
          (if (or (str/starts-with? trimmed "(defn ")
                  (str/starts-with? trimmed "(defn- "))
            (let [fname (second (str/split trimmed #"\s+"))
                  saved (when current
                          (assoc current :body (str/join "\n" (:lines current))))]
              (recur (rest remaining)
                     {:name fname :start-line (inc line-num) :lines [line]}
                     (if saved (conj result (dissoc saved :lines)) result)))
            (recur (rest remaining)
                   (when current (update current :lines conj line))
                   result)))
        (if current
          (conj result (-> current
                           (assoc :body (str/join "\n" (:lines current)))
                           (dissoc :lines)))
          result)))))

(defn function-uses-message-matching?
  "检测单个函数体内是否同时使用 .getMessage 和 str/includes? 进行异常分支判断"
  [{:keys [body]}]
  (and (str/includes? body ".getMessage")
       (or (str/includes? body "str/includes?")
           (str/includes? body "re-find"))))

(defn scan-violations [roots]
  (for [root roots
        :let [dir (io/file root)]
        :when (.exists dir)
        ^java.io.File f (file-seq dir)
        :when (clojure-source? f)
        :let [path (.getPath f)
              content (slurp f)
              functions (extract-functions content)]
        func functions
        :when (function-uses-message-matching? func)]
    {:path path
     :line (:start-line func)
     :function-name (:name func)}))

(deftest no-exception-message-string-matching
  (let [violations (scan-violations governed-roots)]
    (is (empty? violations)
        (str "以下函数通过异常消息子串判断异常类型，这依赖 driver 实现细节。\n"
             "修复方式：使用异常类型（class）、SQLState 或 error code 判断。\n\n"
             (str/join "\n" (map (fn [{:keys [path line function-name]}]
                                   (str "  " path ":" line " " function-name))
                                 violations))))))
