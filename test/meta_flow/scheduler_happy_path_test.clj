(ns meta-flow.scheduler-happy-path-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.events :as events]
            [meta-flow.runtime.mock :as runtime.mock]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.runtime.registry :as runtime.registry]
            [meta-flow.projection :as projection]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.state :as scheduler.state]
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

(defn- create-expired-leased-run!
  [db-path task]
  (let [store (store.sqlite/sqlite-state-store db-path)
        run-id (str "run-" (java.util.UUID/randomUUID))
        lease-id (str "lease-" (java.util.UUID/randomUUID))
        created-at "2026-04-01T00:00:00Z"
        leased-at "2026-04-01T00:01:00Z"
        run {:run/id run-id
             :run/attempt 1
             :run/run-fsm-ref (:task/run-fsm-ref task)
             :run/runtime-profile-ref (:task/runtime-profile-ref task)
             :run/state :run.state/leased
             :run/created-at created-at
             :run/updated-at created-at}
        lease {:lease/id lease-id
               :lease/run-id run-id
               :lease/token (str lease-id "-token")
               :lease/state :lease.state/active
               :lease/expires-at "2026-04-01T00:05:00Z"
               :lease/created-at created-at
               :lease/updated-at created-at}]
    (store.protocol/create-run! store task run lease)
    (store.protocol/transition-task! store (:task/id task)
                                     {:transition/from :task.state/queued
                                      :transition/to :task.state/leased}
                                     leased-at)
    {:run-id run-id
     :lease-id lease-id}))

(defn- advance-scheduler!
  [db-path steps]
  (dotimes [_ steps]
    (scheduler/run-scheduler-step db-path)))

(deftest demo-happy-path-completes-and-persists-structured-control-data
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [{:keys [task run artifact-root scheduler-steps]} (scheduler/demo-happy-path! db-path)
            task-id (:task/id task)
            run-id (:run/id run)
            task-view (scheduler/inspect-task! db-path task-id)
            run-view (scheduler/inspect-run! db-path run-id)
            collection-view (scheduler/inspect-collection! db-path)]
        (testing "the demo converges to the Phase 1 happy path terminal states"
          (is (= 5 scheduler-steps))
          (is (= :task.state/completed (:task/state task)))
          (is (= :run.state/finalized (:run/state run)))
          (is (= 9 (:run/event-count run-view)))
          (is (string? (:run/last-heartbeat run-view))))
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
        (testing "dispatch persists a durable execution handle on the run"
          (is (= "polling"
                 (get-in run-view [:run/execution-handle :runtime-run/dispatch])))
          (is (str/starts-with? (get-in run-view [:run/execution-handle :runtime-run/workdir])
                                (str runs-dir "/"))))
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
            (is (= 9 (:run/event-count run-after)))
            (is (= (:run/last-heartbeat run-view)
                   (:run/last-heartbeat run-after)))))))))

(deftest demo-retry-path-rejects-the-artifact-and-records-the-current-decision
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [{:keys [task run artifact-root assessment disposition scheduler-steps]}
            (scheduler/demo-retry-path! db-path)
            run-view (scheduler/inspect-run! db-path (:run/id run))]
        (is (= 5 scheduler-steps))
        (is (= :task.state/retryable-failed (:task/state task)))
        (is (= :run.state/retryable-failed (:run/state run)))
        (is (= :assessment/rejected (:assessment/outcome assessment)))
        (is (= :disposition/rejected (:disposition/action disposition)))
        (is (str/includes? (:assessment/notes assessment) "notes.md"))
        (is (= artifact-root
               (:run/artifact-root run-view)))
        (is (= 1
               (:item_count (query-one db-path
                                       (str "SELECT COUNT(*) AS item_count "
                                            "FROM assessments WHERE run_id = ? AND assessment_key = ?")
                                       [(:run/id run) "validation/current"]))))
        (is (= 1
               (:item_count (query-one db-path
                                       (str "SELECT COUNT(*) AS item_count "
                                            "FROM dispositions WHERE run_id = ? AND disposition_key = ?")
                                       [(:run/id run) "decision/current"]))))))))

