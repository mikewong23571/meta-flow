(ns meta-flow.defs-loader-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]))

(deftest filesystem-repository-loads-and-indexes-definitions
  (let [repository (defs.loader/filesystem-definition-repository)
        definitions (defs.protocol/load-workflow-defs repository)
        summary (defs.loader/definitions-summary definitions)]
    (testing "summary counts match milestone 1 expectations"
      (is (= 2 (:task-types summary)))
      (is (= 2 (:task-fsms summary)))
      (is (= 2 (:run-fsms summary)))
      (is (= 2 (:runtime-profiles summary))))
    (testing "lookups resolve version-pinned definitions"
      (is (= :task-type/cve-investigation
             (:task-type/id (defs.protocol/find-task-type-def repository :task-type/cve-investigation 1))))
      (is (= :runtime.adapter/codex
             (:runtime-profile/adapter-id (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1))))
      (is (= "meta_flow/prompts/worker.md"
             (:runtime-profile/worker-prompt-path (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1))))
      (is (= :artifact-contract/cve-investigation
             (get-in (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1)
                     [:runtime-profile/artifact-contract-ref :definition/id]))))))
