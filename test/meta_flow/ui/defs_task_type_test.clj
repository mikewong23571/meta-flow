(ns meta-flow.ui.defs-task-type-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.ui.defs :as ui.defs]))

(deftest list-task-types-builds-compact-list-items
  (let [task-type {:task-type/id :task-type/repo-arch-investigation
                   :task-type/version 1
                   :task-type/name "Repo architecture investigation"
                   :task-type/description "Inspect a repository and produce an architecture report."
                   :task-type/input-schema [{:field/id :input/repo-url
                                             :field/label "Repository URL"
                                             :field/type :field.type/text
                                             :field/required? true}
                                            {:field/id :input/notify-email
                                             :field/label "Notification Email"
                                             :field/type :field.type/email
                                             :field/required? true}]
                   :task-type/work-key-expr {:work-key/type :work-key.type/tuple
                                             :work-key/tag :repo-arch
                                             :work-key/fields [:input/repo-url :input/notify-email]}
                   :task-type/runtime-profile-ref {:definition/id :runtime-profile/codex-repo-arch
                                                   :definition/version 1}
                   :task-type/resource-policy-ref {:definition/id :resource-policy/serial-repo-arch
                                                   :definition/version 1}
                   :task-type/artifact-contract-ref {:definition/id :artifact-contract/repo-arch
                                                     :definition/version 1}}]
    (with-redefs [defs.protocol/list-task-type-defs (fn [_] [task-type])
                  defs.protocol/find-runtime-profile (fn [_ runtime-profile-id version]
                                                       (is (= [:runtime-profile/codex-repo-arch 1]
                                                              [runtime-profile-id version]))
                                                       {:runtime-profile/name "Codex repo architecture"
                                                        :runtime-profile/adapter-id :runtime.adapter/codex
                                                        :runtime-profile/dispatch-mode :dispatch.mode/background})
                  defs.protocol/find-resource-policy (fn [_ resource-policy-id version]
                                                       (is (= [:resource-policy/serial-repo-arch 1]
                                                              [resource-policy-id version]))
                                                       {:resource-policy/name "Serial repo arch"
                                                        :resource-policy/max-active-runs 1
                                                        :resource-policy/max-attempts 2
                                                        :resource-policy/lease-duration-seconds 1800
                                                        :resource-policy/heartbeat-timeout-seconds 120
                                                        :resource-policy/queue-order :queue-order/fifo})
                  defs.protocol/find-artifact-contract (fn [_ artifact-contract-id version]
                                                         (is (= [:artifact-contract/repo-arch 1]
                                                                [artifact-contract-id version]))
                                                         {:artifact-contract/name "Repo architecture bundle"
                                                          :artifact-contract/required-paths ["architecture.md" "email-receipt.edn"]
                                                          :artifact-contract/optional-paths ["notes.txt"]})]
      (is (= [{:task-type/id :task-type/repo-arch-investigation
               :task-type/version 1
               :task-type/name "Repo architecture investigation"
               :task-type/description "Inspect a repository and produce an architecture report."
               :task-type/input-labels ["Repository URL"
                                        "Notification Email"]
               :task-type/input-count 2
               :task-type/work-key {:work-key/type :work-key.type/tuple
                                    :work-key/tag :repo-arch
                                    :work-key/fields [:input/repo-url :input/notify-email]}
               :task-type/runtime-profile {:definition/id :runtime-profile/codex-repo-arch
                                           :definition/version 1
                                           :definition/name "Codex repo architecture"
                                           :runtime-profile/adapter-id :runtime.adapter/codex
                                           :runtime-profile/dispatch-mode :dispatch.mode/background}
               :task-type/resource-policy {:definition/id :resource-policy/serial-repo-arch
                                           :definition/version 1
                                           :definition/name "Serial repo arch"
                                           :resource-policy/max-active-runs 1
                                           :resource-policy/max-attempts 2
                                           :resource-policy/lease-duration-seconds 1800
                                           :resource-policy/heartbeat-timeout-seconds 120
                                           :resource-policy/queue-order :queue-order/fifo}
               :task-type/artifact-contract {:definition/id :artifact-contract/repo-arch
                                             :definition/version 1
                                             :definition/name "Repo architecture bundle"
                                             :artifact-contract/required-paths ["architecture.md" "email-receipt.edn"]
                                             :artifact-contract/optional-paths ["notes.txt"]
                                             :artifact-contract/required-path-count 2
                                             :artifact-contract/optional-path-count 1}}]
             (ui.defs/list-task-types :defs-repo))))))

(deftest list-task-type-create-options-keeps-form-schema-separate-from-list-view
  (let [task-type {:task-type/id :task-type/repo-arch-investigation
                   :task-type/version 1
                   :task-type/name "Repo architecture investigation"
                   :task-type/description "Inspect a repository and produce an architecture report."
                   :task-type/input-schema [{:field/id :input/repo-url
                                             :field/label "Repository URL"
                                             :field/type :field.type/text
                                             :field/required? true}
                                            {:field/id :input/notify-email
                                             :field/label "Notification Email"
                                             :field/type :field.type/email
                                             :field/required? true}]}
        defs-repo :defs-repo]
    (with-redefs [defs.protocol/list-task-type-defs (fn [_] [task-type])]
      (is (= [{:task-type/id :task-type/repo-arch-investigation
               :task-type/version 1
               :task-type/name "Repo architecture investigation"
               :task-type/description "Inspect a repository and produce an architecture report."
               :task-type/input-schema [{:field/id :input/repo-url
                                         :field/label "Repository URL"
                                         :field/type :field.type/text
                                         :field/required? true}
                                        {:field/id :input/notify-email
                                         :field/label "Notification Email"
                                         :field/type :field.type/email
                                         :field/required? true}]}]
             (ui.defs/list-task-type-create-options defs-repo))))))