(deftest demo-commands-converge-even-when-earlier-queued-mock-work-exists
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (enqueue-demo-task! db-path)
      (let [{happy-task :task
             happy-run :run
             happy-steps :scheduler-steps} (scheduler/demo-happy-path! db-path)
            _ (enqueue-demo-task! db-path)
            {retry-task :task
             retry-run :run
             retry-steps :scheduler-steps} (scheduler/demo-retry-path! db-path)]
        (is (> happy-steps 5))
        (is (> retry-steps 5))
        (is (= :task.state/completed (:task/state happy-task)))
        (is (= :run.state/finalized (:run/state happy-run)))
        (is (= :task.state/retryable-failed (:task/state retry-task)))
        (is (= :run.state/retryable-failed (:run/state retry-run)))))))

(deftest rejected-validation-is-recorded-once-per-run
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [task (enqueue-demo-task! db-path)
            task-id (:task/id task)
            _ (advance-scheduler! db-path 4)
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
            (is (= :task.state/retryable-failed (:task/state task-after)))
            (is (= :run.state/retryable-failed (:run/state run-after)))
            (is (= 9 (:run/event-count run-after)))
            (is (string? (:run/last-heartbeat run-after)))
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
                                [:disposition_type :item_count])))
            (is (= [events/run-dispatched
                    events/task-worker-started
                    events/run-worker-started
                    events/run-worker-heartbeat
                    events/run-worker-exited
                    events/run-artifact-ready
                    events/task-artifact-ready
                    events/run-assessment-rejected
                    events/task-assessment-rejected]
                   (mapv :event/type
                         (store.protocol/list-run-events (store.sqlite/sqlite-state-store db-path) run-id))))))))))

(deftest awaiting-validation-retries-reuse-the-same-assessment-and-disposition-records
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [task (enqueue-demo-task! db-path)
            task-id (:task/id task)
            _ (advance-scheduler! db-path 4)
            run-id (:run_id (query-one db-path
                                       "SELECT run_id FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1"
                                       [task-id]))]
        (with-redefs-fn {#'scheduler.state/emit-event! (fn [& _] nil)}
          (fn []
            (scheduler/run-scheduler-step db-path)
            (let [task-before (scheduler/inspect-task! db-path task-id)
                  run-before (scheduler/inspect-run! db-path run-id)]
              (testing "the seed scheduler step reaches awaiting-validation"
                (is (= :task.state/awaiting-validation
                       (:task/state task-before)))
                (is (= :run.state/awaiting-validation
                       (:run/state run-before))))
              (scheduler/run-scheduler-step db-path)
              (scheduler/run-scheduler-step db-path)
              (testing "repeated scheduler passes reuse one semantic validation round"
                (is (= {:item_count 1
                        :assessment_key "validation/current"
                        :status ":assessment/accepted"}
                       (select-keys (query-one db-path
                                               (str "SELECT COUNT(*) AS item_count, assessment_key, status "
                                                    "FROM assessments WHERE run_id = ? GROUP BY assessment_key, status")
                                               [run-id])
                                    [:item_count :assessment_key :status])))
                (is (= {:item_count 1
                        :disposition_key "decision/current"
                        :disposition_type ":disposition/accepted"}
                       (select-keys (query-one db-path
                                               (str "SELECT COUNT(*) AS item_count, disposition_key, disposition_type "
                                                    "FROM dispositions WHERE run_id = ? GROUP BY disposition_key, disposition_type")
                                               [run-id])
                                    [:item_count :disposition_key :disposition_type])))
                (is (= :task.state/awaiting-validation
                       (:task/state (scheduler/inspect-task! db-path task-id))))
                (is (= :run.state/awaiting-validation
                       (:run/state (scheduler/inspect-run! db-path run-id))))
                (is (= (:task/updated-at task-before)
                       (:updated_at (query-one db-path
                                               "SELECT updated_at FROM tasks WHERE task_id = ?"
                                               [task-id]))))
                (is (= (:run/updated-at run-before)
                       (:updated_at (query-one db-path
                                               "SELECT updated_at FROM runs WHERE run_id = ?"
                                               [run-id]))))))))))))

