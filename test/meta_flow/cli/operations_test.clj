(ns meta-flow.cli.operations-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [meta-flow.cli :as cli]
            [meta-flow.cli.test-support :as support]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.codex :as runtime.codex]
            [meta-flow.runtime.codex.home :as codex.home]
            [meta-flow.runtime.codex.process.launch :as codex.launch]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.scheduler :as scheduler]))

(deftest demo-retry-path-command-prints-rejected-outcome
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-cli-system)]
    (with-redefs [db/default-db-path db-path
                  db/runtime-directories [artifacts-dir runs-dir codex-home-dir]]
      (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
                runtime.mock.fs/*run-root-dir* runs-dir]
        (let [output (with-out-str
                       (cli/dispatch-command! ["demo" "retry-path"]))
              task-id (some->> output
                               (re-find #"Enqueued task ([^ ]+)")
                               second)
              run-id (some->> output
                              (re-find #"Created run ([^ ]+)")
                              second)
              task (when task-id
                     (scheduler/inspect-task! db-path task-id))
              run (when run-id
                    (scheduler/inspect-run! db-path run-id))]
          (is (str/includes? output "Assessment rejected"))
          (is (str/includes? output "Disposition :disposition/rejected"))
          (is (str/includes? output ":task.state/retryable-failed"))
          (is (some? task-id))
          (is (some? run-id))
          (is (= :task.state/retryable-failed (:task/state task)))
          (is (= :run.state/retryable-failed (:run/state run)))
          (is (string? (:run/artifact-root run))))))))

(deftest operational-commands-print-summaries-for-init-defs-scheduler-and-demo
  (let [repository ::repository
        bootstrap-calls (atom [])
        init-output (with-out-str
                      (with-redefs [defs.loader/filesystem-definition-repository (fn []
                                                                                   repository)
                                    defs.protocol/load-workflow-defs (fn [repo]
                                                                       (swap! bootstrap-calls conj [:load repo])
                                                                       {:defs/loaded true})
                                    db/initialize-database! (fn
                                                              ([] {:db-path "var/meta-flow.sqlite3"
                                                                   :pragmas {:journal_mode "wal"
                                                                             :busy_timeout 5000}})
                                                              ([_] {:db-path "var/meta-flow.sqlite3"
                                                                    :pragmas {:journal_mode "wal"
                                                                              :busy_timeout 5000}}))
                                    db/ensure-runtime-directories! (fn []
                                                                     ["var/artifacts" "var/runs" "var/codex-home"])]
                        (cli/dispatch-command! ["init"])))
        defs-output (with-out-str
                      (with-redefs [defs.loader/filesystem-definition-repository (fn []
                                                                                   repository)
                                    defs.protocol/load-workflow-defs (fn [_]
                                                                       {:workflow :loaded})
                                    defs.loader/definitions-summary (fn [_]
                                                                      {:task-types 2
                                                                       :task-fsms 3
                                                                       :run-fsms 4
                                                                       :runtime-profiles 5})]
                        (cli/dispatch-command! ["defs" "validate"])))
        codex-home-output (with-out-str
                            (with-redefs [defs.loader/filesystem-definition-repository (fn []
                                                                                         repository)
                                          defs.protocol/load-workflow-defs (fn [_]
                                                                             {:workflow :loaded})
                                          defs.protocol/find-runtime-profile (fn [_ runtime-profile-id version]
                                                                               {:runtime-profile/id runtime-profile-id
                                                                                :runtime-profile/version version
                                                                                :runtime-profile/codex-home-root "var/codex-home"})
                                          db/ensure-runtime-directories! (fn []
                                                                           ["var/artifacts" "var/runs" "var/codex-home"])
                                          codex.home/install-home! (fn [_]
                                                                     {:codex-home/root "var/codex-home"
                                                                      :codex-home/installed-paths ["var/codex-home/README.md"]
                                                                      :codex-home/skipped-paths ["var/codex-home/config.edn"]})]
                              (cli/dispatch-command! ["runtime" "init-codex-home"])))
        scheduler-output (with-out-str
                           (with-redefs [db/default-db-path "var/meta-flow.sqlite3"
                                         defs.loader/filesystem-definition-repository (fn []
                                                                                        repository)
                                         defs.protocol/load-workflow-defs (fn [_]
                                                                            {:workflow :loaded})
                                         db/initialize-database! (fn
                                                                   ([] {:db-path "var/meta-flow.sqlite3"
                                                                        :pragmas {:journal_mode "wal"
                                                                                  :busy_timeout 5000}})
                                                                   ([_] {:db-path "var/meta-flow.sqlite3"
                                                                         :pragmas {:journal_mode "wal"
                                                                                   :busy_timeout 5000}}))
                                         db/ensure-runtime-directories! (fn []
                                                                          ["var/artifacts" "var/runs" "var/codex-home"])
                                         scheduler/run-scheduler-step (fn [_]
                                                                        {:now "2026-04-02T00:00:00Z"
                                                                         :created-runs [{:run/id "run-1"}]
                                                                         :requeued-task-ids ["task-2"]
                                                                         :escalated-task-ids ["task-3"]
                                                                         :expired-lease-run-ids ["run-lease-1"]
                                                                         :heartbeat-timeout-run-ids ["run-heartbeat-1"]
                                                                         :dispatch-block-reason :dispatch.block/cooldown
                                                                         :capacity-skipped-task-ids ["task-4" "task-5"]
                                                                         :task-errors [{:task/id "task-1"
                                                                                        :error/message "bad adapter"}]
                                                                         :snapshot {:snapshot/runnable-count 3
                                                                                    :snapshot/retryable-failed-count 2
                                                                                    :snapshot/awaiting-validation-count 1
                                                                                    :snapshot/expired-lease-count 4
                                                                                    :snapshot/heartbeat-timeout-count 5
                                                                                    :snapshot/dispatch-cooldown-active? true
                                                                                    :snapshot/dispatch-cooldown-until "2026-04-02T00:05:00Z"}})]
                             (cli/dispatch-command! ["scheduler" "once"])))
        happy-output (with-out-str
                       (with-redefs [db/default-db-path "var/meta-flow.sqlite3"
                                     defs.loader/filesystem-definition-repository (fn []
                                                                                    repository)
                                     defs.protocol/load-workflow-defs (fn [_]
                                                                        {:workflow :loaded})
                                     db/initialize-database! (fn
                                                               ([] {:db-path "var/meta-flow.sqlite3"
                                                                    :pragmas {:journal_mode "wal"
                                                                              :busy_timeout 5000}})
                                                               ([_] {:db-path "var/meta-flow.sqlite3"
                                                                     :pragmas {:journal_mode "wal"
                                                                               :busy_timeout 5000}}))
                                     db/ensure-runtime-directories! (fn []
                                                                      ["var/artifacts" "var/runs" "var/codex-home"])
                                     scheduler/demo-happy-path! (fn [_]
                                                                  {:task {:task/id "task-1"
                                                                          :task/work-key "wk-1"
                                                                          :task/state :task.state/completed}
                                                                   :run {:run/id "run-1"
                                                                         :run/attempt 1
                                                                         :run/state :run.state/finalized}
                                                                   :artifact-root "var/artifacts/run-1"
                                                                   :scheduler-steps 4})]
                         (cli/dispatch-command! ["demo" "happy-path"])))]
    (is (= [[:load repository]] @bootstrap-calls))
    (is (str/includes? init-output "Initialized database at var/meta-flow.sqlite3"))
    (is (str/includes? init-output "Loaded workflow definitions"))
    (is (str/includes? defs-output "Definitions valid"))
    (is (str/includes? defs-output "Task types: 2"))
    (is (str/includes? codex-home-output "Ensured project CODEX_HOME at var/codex-home"))
    (is (str/includes? codex-home-output "Installed runtime templates for codex worker"))
    (is (str/includes? codex-home-output "Templates installed: 1"))
    (is (str/includes? codex-home-output "Templates preserved: 1"))
    (is (str/includes? scheduler-output "Created runs: 1"))
    (is (str/includes? scheduler-output "Requeued tasks: 1"))
    (is (str/includes? scheduler-output "Escalated tasks: 1"))
    (is (str/includes? scheduler-output "Recovered expired leases: 1"))
    (is (str/includes? scheduler-output "Recovered heartbeat timeouts: 1"))
    (is (str/includes? scheduler-output "Dispatch blocked: true"))
    (is (str/includes? scheduler-output "Dispatch block reason: :dispatch.block/cooldown"))
    (is (str/includes? scheduler-output "Capacity skipped tasks: 2"))
    (is (str/includes? scheduler-output "Dispatch errors: 1"))
    (is (str/includes? scheduler-output "Retryable failures before step: 2"))
    (is (str/includes? scheduler-output "Expired leases before step: 4"))
    (is (str/includes? scheduler-output "Heartbeat timeouts before step: 5"))
    (is (str/includes? scheduler-output "Dispatch cooldown active before step: true"))
    (is (str/includes? scheduler-output "Dispatch cooldown until: 2026-04-02T00:05:00Z"))
    (is (str/includes? scheduler-output "Task task-1 failed: bad adapter"))
    (is (str/includes? happy-output "Assessment accepted"))
    (is (str/includes? happy-output "Task task-1 -> :task.state/completed"))
    (is (str/includes? happy-output "Run run-1 -> :run.state/finalized"))))

(deftest codex-smoke-command-requires-opt-in-and-prints-a-summary-when-enabled
  (let [repository ::repository]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Codex smoke test cannot start: set META_FLOW_ENABLE_CODEX_SMOKE=1"
                          (with-redefs [db/default-db-path "var/meta-flow.sqlite3"
                                        defs.loader/filesystem-definition-repository (fn []
                                                                                       repository)
                                        defs.protocol/load-workflow-defs (fn [_]
                                                                           {:workflow :loaded})
                                        db/initialize-database! (fn
                                                                  ([] {:db-path "var/meta-flow.sqlite3"
                                                                       :pragmas {:journal_mode "wal"
                                                                                 :busy_timeout 5000}})
                                                                  ([_] {:db-path "var/meta-flow.sqlite3"
                                                                        :pragmas {:journal_mode "wal"
                                                                                  :busy_timeout 5000}}))
                                        db/ensure-runtime-directories! (fn []
                                                                         ["var/artifacts" "var/runs" "var/codex-home"])
                                        codex.launch/smoke-enabled? (constantly false)]
                            (cli/dispatch-command! ["demo" "codex-smoke"]))))
    (let [output (with-out-str
                   (with-redefs [db/default-db-path "var/meta-flow.sqlite3"
                                 defs.loader/filesystem-definition-repository (fn []
                                                                                repository)
                                 defs.protocol/load-workflow-defs (fn [_]
                                                                    {:workflow :loaded})
                                 defs.protocol/find-runtime-profile (fn [_ runtime-profile-id version]
                                                                      {:runtime-profile/id runtime-profile-id
                                                                       :runtime-profile/version version})
                                 db/initialize-database! (fn
                                                           ([] {:db-path "var/meta-flow.sqlite3"
                                                                :pragmas {:journal_mode "wal"
                                                                          :busy_timeout 5000}})
                                                           ([_] {:db-path "var/meta-flow.sqlite3"
                                                                 :pragmas {:journal_mode "wal"
                                                                           :busy_timeout 5000}}))
                                 db/ensure-runtime-directories! (fn []
                                                                  ["var/artifacts" "var/runs" "var/codex-home"])
                                 codex.launch/smoke-enabled? (constantly true)
                                 runtime.codex/ensure-launch-supported! (fn [_] {:launch/ready? true})
                                 scheduler/demo-codex-smoke! (fn [_]
                                                               {:task {:task/id "task-9"
                                                                       :task/work-key "wk-codex"
                                                                       :task/state :task.state/completed}
                                                                :run {:run/id "run-9"
                                                                      :run/attempt 1
                                                                      :run/state :run.state/finalized}
                                                                :artifact-root "var/artifacts/run-9"
                                                                :scheduler-steps 8})]
                     (cli/dispatch-command! ["demo" "codex-smoke"])))]
      (is (str/includes? output "Dispatched codex worker with :runtime-profile/codex-worker"))
      (is (str/includes? output "Task task-9 -> :task.state/completed"))
      (is (str/includes? output "Run run-9 -> :run.state/finalized")))))