(deftest load-task-type-detail-resolves-related-definitions
  (let [task-type {:task-type/id :task-type/default
                   :task-type/version 1
                   :task-type/name "Default generic task"
                   :task-type/description "Generic task definition used by bootstrap flows."
                   :task-type/input-schema [{:field/id :work-key
                                             :field/label "Work Key"
                                             :field/type :field.type/text
                                             :field/required? true}]
                   :task-type/work-key-expr {:work-key/type :work-key.type/direct
                                             :work-key/field :work-key}
                   :task-type/task-fsm-ref {:definition/id :task-fsm/default
                                            :definition/version 3}
                   :task-type/run-fsm-ref {:definition/id :run-fsm/default
                                           :definition/version 2}
                   :task-type/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                                   :definition/version 1}
                   :task-type/validator-ref {:definition/id :validator/required-paths
                                             :definition/version 1}
                   :task-type/resource-policy-ref {:definition/id :resource-policy/default
                                                   :definition/version 3}
                   :task-type/artifact-contract-ref {:definition/id :artifact-contract/default
                                                     :definition/version 1}}]
    (with-redefs [defs.protocol/find-task-type-def (fn [_ task-type-id version]
                                                     (when (= [:task-type/default 1] [task-type-id version])
                                                       task-type))
                  defs.protocol/find-task-fsm-def (fn [_ _ _]
                                                    {:task-fsm/name "Default task lifecycle"
                                                     :task-fsm/initial-state :task.state/queued
                                                     :task-fsm/states [:task.state/queued :task.state/running :task.state/completed]
                                                     :task-fsm/terminal-states #{:task.state/completed}})
                  defs.protocol/find-run-fsm-def (fn [_ _ _]
                                                   {:run-fsm/name "Default run lifecycle"
                                                    :run-fsm/initial-state :run.state/queued
                                                    :run-fsm/states [:run.state/queued :run.state/running :run.state/completed]
                                                    :run-fsm/terminal-states #{:run.state/completed}})
                  defs.protocol/find-runtime-profile (fn [_ _ _]
                                                       {:runtime-profile/name "Mock worker"
                                                        :runtime-profile/adapter-id :runtime.adapter/mock
                                                        :runtime-profile/dispatch-mode :dispatch.mode/inline})
                  defs.protocol/find-validator-def (fn [_ _ _]
                                                     {:validator/name "Required paths"
                                                      :validator/type :validator.type/required-paths})
                  defs.protocol/find-resource-policy (fn [_ _ _]
                                                       {:resource-policy/name "Default policy"
                                                        :resource-policy/max-active-runs 2
                                                        :resource-policy/max-attempts 3
                                                        :resource-policy/lease-duration-seconds 900
                                                        :resource-policy/heartbeat-timeout-seconds 60
                                                        :resource-policy/queue-order :queue-order/fifo})
                  defs.protocol/find-artifact-contract (fn [_ _ _]
                                                         {:artifact-contract/name "Default bundle"
                                                          :artifact-contract/required-paths ["result.json"]
                                                          :artifact-contract/optional-paths []})]
      (testing "detail includes resolved refs and work-key metadata"
        (is (= {:task-type/id :task-type/default
                :task-type/version 1
                :task-type/name "Default generic task"
                :task-type/description "Generic task definition used by bootstrap flows."
                :task-type/input-schema [{:field/id :work-key
                                          :field/label "Work Key"
                                          :field/type :field.type/text
                                          :field/required? true}]
                :task-type/work-key {:work-key/type :work-key.type/direct
                                     :work-key/field :work-key}
                :task-type/task-fsm {:definition/id :task-fsm/default
                                     :definition/version 3
                                     :definition/name "Default task lifecycle"
                                     :task-fsm/initial-state :task.state/queued
                                     :task-fsm/state-count 3
                                     :task-fsm/terminal-state-count 1}
                :task-type/run-fsm {:definition/id :run-fsm/default
                                    :definition/version 2
                                    :definition/name "Default run lifecycle"
                                    :run-fsm/initial-state :run.state/queued
                                    :run-fsm/state-count 3
                                    :run-fsm/terminal-state-count 1}
                :task-type/runtime-profile {:definition/id :runtime-profile/mock-worker
                                            :definition/version 1
                                            :definition/name "Mock worker"
                                            :runtime-profile/adapter-id :runtime.adapter/mock
                                            :runtime-profile/dispatch-mode :dispatch.mode/inline}
                :task-type/validator {:definition/id :validator/required-paths
                                      :definition/version 1
                                      :definition/name "Required paths"
                                      :validator/type :validator.type/required-paths}
                :task-type/resource-policy {:definition/id :resource-policy/default
                                            :definition/version 3
                                            :definition/name "Default policy"
                                            :resource-policy/max-active-runs 2
                                            :resource-policy/max-attempts 3
                                            :resource-policy/lease-duration-seconds 900
                                            :resource-policy/heartbeat-timeout-seconds 60
                                            :resource-policy/queue-order :queue-order/fifo}
                :task-type/artifact-contract {:definition/id :artifact-contract/default
                                              :definition/version 1
                                              :definition/name "Default bundle"
                                              :artifact-contract/required-paths ["result.json"]
                                              :artifact-contract/optional-paths []
                                              :artifact-contract/required-path-count 1
                                              :artifact-contract/optional-path-count 0}}
               (ui.defs/load-task-type-detail :defs-repo :task-type/default 1))))
      (testing "missing task types return a not-found ex-info"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Task type not found: :task-type/missing"
                              (ui.defs/load-task-type-detail :defs-repo :task-type/missing 1)))))))