(deftest expired-lease-recovery-releases-the-lease-and-stops-reprocessing
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)
        task (enqueue-demo-task! db-path)
        task-id (:task/id task)
        {:keys [run-id lease-id]} (create-expired-leased-run! db-path task)
        store (store.sqlite/sqlite-state-store db-path)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [first-step (scheduler/run-scheduler-step db-path)
            task-after-first (scheduler/inspect-task! db-path task-id)
            run-after-first (scheduler/inspect-run! db-path run-id)
            first-events (store.protocol/list-run-events store run-id)]
        (testing "the first scheduler step converts the expired lease into retryable failure"
          (is (empty? (:created-runs first-step)))
          (is (empty? (:task-errors first-step)))
          (is (= :task.state/retryable-failed (:task/state task-after-first)))
          (is (= :run.state/retryable-failed (:run/state run-after-first)))
          (is (= {:state ":lease.state/released"}
                 (select-keys (query-one db-path
                                         "SELECT state FROM leases WHERE lease_id = ?"
                                         [lease-id])
                              [:state])))
          (is (= [events/run-lease-expired
                  events/task-lease-expired]
                 (mapv :event/type first-events))))
        (testing "a later scheduler step does not re-emit expiry events"
          (let [second-step (scheduler/run-scheduler-step db-path)
                task-after-second (scheduler/inspect-task! db-path task-id)
                run-after-second (scheduler/inspect-run! db-path run-id)
                second-events (store.protocol/list-run-events store run-id)]
            (is (empty? (:created-runs second-step)))
            (is (empty? (:task-errors second-step)))
            (is (= :task.state/retryable-failed (:task/state task-after-second)))
            (is (= :run.state/retryable-failed (:run/state run-after-second)))
            (is (= first-events second-events))))))))

(deftest expired-lease-recovery-rolls-back-when-atomic-recovery-fails
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)
        task (enqueue-demo-task! db-path)
        task-id (:task/id task)
        {:keys [run-id lease-id]} (create-expired-leased-run! db-path task)
        store (store.sqlite/sqlite-state-store db-path)
        reader (projection/sqlite-projection-reader db-path)
        original-ingest! store.sqlite/ingest-run-event-via-connection!]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (testing "a failure in the recovery transaction leaves the lease and states untouched"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"synthetic ingest failure"
                              (with-redefs-fn {#'meta-flow.store.sqlite/ingest-run-event-via-connection!
                                               (fn [connection event-intent]
                                                 (if (= events/task-lease-expired (:event/type event-intent))
                                                   (throw (ex-info "synthetic ingest failure"
                                                                   {:event-intent event-intent}))
                                                   (original-ingest! connection event-intent)))}
                                (fn []
                                  (scheduler/run-scheduler-step db-path)))))
        (is (= :task.state/leased
               (:task/state (scheduler/inspect-task! db-path task-id))))
        (is (= :run.state/leased
               (:run/state (scheduler/inspect-run! db-path run-id))))
        (is (= {:state ":lease.state/active"}
               (select-keys (query-one db-path
                                       "SELECT state FROM leases WHERE lease_id = ?"
                                       [lease-id])
                            [:state])))
        (is (= 0
               (:item_count (query-one db-path
                                       "SELECT COUNT(*) AS item_count FROM run_events WHERE run_id = ?"
                                       [run-id]))))
        (is (= [run-id]
               (projection/list-expired-lease-run-ids reader "2026-04-01T00:06:00Z" 10))))
      (testing "the same expired lease can be recovered on a later scheduler pass"
        (let [step-result (scheduler/run-scheduler-step db-path)]
          (is (empty? (:created-runs step-result)))
          (is (empty? (:task-errors step-result)))
          (is (= :task.state/retryable-failed
                 (:task/state (scheduler/inspect-task! db-path task-id))))
          (is (= :run.state/retryable-failed
                 (:run/state (scheduler/inspect-run! db-path run-id))))
          (is (= {:state ":lease.state/released"}
                 (select-keys (query-one db-path
                                         "SELECT state FROM leases WHERE lease_id = ?"
                                         [lease-id])
                              [:state])))
          (is (= [events/run-lease-expired
                  events/task-lease-expired]
                 (mapv :event/type (store.protocol/list-run-events store run-id)))))))))

