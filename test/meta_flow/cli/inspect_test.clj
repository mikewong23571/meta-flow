(ns meta-flow.cli.inspect-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [meta-flow.cli :as cli]
            [meta-flow.cli.test-support :as support]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

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
                                                  {:collection/dispatch {:dispatch/paused? false
                                                                         :dispatch/cooldown-until nil}
                                                   :collection/resource-policy-ref {:definition/id :resource-policy/default
                                                                                    :definition/version 3}})]
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
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-cli-system)
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
        (is (= {:definition/id :runtime-profile/mock-worker :definition/version 1}
               (:task/runtime-profile-ref task)))
        (is (true? (.exists (io/file artifacts-dir))))
        (is (true? (.exists (io/file runs-dir))))
        (is (true? (.exists (io/file codex-home-dir))))
        (is (= {:definition/id :resource-policy/default
                :definition/version 3}
               (:collection/resource-policy-ref task-row)))))))

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
