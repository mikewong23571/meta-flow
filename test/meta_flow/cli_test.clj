(ns meta-flow.cli-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [meta-flow.cli :as cli]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn- temp-cli-system
  []
  (let [temp-dir (java.nio.file.Files/createTempDirectory "meta-flow-cli-test"
                                                          (make-array java.nio.file.attribute.FileAttribute 0))
        root (.toFile temp-dir)]
    {:db-path (str root "/meta-flow.sqlite3")
     :artifacts-dir (str root "/artifacts")
     :runs-dir (str root "/runs")
     :codex-home-dir (str root "/codex-home")}))

(deftest inspect-commands-do-not-bootstrap-the-system
  (let [bootstrap-calls (atom [])]
    (with-redefs [db/default-db-path "var/test.sqlite3"
                  defs.loader/filesystem-definition-repository (fn []
                                                                 (swap! bootstrap-calls conj :defs)
                                                                 (throw (ex-info "definitions should not load during inspect" {})))
                  db/initialize-database! (fn
                                            ([] (swap! bootstrap-calls conj :db-init)
                                                (throw (ex-info "db init should not run during inspect" {})))
                                            ([_] (swap! bootstrap-calls conj :db-init)
                                                 (throw (ex-info "db init should not run during inspect" {}))))
                  db/ensure-runtime-directories! (fn []
                                                   (swap! bootstrap-calls conj :runtime-dirs)
                                                   (throw (ex-info "runtime dirs should not be created during inspect" {})))
                  scheduler/inspect-task! (fn [_ task-id]
                                            {:task/id task-id
                                             :task/state :task.state/completed})
                  scheduler/inspect-run! (fn [_ run-id]
                                           {:run/id run-id
                                            :run/state :run.state/finalized})
                  scheduler/inspect-collection! (fn [_]
                                                  {:collection/dispatch {:dispatch/paused? false}
                                                   :collection/resource-policy-ref {:definition/id :resource-policy/default
                                                                                    :definition/version 1}})]
      (let [task-output (with-out-str
                          (cli/dispatch-command! ["inspect" "task" "--task-id" "task-123"]))
            run-output (with-out-str
                         (cli/dispatch-command! ["inspect" "run" "--run-id" "run-123"]))
            collection-output (with-out-str
                                (cli/dispatch-command! ["inspect" "collection"]))]
        (is (empty? @bootstrap-calls))
        (is (str/includes? task-output "task-123"))
        (is (str/includes? run-output "run-123"))
        (is (str/includes? collection-output ":resource-policy/default"))))))

(deftest enqueue-command-bootstraps-and-persists-a-queued-task
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (temp-cli-system)
        work-key "CVE-2024-CLI-0001"]
    (with-redefs [db/default-db-path db-path
                  db/runtime-directories [artifacts-dir runs-dir codex-home-dir]]
      (let [store (store.sqlite/sqlite-state-store db-path)
            output (with-out-str
                     (cli/dispatch-command! ["enqueue" "--work-key" work-key]))
            task-row (scheduler/inspect-collection! db-path)
            task (store.protocol/find-task-by-work-key store work-key)
            task-id (:task/id task)]
        (is (str/includes? output work-key))
        (is (some? task-id))
        (is (= :task.state/queued (:task/state task)))
        (is (= work-key (:task/work-key task)))
        (is (= {:definition/id :runtime-profile/mock-worker
                :definition/version 1}
               (:task/runtime-profile-ref task)))
        (is (true? (.exists (io/file artifacts-dir))))
        (is (true? (.exists (io/file runs-dir))))
        (is (true? (.exists (io/file codex-home-dir))))
        (is (= {:definition/id :resource-policy/default
                :definition/version 1}
               (:collection/resource-policy-ref task-row)))))))

(deftest demo-retry-path-command-prints-rejected-outcome
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (temp-cli-system)]
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
                                                                         :task-errors [{:task/id "task-1"
                                                                                        :error/message "bad adapter"}]
                                                                         :snapshot {:snapshot/runnable-count 3
                                                                                    :snapshot/awaiting-validation-count 1}})]
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
    (is (str/includes? scheduler-output "Created runs: 1"))
    (is (str/includes? scheduler-output "Dispatch errors: 1"))
    (is (str/includes? scheduler-output "Task task-1 failed: bad adapter"))
    (is (str/includes? happy-output "Assessment accepted"))
    (is (str/includes? happy-output "Task task-1 -> :task.state/completed"))
    (is (str/includes? happy-output "Run run-1 -> :run.state/finalized"))))

(deftest enqueue-and-inspect-requirements-surface-usage-errors
  (let [repository ::repository]
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
                  scheduler/enqueue-demo-task! (fn [_ opts]
                                                 {:task {:task/id "task-1"
                                                         :task/work-key (or (:work-key opts) "generated-work-key")
                                                         :task/state :task.state/queued
                                                         :task/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                                                                    :definition/version 1}}
                                                  :reused? false})]
      (let [enqueue-output (with-out-str
                             (cli/dispatch-command! ["enqueue"]))]
        (is (str/includes? enqueue-output "generated-work-key")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Missing required option --task-id"
                            (cli/dispatch-command! ["inspect" "task"])))
      (let [out-output (with-out-str
                         (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                               #"Unsupported command"
                                               (cli/dispatch-command! ["unsupported" "command"]))))]
        (is (str/includes? out-output "Usage:"))
        (is (str/includes? out-output "inspect collection"))))))