(deftest unsupported-runtime-adapter-does-not-persist-a-leased-run
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
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
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
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
                  _ (advance-scheduler! db-path 4)
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

(deftest startup-failure-after-an-emitted-event-does-not-compensate-the-claim
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)
        fail-once? (atom true)
        delegate (runtime.mock/mock-runtime)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [task (enqueue-demo-task! db-path)
            task-id (:task/id task)
            eventful-adapter (reify runtime.protocol/RuntimeAdapter
                               (adapter-id [_]
                                 (runtime.protocol/adapter-id delegate))
                               (prepare-run! [_ ctx task run]
                                 (runtime.protocol/prepare-run! delegate ctx task run))
                               (dispatch-run! [_ ctx task run]
                                 (let [result (runtime.protocol/dispatch-run! delegate ctx task run)]
                                   (if (compare-and-set! fail-once? true false)
                                     (throw (ex-info "synthetic post-dispatch failure"
                                                     {:run-id (:run/id run)}))
                                     result)))
                               (poll-run! [_ ctx run now]
                                 (runtime.protocol/poll-run! delegate ctx run now))
                               (cancel-run! [_ ctx run reason]
                                 (runtime.protocol/cancel-run! delegate ctx run reason)))]
        (with-redefs [runtime.registry/runtime-adapter (fn [_] eventful-adapter)]
          (let [first-step (scheduler/run-scheduler-step db-path)
                run-id (:run_id (query-one db-path
                                           "SELECT run_id FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1"
                                           [task-id]))]
            (testing "the first failure replays emitted startup events so the run does not stay parked in leased"
              (is (= 1 (count (:task-errors first-step))))
              (is (= "synthetic post-dispatch failure"
                     (get-in first-step [:task-errors 0 :error/message])))
              (is (= :task.state/leased
                     (:task/state (scheduler/inspect-task! db-path task-id))))
              (is (= :run.state/dispatched
                     (:run/state (scheduler/inspect-run! db-path run-id))))
              (is (= {:state ":lease.state/active"}
                     (select-keys (query-one db-path
                                             "SELECT state FROM leases WHERE lease_id = (SELECT lease_id FROM runs WHERE run_id = ?)"
                                             [run-id])
                                  [:state])))
              (is (= 1
                     (:run/last-applied-event-seq (scheduler/inspect-run! db-path run-id))))
              (is (= [events/run-dispatched]
                     (mapv :event/type
                           (store.protocol/list-run-events (store.sqlite/sqlite-state-store db-path) run-id)))))
            (testing "later scheduler passes can continue from the partially-started run"
              (advance-scheduler! db-path 4)
              (is (= :task.state/completed
                     (:task/state (scheduler/inspect-task! db-path task-id))))
              (is (= :run.state/finalized
                     (:run/state (scheduler/inspect-run! db-path run-id)))))))))))

(deftest mock-runtime-prepares-each-run-once
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)
        _ (enqueue-demo-task! db-path)
        metadata-writes (atom [])
        original-write-edn! @#'meta-flow.runtime.mock.fs/write-edn-file!]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (with-redefs-fn {#'meta-flow.runtime.mock.fs/write-edn-file!
                       (fn [path value]
                         (swap! metadata-writes conj path)
                         (original-write-edn! path value))}
        (fn []
          (let [step-result (scheduler/run-scheduler-step db-path)
                expected-prefix (str runs-dir "/")]
            (is (= 1 (count (:created-runs step-result))))
            (is (empty? (:task-errors step-result)))
            (is (= 6
                   (count (filter #(str/starts-with? % expected-prefix)
                                  @metadata-writes))))
            (is (= #{"definitions.edn" "task.edn" "run.edn" "runtime-profile.edn" "runtime-state.edn"}
                   (set (map #(last (str/split % #"/"))
                             (filter #(str/starts-with? % expected-prefix)
                                     @metadata-writes)))))))))))

