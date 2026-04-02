(ns meta-flow.store.sqlite-run-events-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.control.projection :as projection]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite.run.events :as store.sqlite.run.events]
            [meta-flow.store.sqlite.test-support :as support]))

(deftest ingest-run-event-is-idempotent-and-assigns-monotonic-sequence
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (store.protocol/enqueue-task! store (support/task "task-1" "work/cve-3" now))
        _ (store.protocol/create-run! store task-1 (support/run "run-1" 1 now) (support/lease "lease-1" "run-1" "2026-04-01T00:10:00Z" now))
        heartbeat {:event/run-id "run-1"
                   :event/type events/run-worker-heartbeat
                   :event/idempotency-key "heartbeat-1"
                   :event/payload {:progress/stage :stage/research}
                   :event/caused-by {:actor/type :worker
                                     :actor/id "mock-worker"}
                   :event/emitted-at "2026-04-01T00:01:00Z"}
        exit-event {:event/run-id "run-1"
                    :event/type events/run-worker-exited
                    :event/idempotency-key "exit-1"
                    :event/payload {:worker/exit-code 0}
                    :event/caused-by {:actor/type :worker
                                      :actor/id "mock-worker"}
                    :event/emitted-at "2026-04-01T00:02:00Z"}]
    (testing "callers are not allowed to pre-assign event sequence numbers"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"event/seq"
                            (event-ingest/ingest-run-event! store (assoc heartbeat :event/seq 9)))))
    (testing "event intents must include payload and actor metadata"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"event/payload"
                            (event-ingest/ingest-run-event! store (dissoc heartbeat :event/payload))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"event/caused-by"
                            (event-ingest/ingest-run-event! store (dissoc heartbeat :event/caused-by)))))
    (testing "idempotency keys collapse duplicates onto one stored event"
      (let [first-event (event-ingest/ingest-run-event! store heartbeat)
            duplicate-event (event-ingest/ingest-run-event! store (assoc heartbeat
                                                                         :event/payload {:progress/stage :stage/ignored}))
            second-event (event-ingest/ingest-run-event! store exit-event)
            stored-events (store.protocol/list-run-events store "run-1")]
        (is (= 1 (:event/seq first-event)))
        (is (= first-event duplicate-event))
        (is (= 2 (:event/seq second-event)))
        (is (= [1 2]
               (mapv :event/seq stored-events)))
        (is (= [2]
               (mapv :event/seq (store.protocol/list-run-events-after store "run-1" 1))))
        (is (= 2
               (support/query-single-value db-path "SELECT COUNT(*) FROM run_events WHERE run_id = 'run-1'")))))))

