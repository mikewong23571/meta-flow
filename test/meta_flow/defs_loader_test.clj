(ns meta-flow.defs-loader-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.defs.repository :as defs.repository]))

(deftest filesystem-repository-loads-and-indexes-definitions
  (let [repository (defs.loader/filesystem-definition-repository)
        definitions (defs.protocol/load-workflow-defs repository)
        summary (defs.loader/definitions-summary definitions)]
    (testing "summary counts match milestone 1 expectations"
      (is (= 2 (:task-fsms summary)))
      (is (= 2 (:run-fsms summary))))
    (testing "lookups resolve version-pinned definitions"
      (is (= :task-type/cve-investigation
             (:task-type/id (defs.protocol/find-task-type-def repository :task-type/cve-investigation 1))))
      (is (= :task-type/repo-arch-investigation
             (:task-type/id (defs.protocol/find-task-type-def repository :task-type/repo-arch-investigation 1))))
      (is (= :runtime.adapter/codex
             (:runtime-profile/adapter-id (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1))))
      (is (= "meta_flow/prompts/worker.md"
             (:runtime-profile/worker-prompt-path (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1))))
      (is (= :artifact-contract/cve-investigation
             (get-in (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1)
                     [:runtime-profile/artifact-contract-ref :definition/id])))
      (is (= "meta_flow/prompts/repo-arch-worker.md"
             (:runtime-profile/worker-prompt-path (defs.protocol/find-runtime-profile repository :runtime-profile/codex-repo-arch 1))))
      (is (= :launch.mode/codex-exec
             (:runtime-profile/default-launch-mode
              (defs.protocol/find-runtime-profile repository :runtime-profile/codex-repo-arch 1)))))
    (testing "summary reports every definition bucket"
      (is (= {:task-types 3
              :task-fsms 2
              :run-fsms 2
              :runtime-profiles 3
              :artifact-contracts 3
              :validators 3
              :resource-policies 4}
             summary)))))

(deftest filesystem-definition-repository-delegates-both-arities
  (with-redefs [defs.repository/filesystem-definition-repository (fn
                                                                   ([] ::default-repository)
                                                                   ([resource-base]
                                                                    [:repository resource-base]))]
    (is (= ::default-repository
           (defs.loader/filesystem-definition-repository)))
    (is (= [:repository "alt/base"]
           (defs.loader/filesystem-definition-repository "alt/base")))))
