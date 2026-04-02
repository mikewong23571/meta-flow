(ns meta-flow.scheduler.recovery-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.control.events :as events]
            [meta-flow.control.projection :as projection]
            [meta-flow.runtime.mock :as runtime.mock]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.runtime.registry :as runtime.registry]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.test-support :as support]
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
            (is (= :task.state/retryable-failed (:task/state task-after-second)))
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

(deftest unsupported-runtime-adapter-does-not-persist-a-leased-run
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [task (support/enqueue-codex-task! db-path)
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
                 (:item_count (support/query-one db-path
                                                 "SELECT COUNT(*) AS item_count FROM runs WHERE task_id = ?"
                                                 [task-id])))))))))

(deftest startup-failure-recovers-leased-state-and-allows-a-retry
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        fail-once? (atom true)
        delegate (runtime.mock/mock-runtime)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [task (support/enqueue-demo-task! db-path)
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
                   (select-keys (support/query-one db-path
                                                   "SELECT attempt, state FROM runs WHERE task_id = ? ORDER BY attempt ASC LIMIT 1"
                                                   [task-id])
                                [:attempt :state])))
            (is (= {:state ":lease.state/released"}
                   (select-keys (support/query-one db-path
                                                   (str "SELECT l.state AS state "
                                                        "FROM leases l JOIN runs r ON r.lease_id = l.lease_id "
                                                        "WHERE r.task_id = ? ORDER BY r.attempt ASC LIMIT 1")
                                                   [task-id])
                                [:state]))))
          (testing "a later scheduler pass can create a fresh run and complete the task"
            (let [second-step (scheduler/run-scheduler-step db-path)
                  _ (support/advance-scheduler! db-path 4)
                  latest-run-id (:run_id (support/query-one db-path
                                                            "SELECT run_id FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1"
                                                            [task-id]))
                  task-after (scheduler/inspect-task! db-path task-id)
                  run-after (scheduler/inspect-run! db-path latest-run-id)]
              (is (= 1 (count (:created-runs second-step))))
              (is (empty? (:task-errors second-step)))
              (is (= 2
                     (:item_count (support/query-one db-path
                                                     "SELECT COUNT(*) AS item_count FROM runs WHERE task_id = ?"
                                                     [task-id]))))
              (is (= :task.state/completed (:task/state task-after)))
              (is (= :run.state/finalized (:run/state run-after))))))))))

(deftest startup-failure-after-an-emitted-event-does-not-compensate-the-claim
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        fail-once? (atom true)
        delegate (runtime.mock/mock-runtime)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [task (support/enqueue-demo-task! db-path)
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
                run-id (:run_id (support/query-one db-path
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
                     (select-keys (support/query-one db-path
                                                     "SELECT state FROM leases WHERE lease_id = (SELECT lease_id FROM runs WHERE run_id = ?)"
                                                     [run-id])
                                  [:state])))
              (is (= 1
                     (:run/last-applied-event-seq (scheduler/inspect-run! db-path run-id))))
              (is (= [events/run-dispatched]
                     (mapv :event/type
                           (store.protocol/list-run-events (store.sqlite/sqlite-state-store db-path) run-id)))))
            (testing "later scheduler passes can continue from the partially-started run"
              (support/advance-scheduler! db-path 4)
              (is (= :task.state/completed
                     (:task/state (scheduler/inspect-task! db-path task-id))))
              (is (= :run.state/finalized
                     (:run/state (scheduler/inspect-run! db-path run-id)))))))))))