(deftest mock-runtime-polls-progressively-and-can-be-cancelled
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)
        store (store.sqlite/sqlite-state-store db-path)
        adapter (runtime.mock/mock-runtime)
        task (enqueue-demo-task! db-path)
        now "2026-04-01T00:00:00Z"
        {:keys [run]} (store.protocol/create-run! store
                                                  task
                                                  {:run/id "run-runtime-test"
                                                   :run/attempt 1
                                                   :run/run-fsm-ref (:task/run-fsm-ref task)
                                                   :run/runtime-profile-ref (:task/runtime-profile-ref task)
                                                   :run/state :run.state/leased
                                                   :run/created-at now
                                                   :run/updated-at now}
                                                  {:lease/id "lease-runtime-test"
                                                   :lease/run-id "run-runtime-test"
                                                   :lease/token "lease-runtime-test-token"
                                                   :lease/state :lease.state/active
                                                   :lease/expires-at "2026-04-01T00:30:00Z"
                                                   :lease/created-at now
                                                   :lease/updated-at now})
        ctx {:db-path db-path
             :store store
             :repository (defs.loader/filesystem-definition-repository)
             :now now}]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (runtime.protocol/prepare-run! adapter ctx task run)
      (runtime.protocol/dispatch-run! adapter ctx task run)
      (testing "poll advances the mock run in phases"
        (is (= [events/run-dispatched]
               (mapv :event/type (store.protocol/list-run-events store (:run/id run)))))
        (is (= [events/task-worker-started
                events/run-worker-started]
               (mapv :event/type (runtime.protocol/poll-run! adapter ctx run now))))
        (is (= [events/run-worker-heartbeat]
               (mapv :event/type (runtime.protocol/poll-run! adapter ctx run now))))
        (is (= [events/run-worker-exited]
               (mapv :event/type (runtime.protocol/poll-run! adapter ctx run now))))
        (is (= [events/run-artifact-ready
                events/task-artifact-ready]
               (mapv :event/type (runtime.protocol/poll-run! adapter ctx run now))))
        (is (empty? (runtime.protocol/poll-run! adapter ctx run now))))
      (testing "cancel requests convert the next poll into an exit without artifacts"
        (let [cancel-task (enqueue-demo-task! db-path)
              {:keys [run]} (store.protocol/create-run! store
                                                        cancel-task
                                                        {:run/id "run-runtime-cancel"
                                                         :run/attempt 2
                                                         :run/run-fsm-ref (:task/run-fsm-ref cancel-task)
                                                         :run/runtime-profile-ref (:task/runtime-profile-ref cancel-task)
                                                         :run/state :run.state/leased
                                                         :run/created-at "2026-04-01T00:01:00Z"
                                                         :run/updated-at "2026-04-01T00:01:00Z"}
                                                        {:lease/id "lease-runtime-cancel"
                                                         :lease/run-id "run-runtime-cancel"
                                                         :lease/token "lease-runtime-cancel-token"
                                                         :lease/state :lease.state/active
                                                         :lease/expires-at "2026-04-01T00:31:00Z"
                                                         :lease/created-at "2026-04-01T00:01:00Z"
                                                         :lease/updated-at "2026-04-01T00:01:00Z"})
              cancel-ctx (assoc ctx :now "2026-04-01T00:01:00Z")]
          (runtime.protocol/prepare-run! adapter cancel-ctx cancel-task run)
          (runtime.protocol/dispatch-run! adapter cancel-ctx cancel-task run)
          (is (= {:status :cancel-requested}
                 (runtime.protocol/cancel-run! adapter cancel-ctx run {:reason :test/cancel})))
          (is (= [events/run-worker-exited]
                 (mapv :event/type (runtime.protocol/poll-run! adapter cancel-ctx run "2026-04-01T00:02:00Z"))))
          (is (empty? (runtime.protocol/poll-run! adapter cancel-ctx run "2026-04-01T00:03:00Z")))
          (is (= 1
                 (:item_count (query-one db-path
                                         "SELECT COUNT(*) AS item_count FROM artifacts WHERE run_id = ?"
                                         ["run-runtime-test"]))))
          (is (= 0
                 (:item_count (query-one db-path
                                         "SELECT COUNT(*) AS item_count FROM artifacts WHERE run_id = ?"
                                         ["run-runtime-cancel"])))))))))

(deftest stale-runnable-ids-do-not-consume-dispatch-capacity
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
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
            (is (= :task.state/leased
                   (:task/state (scheduler/inspect-task! db-path task-id))))))))))

(deftest scheduler-continues-past-runnable-tasks-that-fail-to-dispatch
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
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
            (is (= :task.state/leased
                   (:task/state (scheduler/inspect-task! db-path (:task/id runnable-task)))))
            (is (= :run.state/dispatched
                   (:run/state (scheduler/inspect-run! db-path run-id))))))))))

(deftest demo-happy-path-isolated-from-shared-queue-failures
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (enqueue-codex-task! db-path)
      (let [{:keys [task run scheduler-steps]} (scheduler/demo-happy-path! db-path)]
        (is (= 5 scheduler-steps))
        (is (= :task.state/completed (:task/state task)))
        (is (= :run.state/finalized (:run/state run)))))))

(deftest demo-happy-path-runs-through-public-scheduler-step
  (let [{:keys [db-path artifacts-dir runs-dir]} (temp-system)
        scheduler-calls (atom 0)
        original-run-scheduler-step @#'scheduler/run-scheduler-step]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (with-redefs [scheduler/run-scheduler-step (fn [db-path*]
                                                   (swap! scheduler-calls inc)
                                                   (original-run-scheduler-step db-path*))]
        (let [{:keys [task run scheduler-steps]} (scheduler/demo-happy-path! db-path)]
          (is (= 5 scheduler-steps))
          (is (= 5 @scheduler-calls))
          (is (= :task.state/completed (:task/state task)))
          (is (= :run.state/finalized (:run/state run))))))))
