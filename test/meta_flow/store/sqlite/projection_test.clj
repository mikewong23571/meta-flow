(ns meta-flow.store.sqlite.projection-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.control.projection :as projection]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite.test-support :as support]))

(deftest load-scheduler-snapshot-is-transactionally-consistent
  (let [{:keys [store reader]} (support/test-system)
        observed-first-query (java.util.concurrent.CountDownLatch. 1)
        allow-count-query (java.util.concurrent.CountDownLatch. 1)
        original-runnable-task-ids-query @#'projection/runnable-task-ids-query]
    (with-redefs [projection/runnable-task-ids-query
                  (fn [connection limit]
                    (let [task-ids (original-runnable-task-ids-query connection limit)]
                      (.countDown observed-first-query)
                      (is (.await allow-count-query 5 java.util.concurrent.TimeUnit/SECONDS))
                      task-ids))]
      (let [snapshot-future (future (projection/load-scheduler-snapshot reader "2026-04-01T01:00:00Z"))]
        (is (.await observed-first-query 5 java.util.concurrent.TimeUnit/SECONDS))
        (store.protocol/enqueue-task! store
                                      (support/task "task-race" "work/cve-race" "2026-04-01T00:00:00Z"))
        (.countDown allow-count-query)
        (let [snapshot (deref snapshot-future 5000 ::timeout)]
          (is (not= ::timeout snapshot))
          (is (= [] (:snapshot/runnable-task-ids snapshot)))
          (is (= 0 (:snapshot/runnable-count snapshot))))))))

(deftest projection-reader-exposes-runnable-awaiting-validation-and-expired-lease-sets
  (let [{:keys [store reader]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        future-now "2026-04-01T01:00:00Z"
        queued-task (store.protocol/enqueue-task! store (support/task "task-queued" "work/cve-queued" now))
        expired-task (store.protocol/enqueue-task! store (support/task "task-expired" "work/cve-expired" now))
        awaiting-task (store.protocol/enqueue-task! store (support/task "task-awaiting" "work/cve-awaiting" now))
        retryable-task (store.protocol/enqueue-task! store (support/task "task-retryable" "work/cve-retryable" now))
        timeout-task (store.protocol/enqueue-task! store (support/task "task-timeout" "work/cve-timeout" now))]
    (store.protocol/upsert-collection-state! store (support/collection-state true now))
    (store.protocol/create-run! store expired-task
                                (support/run "run-expired" 1 now)
                                (support/lease "lease-expired" "run-expired" "2026-04-01T00:05:00Z" now))
    (store.protocol/create-run! store awaiting-task
                                (support/run "run-awaiting" 1 now)
                                (support/lease "lease-awaiting" "run-awaiting" "2026-04-01T00:04:00Z" now))
    (store.protocol/create-run! store retryable-task
                                (support/run "run-retryable" 1 now)
                                (support/lease "lease-retryable" "run-retryable" "2026-04-01T00:06:00Z" now))
    (store.protocol/create-run! store timeout-task
                                (assoc (support/run "run-timeout" 1 now)
                                       :run/heartbeat-timeout-seconds 60)
                                (support/lease "lease-timeout" "run-timeout" "2026-04-01T02:00:00Z" now))
    (store.protocol/transition-task! store "task-expired"
                                     {:transition/from :task.state/queued
                                      :transition/to :task.state/leased}
                                     "2026-04-01T00:01:00Z")
    (store.protocol/transition-task! store "task-awaiting"
                                     {:transition/from :task.state/queued
                                      :transition/to :task.state/awaiting-validation}
                                     "2026-04-01T00:02:00Z")
    (store.protocol/transition-run! store "run-awaiting"
                                    {:transition/from :run.state/leased
                                     :transition/to :run.state/awaiting-validation}
                                    "2026-04-01T00:02:00Z")
    (store.protocol/transition-task! store "task-retryable"
                                     {:transition/from :task.state/queued
                                      :transition/to :task.state/retryable-failed}
                                     "2026-04-01T00:03:00Z")
    (store.protocol/transition-run! store "run-retryable"
                                    {:transition/from :run.state/leased
                                     :transition/to :run.state/retryable-failed}
                                    "2026-04-01T00:03:00Z")
    (store.protocol/transition-task! store "task-timeout"
                                     {:transition/from :task.state/queued
                                      :transition/to :task.state/running}
                                     "2026-04-01T00:01:30Z")
    (store.protocol/transition-run! store "run-timeout"
                                    {:transition/from :run.state/leased
                                     :transition/to :run.state/running}
                                    "2026-04-01T00:01:30Z")
    (event-ingest/ingest-run-event! store {:event/run-id "run-timeout"
                                           :event/type events/run-worker-started
                                           :event/idempotency-key "timeout-started"
                                           :event/payload {}
                                           :event/caused-by {:actor/type :worker
                                                             :actor/id "mock-worker"}
                                           :event/emitted-at "2026-04-01T00:01:30Z"})
    (event-ingest/ingest-run-event! store {:event/run-id "run-timeout"
                                           :event/type events/run-worker-heartbeat
                                           :event/idempotency-key "timeout-heartbeat"
                                           :event/payload {:progress/stage :stage/research}
                                           :event/caused-by {:actor/type :worker
                                                             :actor/id "mock-worker"}
                                           :event/emitted-at "2026-04-01T00:02:00Z"})
    (is (= ["task-queued"]
           (projection/list-runnable-task-ids reader future-now 10)))
    (is (= ["task-retryable"]
           (projection/list-retryable-failed-task-ids reader future-now 10)))
    (is (= ["run-awaiting"]
           (projection/list-awaiting-validation-run-ids reader future-now 10)))
    (is (= ["run-expired" "run-awaiting" "run-timeout"]
           (projection/list-active-run-ids reader future-now 10)))
    (is (= ["run-expired"]
           (projection/list-expired-lease-run-ids reader future-now 10)))
    (is (= ["run-timeout"]
           (projection/list-heartbeat-timeout-run-ids reader future-now 10)))
    (let [snapshot (projection/load-scheduler-snapshot reader future-now)]
      (is (true? (:snapshot/dispatch-paused? snapshot)))
      (is (= ["task-queued"] (:snapshot/runnable-task-ids snapshot)))
      (is (= ["task-retryable"] (:snapshot/retryable-failed-task-ids snapshot)))
      (is (= ["run-awaiting"] (:snapshot/awaiting-validation-run-ids snapshot)))
      (is (= ["run-expired"] (:snapshot/expired-lease-run-ids snapshot)))
      (is (= ["run-timeout"] (:snapshot/heartbeat-timeout-run-ids snapshot)))
      (is (= 1 (:snapshot/runnable-count snapshot)))
      (is (= 1 (:snapshot/retryable-failed-count snapshot)))
      (is (= 1 (:snapshot/awaiting-validation-count snapshot)))
      (is (= 1 (:snapshot/expired-lease-count snapshot)))
      (is (= 1 (:snapshot/heartbeat-timeout-count snapshot)))
      (is (= 3 (projection/count-active-runs reader future-now)))
      (is (= "task-queued" (:task/id queued-task))))))

(deftest upsert-collection-state-preserves-created-at-in-canonical-edn
  (let [{:keys [db-path store reader]} (support/test-system)
        created-at "2026-04-01T00:00:00Z"
        updated-at "2026-04-01T01:00:00Z"]
    (store.protocol/upsert-collection-state! store (support/collection-state false created-at))
    (store.protocol/upsert-collection-state! store (-> (support/collection-state true updated-at)
                                                       (dissoc :collection/created-at)))
    (let [snapshot (projection/load-scheduler-snapshot reader updated-at)
          persisted-state (-> (support/query-single-value db-path "SELECT state_edn FROM collection_state")
                              edn/read-string)]
      (is (= created-at
             (:collection/created-at (first (:snapshot/collections snapshot)))))
      (is (= created-at
             (:collection/created-at persisted-state)))
      (is (= created-at
             (support/query-single-value db-path "SELECT created_at FROM collection_state")))
      (is (true? (get-in persisted-state [:collection/dispatch :dispatch/paused?]))))))

(deftest snapshot-counts-are-not-capped-by-sample-limit
  (let [{:keys [store reader]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        transition-now "2026-04-01T00:01:00Z"
        future-now "2026-04-01T01:00:00Z"]
    (doseq [idx (range 101)]
      (store.protocol/enqueue-task! store
                                    (support/task (str "task-runnable-" idx)
                                                  (str "work/runnable-" idx)
                                                  now)))
    (doseq [idx (range 101)]
      (let [task-id (str "task-expired-" idx)
            run-id (str "run-expired-" idx)
            task-entity (store.protocol/enqueue-task! store
                                                      (support/task task-id
                                                                    (str "work/expired-" idx)
                                                                    now))]
        (store.protocol/create-run! store task-entity
                                    (support/run run-id 1 now)
                                    (support/lease (str "lease-expired-" idx)
                                                   run-id
                                                   "2026-04-01T00:05:00Z"
                                                   now))
        (store.protocol/transition-task! store task-id
                                         {:transition/from :task.state/queued
                                          :transition/to :task.state/leased}
                                         transition-now)))
    (doseq [idx (range 101)]
      (let [task-id (str "task-awaiting-" idx)
            run-id (str "run-awaiting-" idx)
            task-entity (store.protocol/enqueue-task! store
                                                      (support/task task-id
                                                                    (str "work/awaiting-" idx)
                                                                    now))]
        (store.protocol/create-run! store task-entity
                                    (support/run run-id 1 now)
                                    (support/lease (str "lease-awaiting-" idx)
                                                   run-id
                                                   "2026-04-01T00:04:00Z"
                                                   now))
        (store.protocol/transition-task! store task-id
                                         {:transition/from :task.state/queued
                                          :transition/to :task.state/awaiting-validation}
                                         transition-now)
        (store.protocol/transition-run! store run-id
                                        {:transition/from :run.state/leased
                                         :transition/to :run.state/awaiting-validation}
                                        transition-now)))
    (let [snapshot (projection/load-scheduler-snapshot reader future-now)]
      (is (= 101 (:snapshot/runnable-count snapshot)))
      (is (= 101 (:snapshot/awaiting-validation-count snapshot)))
      (is (= 101 (:snapshot/expired-lease-count snapshot)))
      (is (= 100 (count (:snapshot/runnable-task-ids snapshot))))
      (is (= 100 (count (:snapshot/awaiting-validation-run-ids snapshot))))
      (is (= 100 (count (:snapshot/expired-lease-run-ids snapshot)))))))
