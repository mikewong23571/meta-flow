(ns meta-flow.scheduler.recovery.lease-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.control.events :as events]
            [meta-flow.control.projection :as projection]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest expired-lease-recovery-releases-the-lease-and-stops-reprocessing
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        task (support/enqueue-demo-task! db-path)
        task-id (:task/id task)
        {:keys [run-id lease-id]} (support/create-expired-leased-run! db-path task)
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
                 (select-keys (support/query-one db-path
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
            (is (= [task-id] (:requeued-task-ids second-step)))
            (is (= :task.state/queued (:task/state task-after-second)))
            (is (= :run.state/retryable-failed (:run/state run-after-second)))
            (is (= first-events second-events))))))))

(deftest expired-lease-recovery-rolls-back-when-atomic-recovery-fails
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        task (support/enqueue-demo-task! db-path)
        task-id (:task/id task)
        {:keys [run-id lease-id]} (support/create-expired-leased-run! db-path task)
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
               (select-keys (support/query-one db-path
                                               "SELECT state FROM leases WHERE lease_id = ?"
                                               [lease-id])
                            [:state])))
        (is (= 0
               (:item_count (support/query-one db-path
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
                 (select-keys (support/query-one db-path
                                                 "SELECT state FROM leases WHERE lease_id = ?"
                                                 [lease-id])
                              [:state])))
          (is (= [events/run-lease-expired
                  events/task-lease-expired]
                 (mapv :event/type (store.protocol/list-run-events store run-id)))))))))
