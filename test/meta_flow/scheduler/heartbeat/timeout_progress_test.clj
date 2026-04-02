(ns meta-flow.scheduler.heartbeat.timeout-progress-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.control.projection :as projection]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.shared :as scheduler.shared]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest heartbeat-timeout-recheck-prefers-max-emitted-at-over-event-sequence
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        store (store.sqlite/sqlite-state-store db-path)
        task (support/enqueue-demo-task! db-path)
        task-id (:task/id task)
        run-id "run-out-of-order-heartbeats"
        lease-id "lease-out-of-order-heartbeats"
        run {:run/id run-id
             :run/attempt 1
             :run/run-fsm-ref (:task/run-fsm-ref task)
             :run/runtime-profile-ref (:task/runtime-profile-ref task)
             :run/heartbeat-timeout-seconds 60
             :run/state :run.state/leased
             :run/created-at "2026-04-01T00:00:00Z"
             :run/updated-at "2026-04-01T00:00:00Z"}
        lease {:lease/id lease-id
               :lease/run-id run-id
               :lease/token (str lease-id "-token")
               :lease/state :lease.state/active
               :lease/expires-at "2099-04-01T00:30:00Z"
               :lease/created-at "2026-04-01T00:00:00Z"
               :lease/updated-at "2026-04-01T00:00:00Z"}]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (store.protocol/create-run! store task run lease)
      (store.protocol/transition-task! store task-id
                                       {:transition/from :task.state/queued
                                        :transition/to :task.state/running}
                                       "2026-04-01T00:01:00Z")
      (store.protocol/transition-run! store run-id
                                      {:transition/from :run.state/leased
                                       :transition/to :run.state/running}
                                      "2026-04-01T00:01:00Z")
      (event-ingest/ingest-run-event! store {:event/run-id run-id
                                             :event/type events/run-worker-heartbeat
                                             :event/idempotency-key "newer-heartbeat"
                                             :event/payload {:progress/stage :stage/research}
                                             :event/caused-by {:actor/type :worker
                                                               :actor/id "mock-worker"}
                                             :event/emitted-at "2026-04-01T00:05:00Z"})
      (event-ingest/ingest-run-event! store {:event/run-id run-id
                                             :event/type events/run-worker-heartbeat
                                             :event/idempotency-key "older-heartbeat-written-late"
                                             :event/payload {:progress/stage :stage/research}
                                             :event/caused-by {:actor/type :worker
                                                               :actor/id "mock-worker"}
                                             :event/emitted-at "2026-04-01T00:02:00Z"})
      (with-redefs [scheduler.shared/now (constantly "2026-04-01T00:05:30Z")
                    projection/list-active-run-ids (fn [_ _ _] [])
                    projection/list-heartbeat-timeout-run-ids (fn [_ _ _] [run-id])]
        (let [step-result (scheduler/run-scheduler-step db-path)
              run-after (scheduler/inspect-run! db-path run-id)
              task-after (scheduler/inspect-task! db-path task-id)]
          (testing "the recheck keeps the newer heartbeat even if an older one was ingested later"
            (is (empty? (:heartbeat-timeout-run-ids step-result)))
            (is (= :run.state/running (:run/state run-after)))
            (is (= :task.state/running (:task/state task-after)))
            (is (= {:state ":lease.state/active"}
                   (select-keys (support/query-one db-path
                                                   "SELECT state FROM leases WHERE lease_id = ?"
                                                   [lease-id])
                                [:state])))
            (is (= [events/run-worker-heartbeat
                    events/run-worker-heartbeat]
                   (mapv :event/type (store.protocol/list-run-events store run-id))))))))))

(deftest heartbeat-timeout-recovery-falls-back-to-dispatch-time-without-progress-events
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        store (store.sqlite/sqlite-state-store db-path)
        task (support/enqueue-demo-task! db-path)
        task-id (:task/id task)
        run-id "run-dispatched-no-progress"
        lease-id "lease-dispatched-no-progress"
        run {:run/id run-id
             :run/attempt 1
             :run/run-fsm-ref (:task/run-fsm-ref task)
             :run/runtime-profile-ref (:task/runtime-profile-ref task)
             :run/heartbeat-timeout-seconds 60
             :run/state :run.state/leased
             :run/created-at "2026-04-01T00:00:00Z"
             :run/updated-at "2026-04-01T00:00:00Z"}
        lease {:lease/id lease-id
               :lease/run-id run-id
               :lease/token (str lease-id "-token")
               :lease/state :lease.state/active
               :lease/expires-at "2099-04-01T00:30:00Z"
               :lease/created-at "2026-04-01T00:00:00Z"
               :lease/updated-at "2026-04-01T00:00:00Z"}]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (store.protocol/create-run! store task run lease)
      (store.protocol/transition-task! store task-id
                                       {:transition/from :task.state/queued
                                        :transition/to :task.state/leased}
                                       "2026-04-01T00:01:00Z")
      (store.protocol/transition-run! store run-id
                                      {:transition/from :run.state/leased
                                       :transition/to :run.state/dispatched}
                                      "2026-04-01T00:05:00Z")
      (with-redefs [scheduler.shared/now (constantly "2026-04-01T01:00:00Z")
                    projection/list-active-run-ids (fn [_ _ _] [])]
        (let [step-result (scheduler/run-scheduler-step db-path)
              run-after (scheduler/inspect-run! db-path run-id)
              task-after (scheduler/inspect-task! db-path task-id)]
          (testing "dispatched runs with no worker-started event still honor heartbeat timeout from dispatch time"
            (is (= [run-id] (:heartbeat-timeout-run-ids step-result)))
            (is (= :run.state/retryable-failed (:run/state run-after)))
            (is (= :task.state/retryable-failed (:task/state task-after)))
            (is (= {:state ":lease.state/released"}
                   (select-keys (support/query-one db-path
                                                   "SELECT state FROM leases WHERE lease_id = ?"
                                                   [lease-id])
                                [:state])))
            (is (= [events/run-heartbeat-timed-out
                    events/task-heartbeat-timed-out]
                   (mapv :event/type (store.protocol/list-run-events store run-id))))))))))
