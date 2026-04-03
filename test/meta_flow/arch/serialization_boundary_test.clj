(ns meta-flow.arch.serialization-boundary-test
  "治理意图：scheduler/ 和 control/ 层不应直接比较数据库行中的序列化 keyword 字符串。
   所有数据库行应经过 store.sqlite.shared 的实体转换函数处理后，使用 keyword 比较。
   拦截问题：ISSUE-04"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def governed-roots
  ["src/meta_flow/scheduler"
   "src/meta_flow/control"])

(def serialized-keyword-pattern
  ;; 匹配形如 "\":keyword.ns/value\"" 的字符串字面量
  ;; 这表明代码在直接比较数据库中的序列化 keyword 文本
  #"\":[a-z][a-z0-9_-]*\.[a-z][a-z0-9_-]*/[a-z][a-z0-9_-]*\"")

(defn clojure-source? [^java.io.File f]
  (and (.isFile f) (str/ends-with? (.getName f) ".clj")))

(defn scan-violations [roots]
  (for [root roots
        :let [dir (io/file root)]
        :when (.exists dir)
        ^java.io.File f (file-seq dir)
        :when (clojure-source? f)
        :let [path (.getPath f)
              lines (str/split-lines (slurp f))]
        [line-num line] (map-indexed vector lines)
        :let [matches (re-seq serialized-keyword-pattern line)]
        :when (seq matches)]
    {:path path
     :line (inc line-num)
     :content (str/trim line)
     :matches matches}))

(deftest no-serialized-keyword-string-comparisons
  (let [violations (scan-violations governed-roots)]
    (is (empty? violations)
        (str "以下代码直接比较了数据库行中的序列化 keyword 字符串。\n"
             "修复方式：使用 store.sqlite.shared 的实体转换函数将行转为实体后，用 keyword 比较。\n\n"
             (str/join "\n" (map (fn [{:keys [path line content]}]
                                   (str "  " path ":" line " → " content))
                                 violations))))))
