(ns meta-flow.store.sqlite.heartbeat-projection-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.control.projection :as projection]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite.test-support :as support]))

(deftest heartbeat-timeout-snapshot-reuses-one-row-scan-for-ids-and-count
  (let [{:keys [store reader]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        future-now "2026-04-01T01:00:00Z"
        timeout-task (store.protocol/enqueue-task! store (support/task "task-timeout-once" "work/cve-timeout-once" now))
        call-count (atom 0)
        original-query @#'projection/heartbeat-timeout-run-rows-query]
    (store.protocol/create-run! store timeout-task
                                (assoc (support/run "run-timeout-once" 1 now)
                                       :run/heartbeat-timeout-seconds 60)
                                (support/lease "lease-timeout-once" "run-timeout-once" "2026-04-01T02:00:00Z" now))
    (store.protocol/transition-task! store "task-timeout-once"
                                     {:transition/from :task.state/queued
                                      :transition/to :task.state/running}
                                     "2026-04-01T00:01:00Z")
    (store.protocol/transition-run! store "run-timeout-once"
                                    {:transition/from :run.state/leased
                                     :transition/to :run.state/running}
                                    "2026-04-01T00:01:00Z")
    (event-ingest/ingest-run-event! store {:event/run-id "run-timeout-once"
                                           :event/type events/run-worker-heartbeat
                                           :event/idempotency-key "heartbeat-timeout-once"
                                           :event/payload {:progress/stage :stage/research}
                                           :event/caused-by {:actor/type :worker
                                                             :actor/id "mock-worker"}
                                           :event/emitted-at "2026-04-01T00:01:00Z"})
    (with-redefs [projection/heartbeat-timeout-run-rows-query
                  (fn [connection now-value]
                    (swap! call-count inc)
                    (original-query connection now-value))]
      (let [snapshot (projection/load-scheduler-snapshot reader future-now)]
        (is (= ["run-timeout-once"] (:snapshot/heartbeat-timeout-run-ids snapshot)))
        (is (= 1 (:snapshot/heartbeat-timeout-count snapshot)))
        (is (= 1 @call-count))))))

(deftest heartbeat-timeout-projection-includes-dispatched-runs-with-observed-progress
  (let [{:keys [store reader]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        future-now "2026-04-01T01:00:00Z"
        task (store.protocol/enqueue-task! store (support/task "task-dispatched-timeout" "work/dispatched-timeout" now))]
    (store.protocol/create-run! store task
                                (assoc (support/run "run-dispatched-timeout" 1 now)
                                       :run/heartbeat-timeout-seconds 60)
                                (support/lease "lease-dispatched-timeout" "run-dispatched-timeout" "2026-04-01T02:00:00Z" now))
    (store.protocol/transition-task! store "task-dispatched-timeout"
                                     {:transition/from :task.state/queued
                                      :transition/to :task.state/leased}
                                     "2026-04-01T00:01:00Z")
    (store.protocol/transition-run! store "run-dispatched-timeout"
                                    {:transition/from :run.state/leased
                                     :transition/to :run.state/dispatched}
                                    "2026-04-01T00:05:00Z")
    (event-ingest/ingest-run-event! store {:event/run-id "run-dispatched-timeout"
                                           :event/type events/run-worker-started
                                           :event/idempotency-key "dispatched-started"
                                           :event/payload {}
                                           :event/caused-by {:actor/type :worker
                                                             :actor/id "mock-worker"}
                                           :event/emitted-at "2026-04-01T00:01:30Z"})
    (is (= ["run-dispatched-timeout"]
           (projection/list-heartbeat-timeout-run-ids reader future-now 10)))
    (is (= ["run-dispatched-timeout"]
           (:snapshot/heartbeat-timeout-run-ids (projection/load-scheduler-snapshot reader future-now))))))

(deftest heartbeat-timeout-projection-falls-back-to-dispatch-updated-at-without-progress-events
  (let [{:keys [store reader]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        future-now "2026-04-01T01:00:00Z"
        task (store.protocol/enqueue-task! store (support/task "task-dispatched-no-progress" "work/dispatched-no-progress" now))]
    (store.protocol/create-run! store task
                                (assoc (support/run "run-dispatched-no-progress" 1 now)
                                       :run/heartbeat-timeout-seconds 60)
                                (support/lease "lease-dispatched-no-progress" "run-dispatched-no-progress" "2026-04-01T02:00:00Z" now))
    (store.protocol/transition-task! store "task-dispatched-no-progress"
                                     {:transition/from :task.state/queued
                                      :transition/to :task.state/leased}
                                     "2026-04-01T00:01:00Z")
    (store.protocol/transition-run! store "run-dispatched-no-progress"
                                    {:transition/from :run.state/leased
                                     :transition/to :run.state/dispatched}
                                    "2026-04-01T00:05:00Z")
    (is (= ["run-dispatched-no-progress"]
           (projection/list-heartbeat-timeout-run-ids reader future-now 10)))
    (is (= ["run-dispatched-no-progress"]
           (:snapshot/heartbeat-timeout-run-ids (projection/load-scheduler-snapshot reader future-now))))))
