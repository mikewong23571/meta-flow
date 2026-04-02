(ns meta-flow.scheduler.retry-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest retryable-failed-task-is-requeued-on-a_later_scheduler_pass
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        store (store.sqlite/sqlite-state-store db-path)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [{:keys [task run]} (scheduler/demo-retry-path! db-path)
            task-id (:task/id task)
            failed-run-id (:run/id run)
            first-retry-step (scheduler/run-scheduler-step db-path)
            task-after-requeue (store.protocol/find-task store task-id)
            failed-run-after-requeue (scheduler/inspect-run! db-path failed-run-id)]
        (testing "the first post-failure scheduler pass records an explicit requeue without starting attempt 2"
          (is (empty? (:created-runs first-retry-step)))
          (is (= [task-id] (:requeued-task-ids first-retry-step)))
          (is (empty? (:escalated-task-ids first-retry-step)))
          (is (= :task.state/queued (:task/state task-after-requeue)))
          (is (zero? (long (or (:task/last-applied-event-seq task-after-requeue) 0))))
          (is (= :run.state/retryable-failed (:run/state failed-run-after-requeue))))
        (testing "a later scheduler pass can claim the queued task and create attempt 2"
          (let [dispatch-step (scheduler/run-scheduler-step db-path)
                latest-run-id (:run_id (support/query-one db-path
                                                          "SELECT run_id FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1"
                                                          [task-id]))
                latest-run (scheduler/inspect-run! db-path latest-run-id)
                task-after-dispatch (scheduler/inspect-task! db-path task-id)]
            (is (= 1 (count (:created-runs dispatch-step))))
            (is (empty? (:requeued-task-ids dispatch-step)))
            (is (empty? (:escalated-task-ids dispatch-step)))
            (is (= 2 (:run/attempt latest-run)))
            (is (= :task.state/leased (:task/state task-after-dispatch)))
            (is (= :run.state/dispatched (:run/state latest-run)))
            (support/advance-scheduler! db-path 4)
            (is (= :task.state/completed
                   (:task/state (scheduler/inspect-task! db-path task-id))))
            (is (= :run.state/finalized
                   (:run/state (scheduler/inspect-run! db-path latest-run-id))))))))))

(deftest retryable-failed-task-escalates-when-max-attempts-are-exhausted
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        store (store.sqlite/sqlite-state-store db-path)
        task (support/enqueue-demo-task! db-path)
        task-id (:task/id task)
        now "2026-04-01T00:00:00Z"]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (store.protocol/create-run! store task
                                  {:run/id "run-attempt-1"
                                   :run/attempt 1
                                   :run/run-fsm-ref (:task/run-fsm-ref task)
                                   :run/runtime-profile-ref (:task/runtime-profile-ref task)
                                   :run/state :run.state/leased
                                   :run/created-at now
                                   :run/updated-at now}
                                  {:lease/id "lease-attempt-1"
                                   :lease/run-id "run-attempt-1"
                                   :lease/token "lease-attempt-1-token"
                                   :lease/state :lease.state/active
                                   :lease/expires-at "2026-04-01T00:05:00Z"
                                   :lease/created-at now
                                   :lease/updated-at now})
      (store.protocol/transition-run! store "run-attempt-1"
                                      {:transition/from :run.state/leased
                                       :transition/to :run.state/retryable-failed}
                                      "2026-04-01T00:01:00Z")
      (store.protocol/create-run! store task
                                  {:run/id "run-attempt-2"
                                   :run/attempt 2
                                   :run/run-fsm-ref (:task/run-fsm-ref task)
                                   :run/runtime-profile-ref (:task/runtime-profile-ref task)
                                   :run/state :run.state/leased
                                   :run/created-at "2026-04-01T00:02:00Z"
                                   :run/updated-at "2026-04-01T00:02:00Z"}
                                  {:lease/id "lease-attempt-2"
                                   :lease/run-id "run-attempt-2"
                                   :lease/token "lease-attempt-2-token"
                                   :lease/state :lease.state/active
                                   :lease/expires-at "2026-04-01T00:07:00Z"
                                   :lease/created-at "2026-04-01T00:02:00Z"
                                   :lease/updated-at "2026-04-01T00:02:00Z"})
      (store.protocol/transition-run! store "run-attempt-2"
                                      {:transition/from :run.state/leased
                                       :transition/to :run.state/retryable-failed}
                                      "2026-04-01T00:03:00Z")
      (store.protocol/transition-task! store task-id
                                       {:transition/from :task.state/queued
                                        :transition/to :task.state/retryable-failed}
                                       "2026-04-01T00:03:00Z")
      (let [step-result (scheduler/run-scheduler-step db-path)
            task-after (scheduler/inspect-task! db-path task-id)
            latest-run (scheduler/inspect-run! db-path "run-attempt-2")]
        (is (empty? (:created-runs step-result)))
        (is (empty? (:requeued-task-ids step-result)))
        (is (= [task-id] (:escalated-task-ids step-result)))
        (is (= :task.state/needs-review (:task/state task-after)))
        (is (= :run.state/retryable-failed (:run/state latest-run)))))))