(deftest ingest-run-event-retries-sequence-collisions-and-rebuilds-run-edn
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (store.protocol/enqueue-task! store (support/task "task-1" "work/cve-4" now))
        _ (store.protocol/create-run! store task-1 (support/run "run-1" 1 now) (support/lease "lease-1" "run-1" "2026-04-01T00:10:00Z" now))
        heartbeat {:event/run-id "run-1"
                   :event/type events/run-worker-heartbeat
                   :event/idempotency-key "heartbeat-1"
                   :event/payload {:progress/stage :stage/research}
                   :event/caused-by {:actor/type :worker
                                     :actor/id "mock-worker"}
                   :event/emitted-at "2026-04-01T00:01:00Z"}
        exit-event {:event/run-id "run-1"
                    :event/type events/run-worker-exited
                    :event/idempotency-key "exit-1"
                    :event/payload {:worker/exit-code 0}
                    :event/caused-by {:actor/type :worker
                                      :actor/id "mock-worker"}
                    :event/emitted-at "2026-04-01T00:02:00Z"}]
    (testing "event ingestion retries a concurrent sequence collision"
      (let [original-next-event-seq @#'store.sqlite.run.events/next-event-seq
            call-count (atom 0)
            both-computed-seq (java.util.concurrent.CountDownLatch. 2)
            release-inserts (java.util.concurrent.CountDownLatch. 1)]
        (with-redefs [store.sqlite.run.events/next-event-seq
                      (fn [connection run-id]
                        (let [seq-value (original-next-event-seq connection run-id)
                              call-number (swap! call-count inc)]
                          (when (<= call-number 2)
                            (.countDown both-computed-seq)
                            (when-not (.await release-inserts 5 java.util.concurrent.TimeUnit/SECONDS)
                              (throw (ex-info "Timed out waiting to release concurrent event inserts"
                                              {:run-id run-id
                                               :call-number call-number}))))
                          seq-value))]
          (let [heartbeat-future (future (event-ingest/ingest-run-event! store heartbeat))
                exit-future (future (event-ingest/ingest-run-event! store exit-event))]
            (is (.await both-computed-seq 5 java.util.concurrent.TimeUnit/SECONDS))
            (.countDown release-inserts)
            (let [heartbeat-result @heartbeat-future
                  exit-result @exit-future
                  stored-events (store.protocol/list-run-events store "run-1")]
              (is (= [1 2]
                     (sort [(:event/seq heartbeat-result)
                            (:event/seq exit-result)])))
              (is (= #{"heartbeat-1" "exit-1"}
                     (set (map :event/idempotency-key stored-events))))
              (is (= [1 2]
                     (mapv :event/seq stored-events))))))))
    (testing "run reads and event-triggered rewrites trust the structured columns"
      (support/execute-sql! db-path
                            (str "UPDATE runs SET state = ':run.state/awaiting-validation', "
                                 "artifact_id = 'artifact-existing', "
                                 "updated_at = '2026-04-01T00:03:00Z' "
                                 "WHERE run_id = 'run-1'"))
      (is (= :run.state/awaiting-validation
             (:run/state (store.protocol/find-run store "run-1"))))
      (is (= "artifact-existing"
             (:run/artifact-id (store.protocol/find-run store "run-1"))))
      (event-ingest/ingest-run-event! store {:event/run-id "run-1"
                                             :event/type events/run-worker-heartbeat
                                             :event/idempotency-key "heartbeat-2"
                                             :event/payload {:progress/stage :stage/validate}
                                             :event/caused-by {:actor/type :worker
                                                               :actor/id "mock-worker"}
                                             :event/emitted-at "2026-04-01T00:04:00Z"})
      (let [persisted-run (-> (support/query-single-value db-path "SELECT run_edn FROM runs WHERE run_id = 'run-1'")
                              edn/read-string)]
        (is (= :run.state/awaiting-validation
               (:run/state persisted-run)))
        (is (= "artifact-existing"
               (:run/artifact-id persisted-run)))))
    (testing "late events do not move run updated_at backward"
      (event-ingest/ingest-run-event! store {:event/run-id "run-1"
                                             :event/type events/run-worker-exited
                                             :event/idempotency-key "exit-late-check"
                                             :event/payload {:worker/exit-code 0}
                                             :event/caused-by {:actor/type :worker
                                                               :actor/id "mock-worker"}
                                             :event/emitted-at "2026-04-01T00:10:00Z"})
      (event-ingest/ingest-run-event! store {:event/run-id "run-1"
                                             :event/type events/run-worker-heartbeat
                                             :event/idempotency-key "heartbeat-late-check"
                                             :event/payload {:progress/stage :stage/research}
                                             :event/caused-by {:actor/type :worker
                                                               :actor/id "mock-worker"}
                                             :event/emitted-at "2026-04-01T00:05:00Z"})
      (is (= "2026-04-01T00:10:00Z"
             (support/query-single-value db-path "SELECT updated_at FROM runs WHERE run_id = 'run-1'"))))))

(deftest store-round-trips-native-instant-timestamps
  (let [{:keys [store reader]} (support/test-system)
        created-at (java.time.Instant/parse "2026-04-01T00:00:00Z")
        expires-at (java.time.Instant/parse "2026-04-01T00:10:00Z")
        emitted-at (java.time.Instant/parse "2026-04-01T00:01:00Z")]
    (store.protocol/upsert-collection-state! store (support/collection-state true created-at))
    (let [task-entity (store.protocol/enqueue-task! store (support/task "task-instant" "work/cve-instant" created-at))
          _ (store.protocol/create-run! store task-entity
                                        (support/run "run-instant" 1 created-at)
                                        (support/lease "lease-instant" "run-instant" expires-at created-at))
          stored-event (event-ingest/ingest-run-event! store {:event/run-id "run-instant"
                                                              :event/type events/run-worker-heartbeat
                                                              :event/idempotency-key "heartbeat-instant"
                                                              :event/payload {:progress/stage :stage/research}
                                                              :event/caused-by {:actor/type :worker
                                                                                :actor/id "mock-worker"}
                                                              :event/emitted-at emitted-at})
          stored-task (store.protocol/find-task store "task-instant")
          stored-run (store.protocol/find-run store "run-instant")
          stored-events (store.protocol/list-run-events store "run-instant")
          snapshot (projection/load-scheduler-snapshot reader "2026-04-01T01:00:00Z")]
      (is (= "2026-04-01T00:00:00Z" (:task/created-at task-entity)))
      (is (= "2026-04-01T00:00:00Z" (:task/created-at stored-task)))
      (is (= "2026-04-01T00:00:00Z" (:run/created-at stored-run)))
      (is (= "2026-04-01T00:01:00Z" (:event/emitted-at stored-event)))
      (is (= ["2026-04-01T00:01:00Z"]
             (mapv :event/emitted-at stored-events)))
      (is (= "2026-04-01T00:00:00Z"
             (:collection/created-at (first (:snapshot/collections snapshot))))))))
