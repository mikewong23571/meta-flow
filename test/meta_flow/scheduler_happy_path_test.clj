(ns meta-flow.scheduler-happy-path-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.mock :as runtime.mock]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.runtime.registry :as runtime.registry]
            [meta-flow.projection :as projection]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn- temp-system
  []
  (let [temp-dir (java.nio.file.Files/createTempDirectory "meta-flow-scheduler-test"
                                                          (make-array java.nio.file.attribute.FileAttribute 0))
        root (.toFile temp-dir)
        db-path (str root "/meta-flow.sqlite3")
        artifacts-dir (str root "/artifacts")
        runs-dir (str root "/runs")]
    (.mkdirs (io/file artifacts-dir))
    (.mkdirs (io/file runs-dir))
    (db/initialize-database! db-path)
    {:db-path db-path
     :artifacts-dir artifacts-dir
     :runs-dir runs-dir}))

(defn- query-one
  [db-path sql-text params]
  (sql/with-connection db-path
    (fn [connection]
      (sql/query-one connection sql-text params))))

(defn- enqueue-demo-task!
  [db-path]
  (let [store (store.sqlite/sqlite-state-store db-path)
        defs-repo (defs.loader/filesystem-definition-repository)
        task-type (defs.protocol/find-task-type-def defs-repo :task-type/cve-investigation 1)
        now (sql/utc-now)]
    (store.protocol/enqueue-task! store
                                  {:task/id (str "task-" (java.util.UUID/randomUUID))
                                   :task/work-key (str "CVE-2024-12345-" (subs (str (java.util.UUID/randomUUID)) 0 8))
                                   :task/task-type-ref {:definition/id (:task-type/id task-type)
                                                        :definition/version (:task-type/version task-type)}
                                   :task/task-fsm-ref (:task-type/task-fsm-ref task-type)
                                   :task/run-fsm-ref (:task-type/run-fsm-ref task-type)
                                   :task/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                                              :definition/version 1}
                                   :task/artifact-contract-ref (:task-type/artifact-contract-ref task-type)
                                   :task/validator-ref (:task-type/validator-ref task-type)
                                   :task/resource-policy-ref (:task-type/resource-policy-ref task-type)
                                   :task/state :task.state/queued
                                   :task/created-at now
                                   :task/updated-at now})))

(defn- enqueue-codex-task!
  [db-path]
  (let [store (store.sqlite/sqlite-state-store db-path)
        defs-repo (defs.loader/filesystem-definition-repository)
        task-type (defs.protocol/find-task-type-def defs-repo :task-type/cve-investigation 1)
        now (sql/utc-now)]
    (store.protocol/enqueue-task! store
                                  {:task/id (str "task-" (java.util.UUID/randomUUID))
                                   :task/work-key (str "CVE-2024-99999-" (subs (str (java.util.UUID/randomUUID)) 0 8))
                                   :task/task-type-ref {:definition/id (:task-type/id task-type)
                                                        :definition/version (:task-type/version task-type)}
                                   :task/task-fsm-ref (:task-type/task-fsm-ref task-type)
                                   :task/run-fsm-ref (:task-type/run-fsm-ref task-type)
                                   :task/runtime-profile-ref (:task-type/runtime-profile-ref task-type)
                                   :task/artifact-contract-ref (:task-type/artifact-contract-ref task-type)
                                   :task/validator-ref (:task-type/validator-ref task-type)
                                   :task/resource-policy-ref (:task-type/resource-policy-ref task-type)
                                   :task/state :task.state/queued
                                   :task/created-at now
                                   :task/updated-at now})))

