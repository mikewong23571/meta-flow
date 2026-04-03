(ns meta-flow.arch.definition-safety-test
  "治理意图：定义验证应在加载阶段尽早暴露配置错误，避免延迟到运行时。
   拦截问题：ISSUE-10 (路径穿越), ISSUE-11 (adapter 未注册)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [meta-flow.defs.source :as defs.source]))

;; === 规则 C1: adapter-id 注册校验 ===
;; 从 registry.clj 源码中提取已注册的 adapter-id，
;; 避免在测试中硬编码第二份列表。

(defn extract-registered-adapter-ids
  "从 runtime/registry.clj 源码中提取 case 分支的 keyword，
   即当前已注册的 adapter-id 集合。"
  []
  (let [registry-path "src/meta_flow/runtime/registry.clj"
        content (slurp (io/file registry-path))
        ;; 匹配 case 分支中的 :runtime.adapter/xxx keyword
        matches (re-seq #":runtime\.adapter/[a-z][a-z0-9-]*" content)]
    (set (map #(keyword (subs % 1)) matches))))

(deftest all-runtime-profiles-reference-registered-adapters
  (let [registered (extract-registered-adapter-ids)
        defs (defs.source/load-definition-data defs.source/default-resource-base)
        profiles (:runtime-profiles defs)
        violations (for [profile profiles
                         :let [adapter-id (:runtime-profile/adapter-id profile)]
                         :when (not (contains? registered adapter-id))]
                     {:profile-id (:runtime-profile/id profile)
                      :adapter-id adapter-id})]
    (is (seq registered)
        "未能从 registry.clj 提取到任何已注册 adapter-id，检测逻辑可能需要更新")
    (is (empty? violations)
        (str "以下 runtime-profile 引用了未注册的 adapter-id。\n"
             "任务入队后将在运行时 dispatch 阶段失败，而非加载阶段。\n"
             "修复方式：在 runtime/registry.clj 中注册 adapter，或修正 profile 的 adapter-id。\n\n"
             (str/join "\n" (map (fn [{:keys [profile-id adapter-id]}]
                                   (str "  " profile-id " → " adapter-id
                                        " (已注册: " registered ")"))
                                 violations))))))

;; === 规则 C2: artifact-contract 路径安全校验 ===

(deftest artifact-contract-paths-are-safe
  (let [defs (defs.source/load-definition-data defs.source/default-resource-base)
        contracts (:artifact-contracts defs)
        violations (for [contract contracts
                         path (concat (:artifact-contract/required-paths contract)
                                      (:artifact-contract/optional-paths contract))
                         :when (or (str/includes? path "..")
                                   (str/starts-with? path "/")
                                   (str/starts-with? path "~"))]
                     {:contract-id (:artifact-contract/id contract)
                      :path path
                      :reason (cond
                                (str/includes? path "..") "包含 .. 可穿越到 artifact 目录外"
                                (str/starts-with? path "/") "绝对路径不受 artifact-root 限制"
                                (str/starts-with? path "~") "home 目录展开不受 artifact-root 限制")})]
    (is (empty? violations)
        (str "以下 artifact-contract 路径存在安全风险。\n"
             "修复方式：路径必须是相对路径且不包含 .. 穿越。\n\n"
             (str/join "\n" (map (fn [{:keys [contract-id path reason]}]
                                   (str "  " contract-id " 路径 \"" path "\" — " reason))
                                 violations))))))
