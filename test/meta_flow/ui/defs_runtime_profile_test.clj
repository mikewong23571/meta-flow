(ns meta-flow.ui.defs-runtime-profile-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.ui.defs :as ui.defs]))

(deftest list-runtime-profiles-builds-standalone-list-items
  (let [runtime-profile {:runtime-profile/id :runtime-profile/codex-repo-arch
                         :runtime-profile/version 1
                         :runtime-profile/name "Codex repo architecture worker"
                         :runtime-profile/adapter-id :runtime.adapter/codex
                         :runtime-profile/dispatch-mode :runtime.dispatch/external
                         :runtime-profile/default-launch-mode :launch.mode/codex-exec
                         :runtime-profile/worker-prompt-path "meta_flow/prompts/repo-arch-worker.md"
                         :runtime-profile/web-search-enabled? true
                         :runtime-profile/artifact-contract-ref {:definition/id :artifact-contract/repo-arch
                                                                 :definition/version 1}}
        matching-task-type {:task-type/id :task-type/repo-arch-investigation
                            :task-type/version 1
                            :task-type/name "Repo architecture investigation"
                            :task-type/description "Inspect a repository and produce an architecture report."
                            :task-type/runtime-profile-ref {:definition/id :runtime-profile/codex-repo-arch
                                                            :definition/version 1}}
        defs-repo :defs-repo]
    (with-redefs [defs.protocol/load-workflow-defs (fn [_] {:runtime-profiles [runtime-profile]})
                  defs.protocol/list-task-type-defs (fn [_] [matching-task-type])
                  defs.protocol/find-artifact-contract (fn [_ artifact-contract-id version]
                                                         (is (= [:artifact-contract/repo-arch 1]
                                                                [artifact-contract-id version]))
                                                         {:artifact-contract/name "Repo architecture bundle"
                                                          :artifact-contract/required-paths ["architecture.md"]
                                                          :artifact-contract/optional-paths ["email-receipt.edn"]})]
      (is (= [{:runtime-profile/id :runtime-profile/codex-repo-arch
               :runtime-profile/version 1
               :runtime-profile/name "Codex repo architecture worker"
               :runtime-profile/adapter-id :runtime.adapter/codex
               :runtime-profile/dispatch-mode :runtime.dispatch/external
               :runtime-profile/default-launch-mode :launch.mode/codex-exec
               :runtime-profile/worker-prompt-path "meta_flow/prompts/repo-arch-worker.md"
               :runtime-profile/web-search-enabled? true
               :runtime-profile/task-type-count 1
               :runtime-profile/task-types [{:task-type/id :task-type/repo-arch-investigation
                                             :task-type/version 1
                                             :task-type/name "Repo architecture investigation"
                                             :task-type/description "Inspect a repository and produce an architecture report."}]
               :runtime-profile/artifact-contract {:definition/id :artifact-contract/repo-arch
                                                   :definition/version 1
                                                   :definition/name "Repo architecture bundle"
                                                   :artifact-contract/required-paths ["architecture.md"]
                                                   :artifact-contract/optional-paths ["email-receipt.edn"]
                                                   :artifact-contract/required-path-count 1
                                                   :artifact-contract/optional-path-count 1}}]
             (ui.defs/list-runtime-profiles defs-repo))))))

(deftest load-runtime-profile-detail-resolves-task-type-usage-and-config
  (let [runtime-profile {:runtime-profile/id :runtime-profile/codex-worker
                         :runtime-profile/version 1
                         :runtime-profile/name "Codex worker"
                         :runtime-profile/adapter-id :runtime.adapter/codex
                         :runtime-profile/dispatch-mode :runtime.dispatch/external
                         :runtime-profile/default-launch-mode :launch.mode/codex-exec
                         :runtime-profile/codex-home-root "var/codex-home"
                         :runtime-profile/worker-prompt-path "meta_flow/prompts/worker.md"
                         :runtime-profile/helper-script-path "script/worker_api.bb"
                         :runtime-profile/worker-timeout-seconds 1800
                         :runtime-profile/heartbeat-interval-seconds 30
                         :runtime-profile/allowed-mcp-servers [:mcp/context7]
                         :runtime-profile/web-search-enabled? true
                         :runtime-profile/env-allowlist ["OPENAI_API_KEY" "PATH"]
                         :runtime-profile/skills ["send-arch-report"]
                         :runtime-profile/artifact-contract-ref {:definition/id :artifact-contract/cve-investigation
                                                                 :definition/version 1}}
        matching-task-type {:task-type/id :task-type/cve-investigation
                            :task-type/version 1
                            :task-type/name "CVE investigation"
                            :task-type/description "Investigate a CVE."
                            :task-type/runtime-profile-ref {:definition/id :runtime-profile/codex-worker
                                                            :definition/version 1}}
        unrelated-task-type {:task-type/id :task-type/default
                             :task-type/version 1
                             :task-type/name "Default generic task"
                             :task-type/description "Generic task"
                             :task-type/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                                             :definition/version 1}}]
    (with-redefs [defs.protocol/find-runtime-profile (fn [_ runtime-profile-id version]
                                                       (when (= [:runtime-profile/codex-worker 1]
                                                                [runtime-profile-id version])
                                                         runtime-profile))
                  defs.protocol/list-task-type-defs (fn [_] [matching-task-type unrelated-task-type])
                  defs.protocol/find-artifact-contract (fn [_ artifact-contract-id version]
                                                         (is (= [:artifact-contract/cve-investigation 1]
                                                                [artifact-contract-id version]))
                                                         {:artifact-contract/name "CVE bundle"
                                                          :artifact-contract/required-paths ["manifest.json" "notes.md"]
                                                          :artifact-contract/optional-paths ["run.log"]})]
      (testing "detail includes runtime config and referenced task types"
        (is (= {:runtime-profile/id :runtime-profile/codex-worker
                :runtime-profile/version 1
                :runtime-profile/name "Codex worker"
                :runtime-profile/adapter-id :runtime.adapter/codex
                :runtime-profile/dispatch-mode :runtime.dispatch/external
                :runtime-profile/default-launch-mode :launch.mode/codex-exec
                :runtime-profile/codex-home-root "var/codex-home"
                :runtime-profile/worker-prompt-path "meta_flow/prompts/worker.md"
                :runtime-profile/helper-script-path "script/worker_api.bb"
                :runtime-profile/worker-timeout-seconds 1800
                :runtime-profile/heartbeat-interval-seconds 30
                :runtime-profile/allowed-mcp-servers [:mcp/context7]
                :runtime-profile/web-search-enabled? true
                :runtime-profile/env-allowlist ["OPENAI_API_KEY" "PATH"]
                :runtime-profile/skills ["send-arch-report"]
                :runtime-profile/artifact-contract {:definition/id :artifact-contract/cve-investigation
                                                    :definition/version 1
                                                    :definition/name "CVE bundle"
                                                    :artifact-contract/required-paths ["manifest.json" "notes.md"]
                                                    :artifact-contract/optional-paths ["run.log"]
                                                    :artifact-contract/required-path-count 2
                                                    :artifact-contract/optional-path-count 1}
                :runtime-profile/task-types [{:task-type/id :task-type/cve-investigation
                                              :task-type/version 1
                                              :task-type/name "CVE investigation"
                                              :task-type/description "Investigate a CVE."}]}
               (ui.defs/load-runtime-profile-detail :defs-repo :runtime-profile/codex-worker 1))))
      (testing "missing runtime profiles return a not-found ex-info"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Runtime profile not found: :runtime-profile/missing"
                              (ui.defs/load-runtime-profile-detail :defs-repo :runtime-profile/missing 1)))))))
