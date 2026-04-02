(ns meta-flow.defs-validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.defs.validation :as defs.validation]))

(defn- loaded-definitions
  []
  (defs.protocol/load-workflow-defs (defs.loader/filesystem-definition-repository)))

(deftest validate-fsm-structure-rejects-invalid-boundaries
  (let [base-fsm {:task-fsm/id :task-fsm/test
                  :task-fsm/states [:a :b]
                  :task-fsm/initial-state :a
                  :task-fsm/terminal-states [:b]
                  :task-fsm/transitions [{:transition/from :a
                                          :transition/to :b}]}]
    (is (nil? (defs.validation/validate-fsm-structure! "Task FSM"
                                                       base-fsm
                                                       :task-fsm/states
                                                       :task-fsm/initial-state
                                                       :task-fsm/terminal-states
                                                       :task-fsm/transitions)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"initial state is not listed in states"
                          (defs.validation/validate-fsm-structure! "Task FSM"
                                                                   (assoc base-fsm :task-fsm/initial-state :missing)
                                                                   :task-fsm/states
                                                                   :task-fsm/initial-state
                                                                   :task-fsm/terminal-states
                                                                   :task-fsm/transitions)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"terminal states must be a subset of states"
                          (defs.validation/validate-fsm-structure! "Task FSM"
                                                                   (assoc base-fsm :task-fsm/terminal-states [:missing])
                                                                   :task-fsm/states
                                                                   :task-fsm/initial-state
                                                                   :task-fsm/terminal-states
                                                                   :task-fsm/transitions)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"transition source state is undefined"
                          (defs.validation/validate-fsm-structure! "Task FSM"
                                                                   (assoc base-fsm :task-fsm/transitions [{:transition/from :missing
                                                                                                           :transition/to :b}])
                                                                   :task-fsm/states
                                                                   :task-fsm/initial-state
                                                                   :task-fsm/terminal-states
                                                                   :task-fsm/transitions)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"transition target state is undefined"
                          (defs.validation/validate-fsm-structure! "Task FSM"
                                                                   (assoc base-fsm :task-fsm/transitions [{:transition/from :a
                                                                                                           :transition/to :missing}])
                                                                   :task-fsm/states
                                                                   :task-fsm/initial-state
                                                                   :task-fsm/terminal-states
                                                                   :task-fsm/transitions)))))

(deftest runtime-profile-validation-checks-resources-and-codex-home-root
  (let [definitions (loaded-definitions)
        runtime-profile (first (filter #(= :runtime.adapter/codex
                                           (:runtime-profile/adapter-id %))
                                       (:runtime-profiles definitions)))
        artifact-contract-index {[(get-in runtime-profile [:runtime-profile/artifact-contract-ref :definition/id])
                                  (get-in runtime-profile [:runtime-profile/artifact-contract-ref :definition/version])]
                                 {:artifact-contract/id :artifact-contract/default}}]
    (is (nil? (defs.validation/validate-runtime-profile! runtime-profile artifact-contract-index)))
    (testing "resource and helper paths are required"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Missing resource path"
                            (defs.validation/validate-runtime-profile!
                             (assoc runtime-profile :runtime-profile/worker-prompt-path "meta_flow/prompts/missing.md")
                             artifact-contract-index)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Missing filesystem path"
                            (defs.validation/validate-runtime-profile!
                             (assoc runtime-profile :runtime-profile/helper-script-path "var/missing.sh")
                             artifact-contract-index))))
    (testing "codex home must stay under var/"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"CODEX_HOME under var/"
                            (defs.validation/validate-runtime-profile!
                             (assoc runtime-profile :runtime-profile/codex-home-root "tmp/codex-home")
                             artifact-contract-index))))
    (testing "non-codex profiles do not require codex-specific validation"
      (is (nil? (defs.validation/validate-runtime-profile!
                 {:runtime-profile/id :runtime-profile/mock
                  :runtime-profile/adapter-id :runtime.adapter/mock}
                 artifact-contract-index))))))

(deftest definition-links-build-indexes-and-reject-broken-references
  (let [definitions (loaded-definitions)
        indexes (defs.validation/validate-definition-links! definitions)]
    (is (contains? indexes :task-types))
    (is (contains? (:runtime-profiles indexes) [:runtime-profile/mock-worker 1]))
    (is (contains? (:task-fsms indexes) [:task-fsm/default 2]))
    (is (contains? (:task-fsms indexes) [:task-fsm/cve-investigation 2]))
    (is (contains? (:resource-policies indexes) [:resource-policy/default 2]))
    (is (contains? (:resource-policies indexes) [:resource-policy/serial-cve 2]))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing referenced definition for workflow default task type"
                          (defs.validation/validate-definition-links!
                           (assoc definitions
                                  :workflow (assoc (:workflow definitions)
                                                   :workflow/default-task-type-ref
                                                   {:definition/id :task-type/missing
                                                    :definition/version 1})))))))
