(ns meta-flow.arch.no-magic-numbers-test
  "治理意图：调度器中的时间值、版本号等应来自定义文件或 resource-policy，
   不应硬编码在源码中。魔术数字掩盖了配置意图，变更时需要改代码而非改配置。
   拦截问题：ISSUE-07, ISSUE-08"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def governed-root "src/meta_flow/scheduler")

(def excluded-files
  #{"dev.clj" "test_support.clj"})

(defn excluded-path?
  [^java.io.File f]
  (let [path (.getPath f)]
    (or (contains? excluded-files (.getName f))
        (str/includes? path "/scheduler/dev/"))))

;; --- 规则 1: 时间/容量相关的大数字 ---

(def large-number-pattern
  #"(?<!\w)(\d{2,})(?!\w)")

(defn significant-number?
  "数字 >= 60 认为是潜在的时间/容量配置值"
  [s]
  (try (>= (Long/parseLong s) 60)
       (catch NumberFormatException _ false)))

;; --- 规则 2: defs.protocol/find-* 调用中硬编码版本号 ---
;; Clojure 代码通常跨行排版，单行正则无法匹配。
;; 改用读取整个文件内容，检测 find-* 调用的上下文中是否有裸数字字面量作为版本参数。
;; 检测策略：在 defs.protocol/find- 出现后的若干行内，查找独立数字字面量。

(defn scan-version-hardcode-violations [root]
  (let [dir (io/file root)]
    (when (.exists dir)
      (for [^java.io.File f (file-seq dir)
            :when (and (.isFile f)
                       (str/ends-with? (.getName f) ".clj")
                       (not (excluded-path? f)))
            :let [path (.getPath f)
                  content (slurp f)
                  lines (str/split-lines content)]
            ;; 查找 defs.protocol/find- 调用行，然后检查后续 3 行内是否有独立数字
            [line-num line] (map-indexed vector lines)
            :when (str/includes? line "defs.protocol/find-")
            ;; 取当前行及后续 3 行组成的窗口
            :let [window (subvec (vec lines)
                                 line-num
                                 (min (count lines) (+ line-num 4)))
                  window-text (str/join " " window)
                  ;; 在窗口内查找独立的数字字面量（非负，非 0/1）
                  nums (->> (re-seq #"(?<!\w)(\d+)(?!\w)" window-text)
                            (map (comp #(Long/parseLong %) second))
                            (filter #(>= % 2))
                            vec)]
            :when (seq nums)]
        {:path path
         :line (inc line-num)
         :content (str/trim line)
         :numbers nums}))))

;; --- 共享工具 ---

(defn clojure-source? [^java.io.File f]
  (and (.isFile f)
       (str/ends-with? (.getName f) ".clj")
       (not (excluded-path? f))))

(defn comment-line? [line]
  (str/starts-with? (str/trim line) ";"))

(def known-exemptions
  ;; 格式: "文件名:关键词" — 已审核的合理常量
  #{"step.clj:100"   ;; 查询批量上限，内部分页常量，非业务配置
    "core.clj:100"   ;; dispatch/core.clj default-runnable-limit，同上，内部分页常量
    "run.clj:2000"}) ;; scheduler/run.clj 轮询间隔 2s，固定的 UX 常量，非业务配置

(defn exempted? [filename content]
  (some (fn [exemption]
          (let [[f kw] (str/split exemption #":")]
            (and (= filename f)
                 (str/includes? content kw))))
        known-exemptions))

(defn scan-large-number-violations [root]
  (let [dir (io/file root)]
    (when (.exists dir)
      (for [^java.io.File f (file-seq dir)
            :when (clojure-source? f)
            :let [path (.getPath f)
                  filename (.getName f)
                  lines (str/split-lines (slurp f))]
            [line-num line] (map-indexed vector lines)
            :when (not (comment-line? line))
            :let [matches (->> (re-seq large-number-pattern line)
                               (map first)
                               (filter significant-number?)
                               vec)]
            :when (seq matches)
            :when (not (exempted? filename (str/trim line)))]
        {:path path
         :line (inc line-num)
         :content (str/trim line)
         :numbers matches
         :kind :large-number}))))

(deftest no-hardcoded-time-or-capacity-values
  (let [violations (scan-large-number-violations governed-root)]
    (is (empty? violations)
        (str "以下调度器代码包含硬编码的时间/容量数字（>= 60），应由 resource-policy 提供。\n"
             "修复方式：将数值移入 resource-policy.edn 或通过函数参数传入。\n"
             "如确为合理常量，添加到 known-exemptions 并附理由。\n\n"
             (str/join "\n" (map (fn [{:keys [path line content numbers]}]
                                   (str "  " path ":" line
                                        " 数字 " numbers " → " content))
                                 violations))))))

(deftest no-hardcoded-definition-versions
  (let [violations (scan-version-hardcode-violations governed-root)]
    (is (empty? violations)
        (str "以下调度器代码在 defs.protocol/find-* 调用中硬编码了定义版本号。\n"
             "版本号应由 task/run 的 ref 携带，而非在调度器中写死。\n\n"
             (str/join "\n" (map (fn [{:keys [path line content numbers]}]
                                   (str "  " path ":" line
                                        " 版本号 " numbers " → " content))
                                 violations))))))