(deftest demo-happy-path-completes-and-persists-structured-control-data
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock/*artifact-root-dir* artifacts-dir
              runtime.mock/*run-root-dir* runs-dir]
      (let [{:keys [task run artifact-root scheduler-steps]} (scheduler/demo-happy-path! db-path)
            task-id (:task/id task)
            run-id (:run/id run)
            task-view (scheduler/inspect-task! db-path task-id)
            run-view (scheduler/inspect-run! db-path run-id)
            collection-view (scheduler/inspect-collection! db-path)]
        (testing "the demo converges to the Phase 1 happy path terminal states"
          (is (= 2 scheduler-steps))
          (is (= :task.state/completed (:task/state task)))
          (is (= :run.state/finalized (:run/state run)))
          (is (= 8 (:run/event-count run-view))))
        (testing "artifact files are written under the run-scoped artifact root"
          (is (= artifact-root (:run/artifact-root run-view)))
          (is (.exists (io/file artifact-root)))
          (is (.exists (io/file artifact-root "manifest.json")))
          (is (.exists (io/file artifact-root "notes.md")))
          (is (.exists (io/file artifact-root "run.log"))))
        (testing "inspect returns the structured definition refs needed for control-plane visibility"
          (is (= {:definition/id :task-type/cve-investigation
                  :definition/version 1}
                 (:task/task-type-ref task-view)))
          (is (= {:definition/id :run-fsm/cve-worker
                  :definition/version 1}
                 (:task/run-fsm-ref task-view)))
          (is (= {:definition/id :runtime-profile/mock-worker
                  :definition/version 1}
                 (:task/runtime-profile-ref task-view)))
          (is (= {:definition/id :runtime-profile/mock-worker
                  :definition/version 1}
                 (:run/runtime-profile-ref run-view)))
          (is (= {:definition/id :resource-policy/default
                  :definition/version 1}
                 (:collection/resource-policy-ref collection-view))))
        (testing "the demo bootstraps persisted collection state"
          (is (= {:item_count 1}
                 (select-keys (query-one db-path
                                         "SELECT COUNT(*) AS item_count FROM collection_state"
                                         [])
                              [:item_count]))))
        (testing "structured control columns are persisted outside the EDN blobs"
          (is (= {:task_type_id ":task-type/cve-investigation"
                  :task_type_version 1}
                 (select-keys (query-one db-path
                                         "SELECT task_type_id, task_type_version FROM tasks WHERE task_id = ?"
                                         [task-id])
                              [:task_type_id :task_type_version])))
          (is (= {:run_fsm_id ":run-fsm/cve-worker"
                  :run_fsm_version 1
                  :runtime_profile_id ":runtime-profile/mock-worker"
                  :runtime_profile_version 1}
                 (select-keys (query-one db-path
                                         (str "SELECT run_fsm_id, run_fsm_version, runtime_profile_id, runtime_profile_version "
                                              "FROM runs WHERE run_id = ?")
                                         [run-id])
                              [:run_fsm_id :run_fsm_version :runtime_profile_id :runtime_profile_version])))
          (is (= {:artifact_contract_id ":artifact-contract/cve-investigation"
                  :artifact_contract_version 1}
                 (select-keys (query-one db-path
                                         "SELECT artifact_contract_id, artifact_contract_version FROM artifacts WHERE run_id = ?"
                                         [run-id])
                              [:artifact_contract_id :artifact_contract_version])))
          (is (= {:validator_id ":validator/cve-bundle"
                  :validator_version 1
                  :status ":assessment/accepted"}
                 (select-keys (query-one db-path
                                         "SELECT validator_id, validator_version, status FROM assessments WHERE run_id = ?"
                                         [run-id])
                              [:validator_id :validator_version :status])))
          (is (= {:disposition_type ":disposition/accepted"}
                 (select-keys (query-one db-path
                                         "SELECT disposition_type FROM dispositions WHERE run_id = ?"
                                         [run-id])
                              [:disposition_type]))))
        (testing "re-running scheduler once leaves the converged happy path unchanged"
          (let [step-result (scheduler/run-scheduler-step db-path)
                task-after (scheduler/inspect-task! db-path task-id)
                run-after (scheduler/inspect-run! db-path run-id)]
            (is (empty? (:created-runs step-result)))
            (is (= :task.state/completed (:task/state task-after)))
            (is (= :run.state/finalized (:run/state run-after)))
            (is (= 8 (:run/event-count run-after)))))))))

(deftest rejected-validation-is-recorded-once-per-run
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock/*artifact-root-dir* artifacts-dir
              runtime.mock/*run-root-dir* runs-dir]
      (let [task (enqueue-demo-task! db-path)
            task-id (:task/id task)
            _ (scheduler/run-scheduler-step db-path)
            run-id (:run_id (query-one db-path
                                       "SELECT run_id FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1"
                                       [task-id]))
            run-before-validation (scheduler/inspect-run! db-path run-id)
            artifact-root (:run/artifact-root run-before-validation)]
        (testing "a rejected validation converges after the first scheduler pass"
          (is (.delete (io/file artifact-root "notes.md")))
          (let [first-step (scheduler/run-scheduler-step db-path)
                second-step (scheduler/run-scheduler-step db-path)
                task-after (scheduler/inspect-task! db-path task-id)
                run-after (scheduler/inspect-run! db-path run-id)]
            (is (empty? (:created-runs first-step)))
            (is (empty? (:created-runs second-step)))
            (is (= :task.state/awaiting-validation (:task/state task-after)))
            (is (= :run.state/awaiting-validation (:run/state run-after)))
            (is (= 6 (:run/event-count run-after)))
            (is (= {:status ":assessment/rejected"
                    :item_count 1}
                   (select-keys (query-one db-path
                                           "SELECT status, COUNT(*) AS item_count FROM assessments WHERE run_id = ? GROUP BY status"
                                           [run-id])
                                [:status :item_count])))
            (is (= {:disposition_type ":disposition/rejected"
                    :item_count 1}
                   (select-keys (query-one db-path
                                           (str "SELECT disposition_type, COUNT(*) AS item_count "
                                                "FROM dispositions WHERE run_id = ? GROUP BY disposition_type")
                                           [run-id])
                                [:disposition_type :item_count])))))))))

(deftest unsupported-runtime-adapter-does-not-persist-a-leased-run
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock/*artifact-root-dir* artifacts-dir
              runtime.mock/*run-root-dir* runs-dir]
      (let [task (enqueue-codex-task! db-path)
            task-id (:task/id task)]
        (testing "scheduler records adapter resolution failures and leaves the task runnable"
          (let [step-result (scheduler/run-scheduler-step db-path)]
            (is (= [{:task/id task-id
                     :task/work-key (:task/work-key task)
                     :error/message "Unsupported runtime adapter :runtime.adapter/codex"
                     :error/data {:adapter-id :runtime.adapter/codex}}]
                   (:task-errors step-result))))
          (is (= :task.state/queued
                 (:task/state (scheduler/inspect-task! db-path task-id))))
          (is (= 0
                 (:item_count (query-one db-path
                                         "SELECT COUNT(*) AS item_count FROM runs WHERE task_id = ?"
                                         [task-id])))))))))

(deftest startup-failure-recovers-leased-state-and-allows-a-retry
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)
        fail-once? (atom true)
        delegate (runtime.mock/mock-runtime)]
    (binding [runtime.mock/*artifact-root-dir* artifacts-dir
              runtime.mock/*run-root-dir* runs-dir]
      (let [task (enqueue-demo-task! db-path)
            task-id (:task/id task)
            failing-adapter (reify runtime.protocol/RuntimeAdapter
                              (adapter-id [_]
                                (runtime.protocol/adapter-id delegate))
                              (prepare-run! [_ ctx task run]
                                (if (compare-and-set! fail-once? true false)
                                  (throw (ex-info "synthetic startup failure"
                                                  {:run-id (:run/id run)}))
                                  (runtime.protocol/prepare-run! delegate ctx task run)))
                              (dispatch-run! [_ ctx task run]
                                (runtime.protocol/dispatch-run! delegate ctx task run))
                              (poll-run! [_ ctx run now]
                                (runtime.protocol/poll-run! delegate ctx run now))
                              (cancel-run! [_ ctx run reason]
                                (runtime.protocol/cancel-run! delegate ctx run reason)))]
        (with-redefs [runtime.registry/runtime-adapter (fn [_] failing-adapter)]
          (testing "the first startup failure is recovered back to a runnable task"
            (let [first-step (scheduler/run-scheduler-step db-path)]
              (is (= 1 (count (:task-errors first-step))))
              (is (= {:task/id task-id
                      :task/work-key (:task/work-key task)
                      :error/message "synthetic startup failure"}
                     (select-keys (first (:task-errors first-step))
                                  [:task/id :task/work-key :error/message])))
              (is (string? (get-in first-step [:task-errors 0 :error/data :run-id]))))
            (is (= :task.state/queued
                   (:task/state (scheduler/inspect-task! db-path task-id))))
            (is (= {:attempt 1
                    :state ":run.state/finalized"}
                   (select-keys (query-one db-path
                                           "SELECT attempt, state FROM runs WHERE task_id = ? ORDER BY attempt ASC LIMIT 1"
                                           [task-id])
                                [:attempt :state])))
            (is (= {:state ":lease.state/released"}
                   (select-keys (query-one db-path
                                           (str "SELECT l.state AS state "
                                                "FROM leases l JOIN runs r ON r.lease_id = l.lease_id "
                                                "WHERE r.task_id = ? ORDER BY r.attempt ASC LIMIT 1")
                                           [task-id])
                                [:state]))))
          (testing "a later scheduler pass can create a fresh run and complete the task"
            (let [second-step (scheduler/run-scheduler-step db-path)
                  _ (scheduler/run-scheduler-step db-path)
                  latest-run-id (:run_id (query-one db-path
                                                   "SELECT run_id FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1"
                                                   [task-id]))
                  task-after (scheduler/inspect-task! db-path task-id)
                  run-after (scheduler/inspect-run! db-path latest-run-id)]
              (is (= 1 (count (:created-runs second-step))))
              (is (empty? (:task-errors second-step)))
              (is (= 2
                     (:item_count (query-one db-path
                                             "SELECT COUNT(*) AS item_count FROM runs WHERE task_id = ?"
                                             [task-id]))))
              (is (= :task.state/completed (:task/state task-after)))
              (is (= :run.state/finalized (:run/state run-after))))))))))

(deftest mock-runtime-prepares-each-run-once
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)
        task (enqueue-demo-task! db-path)
        metadata-writes (atom [])
        original-write-edn! @#'meta-flow.runtime.mock/write-edn-file!]
    (binding [runtime.mock/*artifact-root-dir* artifacts-dir
              runtime.mock/*run-root-dir* runs-dir]
      (with-redefs-fn {#'meta-flow.runtime.mock/write-edn-file!
                       (fn [path value]
                         (swap! metadata-writes conj path)
                         (original-write-edn! path value))}
        (fn []
          (let [step-result (scheduler/run-scheduler-step db-path)
                expected-prefix (str runs-dir "/")]
            (is (= 1 (count (:created-runs step-result))))
            (is (empty? (:task-errors step-result)))
            (is (= 4
                   (count (filter #(str/starts-with? % expected-prefix)
                                  @metadata-writes))))
            (is (= #{"definitions.edn" "task.edn" "run.edn" "runtime-profile.edn"}
                   (set (map #(last (str/split % #"/"))
                             (filter #(str/starts-with? % expected-prefix)
                                     @metadata-writes)))))))))))

(deftest stale-runnable-ids-do-not-consume-dispatch-capacity
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock/*artifact-root-dir* artifacts-dir
              runtime.mock/*run-root-dir* runs-dir]
      (let [task (enqueue-demo-task! db-path)
            task-id (:task/id task)]
        (with-redefs [projection/list-runnable-task-ids (fn [_ _ _]
                                                          ["missing-task-id" task-id])]
          (let [step-result (scheduler/run-scheduler-step db-path)]
            (is (= 1 (count (:created-runs step-result))))
            (is (empty? (:task-errors step-result)))
            (is (= 1
                   (:item_count (query-one db-path
                                           "SELECT COUNT(*) AS item_count FROM runs WHERE task_id = ?"
                                           [task-id]))))
            (is (= :task.state/awaiting-validation
                   (:task/state (scheduler/inspect-task! db-path task-id))))))))))

(deftest scheduler-continues-past-runnable-tasks-that-fail-to-dispatch
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock/*artifact-root-dir* artifacts-dir
              runtime.mock/*run-root-dir* runs-dir]
      (let [blocked-task (enqueue-codex-task! db-path)
            runnable-task (enqueue-demo-task! db-path)]
        (with-redefs [projection/list-runnable-task-ids (fn [_ _ _]
                                                          [(:task/id blocked-task)
                                                           (:task/id runnable-task)])]
          (let [step-result (scheduler/run-scheduler-step db-path)
                run-id (:run_id (query-one db-path
                                           "SELECT run_id FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1"
                                           [(:task/id runnable-task)]))]
            (is (= 1 (count (:created-runs step-result))))
            (is (= [{:task/id (:task/id blocked-task)
                     :task/work-key (:task/work-key blocked-task)
                     :error/message "Unsupported runtime adapter :runtime.adapter/codex"
                     :error/data {:adapter-id :runtime.adapter/codex}}]
                   (:task-errors step-result)))
            (is (= :task.state/queued
                   (:task/state (scheduler/inspect-task! db-path (:task/id blocked-task)))))
            (is (= :task.state/awaiting-validation
                   (:task/state (scheduler/inspect-task! db-path (:task/id runnable-task)))))
            (is (= :run.state/awaiting-validation
                   (:run/state (scheduler/inspect-run! db-path run-id))))))))))

(deftest demo-happy-path-isolated-from-shared-queue-failures
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock/*artifact-root-dir* artifacts-dir
              runtime.mock/*run-root-dir* runs-dir]
      (enqueue-codex-task! db-path)
      (let [{:keys [task run scheduler-steps]} (scheduler/demo-happy-path! db-path)]
        (is (= 2 scheduler-steps))
        (is (= :task.state/completed (:task/state task)))
        (is (= :run.state/finalized (:run/state run)))))))
