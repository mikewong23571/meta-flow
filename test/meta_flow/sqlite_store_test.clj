(ns meta-flow.sqlite-store-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.db :as db]
            [meta-flow.event-ingest :as event-ingest]
            [meta-flow.projection :as projection]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn- temp-db-path
  []
  (let [temp-dir (java.nio.file.Files/createTempDirectory "meta-flow-store-test"
                                                          (make-array java.nio.file.attribute.FileAttribute 0))]
    (str (.toFile temp-dir) "/meta-flow.sqlite3")))

(defn- query-single-value
  [db-path sql-text]
  (with-open [connection (db/open-connection db-path)
              statement (.createStatement connection)
              result-set (.executeQuery statement sql-text)]
    (when (.next result-set)
      (.getObject result-set 1))))

(defn- execute-sql!
  [db-path sql-text]
  (with-open [connection (db/open-connection db-path)
              statement (.createStatement connection)]
    (.execute statement sql-text)))

(defn- test-system
  []
  (let [db-path (temp-db-path)]
    (db/initialize-database! db-path)
    {:db-path db-path
     :store (store.sqlite/sqlite-state-store db-path)
     :reader (projection/sqlite-projection-reader db-path)}))

(defn- task
  [task-id work-key now]
  {:task/id task-id
   :task/work-key work-key
   :task/task-type-ref {:definition/id :task-type/default
                        :definition/version 1}
   :task/task-fsm-ref {:definition/id :task-fsm/default
                       :definition/version 1}
   :task/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                              :definition/version 1}
   :task/artifact-contract-ref {:definition/id :artifact-contract/default
                                :definition/version 1}
   :task/validator-ref {:definition/id :validator/required-paths
                        :definition/version 1}
   :task/resource-policy-ref {:definition/id :resource-policy/default
                              :definition/version 1}
   :task/state :task.state/queued
   :task/created-at now
   :task/updated-at now})

(defn- run
  [run-id attempt now]
  {:run/id run-id
   :run/attempt attempt
   :run/run-fsm-ref {:definition/id :run-fsm/default
                     :definition/version 1}
   :run/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                             :definition/version 1}
   :run/state :run.state/leased
   :run/created-at now
   :run/updated-at now})

(defn- lease
  [lease-id run-id expires-at now]
  {:lease/id lease-id
   :lease/run-id run-id
   :lease/token (str lease-id "-token")
   :lease/state :lease.state/active
   :lease/expires-at expires-at
   :lease/created-at now
   :lease/updated-at now})

(defn- collection-state
  [paused? now]
  {:collection/id :collection/default
   :collection/dispatch {:dispatch/paused? paused?}
   :collection/resource-policy-ref {:definition/id :resource-policy/default
                                    :definition/version 1}
   :collection/created-at now
   :collection/updated-at now})

(deftest enqueue-task-is-idempotent-by-work-key
  (let [{:keys [db-path store reader]} (test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (task "task-1" "work/cve-1" now)
        task-duplicate (assoc (task "task-2" "work/cve-1" now)
                              :task/created-at "2026-04-01T00:01:00Z")]
    (testing "enqueue returns the original task when the work key already exists"
      (is (= task-1
             (store.protocol/enqueue-task! store task-1)))
      (is (= task-1
             (store.protocol/enqueue-task! store task-duplicate))))
    (testing "only one row is stored and the runnable projection sees it"
      (is (= 1
             (query-single-value db-path "SELECT COUNT(*) FROM tasks")))
      (is (= ["task-1"]
             (projection/list-runnable-task-ids reader now 10))))))

(deftest create-run-enforces-phase1-run-and-lease-uniqueness
  (let [{:keys [db-path store]} (test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (store.protocol/enqueue-task! store (task "task-1" "work/cve-2" now))
        run-1 (run "run-1" 1 now)
        lease-1 (lease "lease-1" "run-1" "2026-04-01T00:10:00Z" now)]
    (testing "the first run and lease are created atomically"
      (is (= {:run (assoc run-1 :run/task-id "task-1" :run/lease-id "lease-1")
              :lease lease-1}
             (store.protocol/create-run! store task-1 run-1 lease-1))))
    (testing "the same task cannot own two non-terminal runs"
      (is (thrown? java.sql.SQLException
                   (store.protocol/create-run! store
                                               task-1
                                               (run "run-2" 2 now)
                                               (lease "lease-2" "run-2" "2026-04-01T00:11:00Z" now))))
      (is (= 1
             (query-single-value db-path "SELECT COUNT(*) FROM runs")))
      (is (= 1
             (query-single-value db-path "SELECT COUNT(*) FROM leases"))))
    (testing "the same run cannot own two active leases"
      (is (thrown? java.sql.SQLException
                   (execute-sql! db-path
                                 (str "INSERT INTO leases "
                                      "(lease_id, run_id, state, lease_token, lease_expires_at, lease_edn, created_at, updated_at) VALUES "
                                      "('lease-extra', 'run-1', ':lease.state/active', 'lease-extra-token', "
                                      "'2026-04-01T00:12:00Z', '{}', '2026-04-01T00:00:00Z', '2026-04-01T00:00:00Z')")))))))

(deftest create-run-rejects-mismatched-lease-run-id
  (let [{:keys [db-path store]} (test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (store.protocol/enqueue-task! store (task "task-1" "work/cve-2-a" now))
        task-2 (store.protocol/enqueue-task! store (task "task-2" "work/cve-2-b" now))]
    (store.protocol/create-run! store task-1
                                (run "run-1" 1 now)
                                (lease "lease-1" "run-1" "2026-04-01T00:10:00Z" now))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"lease/run-id"
                          (store.protocol/create-run! store task-2
                                                      (run "run-2" 1 now)
                                                      (lease "lease-2" "run-1" "2026-04-01T00:11:00Z" now))))
    (is (= 1
           (query-single-value db-path "SELECT COUNT(*) FROM runs")))
    (is (= 1
           (query-single-value db-path "SELECT COUNT(*) FROM leases")))))

(deftest attach-artifact-rejects-mismatched-run-id
  (let [{:keys [db-path store]} (test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (store.protocol/enqueue-task! store (task "task-1" "work/cve-artifact-1" now))
        task-2 (store.protocol/enqueue-task! store (task "task-2" "work/cve-artifact-2" now))]
    (store.protocol/create-run! store task-1
                                (run "run-1" 1 now)
                                (lease "lease-1" "run-1" "2026-04-01T00:10:00Z" now))
    (store.protocol/create-run! store task-2
                                (run "run-2" 1 now)
                                (lease "lease-2" "run-2" "2026-04-01T00:11:00Z" now))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"artifact/run-id"
                          (store.protocol/attach-artifact! store "run-2"
                                                           {:artifact/id "artifact-1"
                                                            :artifact/run-id "run-1"
                                                            :artifact/task-id "task-1"
                                                            :artifact/contract-ref {:definition/id :artifact-contract/default
                                                                                    :definition/version 1}
                                                            :artifact/location "/tmp/artifact-1"
                                                            :artifact/created-at "2026-04-01T00:20:00Z"})))
    (is (= 0
           (query-single-value db-path "SELECT COUNT(*) FROM artifacts")))
    (is (nil? (query-single-value db-path "SELECT artifact_id FROM runs WHERE run_id = 'run-2'")))))

(deftest attach-artifact-rejects-mismatched-task-id
  (let [{:keys [db-path store]} (test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (store.protocol/enqueue-task! store (task "task-1" "work/cve-artifact-task-1" now))
        task-2 (store.protocol/enqueue-task! store (task "task-2" "work/cve-artifact-task-2" now))]
    (store.protocol/create-run! store task-1
                                (run "run-1" 1 now)
                                (lease "lease-1" "run-1" "2026-04-01T00:10:00Z" now))
    (store.protocol/create-run! store task-2
                                (run "run-2" 1 now)
                                (lease "lease-2" "run-2" "2026-04-01T00:11:00Z" now))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"artifact/task-id"
                          (store.protocol/attach-artifact! store "run-2"
                                                           {:artifact/id "artifact-1"
                                                            :artifact/run-id "run-2"
                                                            :artifact/task-id "task-1"
                                                            :artifact/contract-ref {:definition/id :artifact-contract/default
                                                                                    :definition/version 1}
                                                            :artifact/location "/tmp/artifact-1"
                                                            :artifact/created-at "2026-04-01T00:20:00Z"})))
    (is (= 0
           (query-single-value db-path "SELECT COUNT(*) FROM artifacts")))
    (is (nil? (query-single-value db-path "SELECT artifact_id FROM runs WHERE run_id = 'run-2'")))))

(deftest ingest-run-event-is-idempotent-and-assigns-monotonic-sequence
  (let [{:keys [db-path store]} (test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (store.protocol/enqueue-task! store (task "task-1" "work/cve-3" now))
        _ (store.protocol/create-run! store task-1 (run "run-1" 1 now) (lease "lease-1" "run-1" "2026-04-01T00:10:00Z" now))
        heartbeat {:event/run-id "run-1"
                   :event/type :event/worker-heartbeat
                   :event/idempotency-key "heartbeat-1"
                   :event/payload {:progress/stage :stage/research}
                   :event/caused-by {:actor/type :worker
                                     :actor/id "mock-worker"}
                   :event/emitted-at "2026-04-01T00:01:00Z"}
        exit-event {:event/run-id "run-1"
                    :event/type :event/worker-exit
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
        (is (= 2
               (query-single-value db-path "SELECT COUNT(*) FROM run_events WHERE run_id = 'run-1'")))))))

(deftest ingest-run-event-retries-sequence-collisions-and-rebuilds-run-edn
  (let [{:keys [db-path store]} (test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (store.protocol/enqueue-task! store (task "task-1" "work/cve-4" now))
        _ (store.protocol/create-run! store task-1 (run "run-1" 1 now) (lease "lease-1" "run-1" "2026-04-01T00:10:00Z" now))
        heartbeat {:event/run-id "run-1"
                   :event/type :event/worker-heartbeat
                   :event/idempotency-key "heartbeat-1"
                   :event/payload {:progress/stage :stage/research}
                   :event/caused-by {:actor/type :worker
                                     :actor/id "mock-worker"}
                   :event/emitted-at "2026-04-01T00:01:00Z"}
        exit-event {:event/run-id "run-1"
                    :event/type :event/worker-exit
                    :event/idempotency-key "exit-1"
                    :event/payload {:worker/exit-code 0}
                    :event/caused-by {:actor/type :worker
                                      :actor/id "mock-worker"}
                    :event/emitted-at "2026-04-01T00:02:00Z"}]
    (testing "event ingestion retries a concurrent sequence collision"
      (let [original-next-event-seq @#'store.sqlite/next-event-seq
            call-count (atom 0)
            both-computed-seq (java.util.concurrent.CountDownLatch. 2)
            release-inserts (java.util.concurrent.CountDownLatch. 1)]
        (with-redefs [store.sqlite/next-event-seq
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
      (execute-sql! db-path
                    (str "UPDATE runs SET state = ':run.state/awaiting-validation', "
                         "artifact_id = 'artifact-existing', "
                         "updated_at = '2026-04-01T00:03:00Z' "
                         "WHERE run_id = 'run-1'"))
      (is (= :run.state/awaiting-validation
             (:run/state (store.protocol/find-run store "run-1"))))
      (is (= "artifact-existing"
             (:run/artifact-id (store.protocol/find-run store "run-1"))))
      (event-ingest/ingest-run-event! store {:event/run-id "run-1"
                                             :event/type :event/worker-heartbeat
                                             :event/idempotency-key "heartbeat-2"
                                             :event/payload {:progress/stage :stage/validate}
                                             :event/caused-by {:actor/type :worker
                                                               :actor/id "mock-worker"}
                                             :event/emitted-at "2026-04-01T00:04:00Z"})
      (let [persisted-run (-> (query-single-value db-path "SELECT run_edn FROM runs WHERE run_id = 'run-1'")
                              edn/read-string)]
        (is (= :run.state/awaiting-validation
               (:run/state persisted-run)))
        (is (= "artifact-existing"
               (:run/artifact-id persisted-run)))))
    (testing "late events do not move run updated_at backward"
      (event-ingest/ingest-run-event! store {:event/run-id "run-1"
                                             :event/type :event/worker-exit
                                             :event/idempotency-key "exit-late-check"
                                             :event/payload {:worker/exit-code 0}
                                             :event/caused-by {:actor/type :worker
                                                               :actor/id "mock-worker"}
                                             :event/emitted-at "2026-04-01T00:10:00Z"})
      (event-ingest/ingest-run-event! store {:event/run-id "run-1"
                                             :event/type :event/worker-heartbeat
                                             :event/idempotency-key "heartbeat-late-check"
                                             :event/payload {:progress/stage :stage/research}
                                             :event/caused-by {:actor/type :worker
                                                               :actor/id "mock-worker"}
                                             :event/emitted-at "2026-04-01T00:05:00Z"})
      (is (= "2026-04-01T00:10:00Z"
             (query-single-value db-path "SELECT updated_at FROM runs WHERE run_id = 'run-1'"))))))

(deftest store-round-trips-native-instant-timestamps
  (let [{:keys [store reader]} (test-system)
        created-at (java.time.Instant/parse "2026-04-01T00:00:00Z")
        expires-at (java.time.Instant/parse "2026-04-01T00:10:00Z")
        emitted-at (java.time.Instant/parse "2026-04-01T00:01:00Z")]
    (store.protocol/upsert-collection-state! store (collection-state true created-at))
    (let [task-entity (store.protocol/enqueue-task! store (task "task-instant" "work/cve-instant" created-at))
          _ (store.protocol/create-run! store task-entity
                                        (run "run-instant" 1 created-at)
                                        (lease "lease-instant" "run-instant" expires-at created-at))
          stored-event (event-ingest/ingest-run-event! store {:event/run-id "run-instant"
                                                              :event/type :event/worker-heartbeat
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

(deftest load-scheduler-snapshot-is-transactionally-consistent
  (let [{:keys [store reader]} (test-system)
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
                                      (task "task-race" "work/cve-race" "2026-04-01T00:00:00Z"))
        (.countDown allow-count-query)
        (let [snapshot (deref snapshot-future 5000 ::timeout)]
          (is (not= ::timeout snapshot))
          (is (= [] (:snapshot/runnable-task-ids snapshot)))
          (is (= 0 (:snapshot/runnable-count snapshot))))))))

(deftest projection-reader-exposes-runnable-awaiting-validation-and-expired-lease-sets
  (let [{:keys [store reader]} (test-system)
        now "2026-04-01T00:00:00Z"
        future-now "2026-04-01T01:00:00Z"
        queued-task (store.protocol/enqueue-task! store (task "task-queued" "work/cve-queued" now))
        expired-task (store.protocol/enqueue-task! store (task "task-expired" "work/cve-expired" now))
        awaiting-task (store.protocol/enqueue-task! store (task "task-awaiting" "work/cve-awaiting" now))]
    (store.protocol/upsert-collection-state! store (collection-state true now))
    (store.protocol/create-run! store expired-task
                                (run "run-expired" 1 now)
                                (lease "lease-expired" "run-expired" "2026-04-01T00:05:00Z" now))
    (store.protocol/create-run! store awaiting-task
                                (run "run-awaiting" 1 now)
                                (lease "lease-awaiting" "run-awaiting" "2026-04-01T00:04:00Z" now))
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
    (testing "list queries reflect the current SQLite projection views"
      (is (= ["task-queued"]
             (projection/list-runnable-task-ids reader future-now 10)))
      (is (= ["run-awaiting"]
             (projection/list-awaiting-validation-run-ids reader future-now 10)))
      (is (= ["run-expired"]
             (projection/list-expired-lease-run-ids reader future-now 10))))
    (testing "snapshot aggregates the same scheduling inputs"
      (let [snapshot (projection/load-scheduler-snapshot reader future-now)]
        (is (true? (:snapshot/dispatch-paused? snapshot)))
        (is (= ["task-queued"] (:snapshot/runnable-task-ids snapshot)))
        (is (= ["run-awaiting"] (:snapshot/awaiting-validation-run-ids snapshot)))
        (is (= ["run-expired"] (:snapshot/expired-lease-run-ids snapshot)))
        (is (= 1 (:snapshot/runnable-count snapshot)))
        (is (= 1 (:snapshot/awaiting-validation-count snapshot)))
        (is (= 1 (:snapshot/expired-lease-count snapshot)))
        (is (= "task-queued" (:task/id queued-task)))))))

(deftest upsert-collection-state-preserves-created-at-in-canonical-edn
  (let [{:keys [db-path store reader]} (test-system)
        created-at "2026-04-01T00:00:00Z"
        updated-at "2026-04-01T01:00:00Z"]
    (store.protocol/upsert-collection-state! store (collection-state false created-at))
    (store.protocol/upsert-collection-state! store (-> (collection-state true updated-at)
                                                       (dissoc :collection/created-at)))
    (let [snapshot (projection/load-scheduler-snapshot reader updated-at)
          persisted-state (-> (query-single-value db-path "SELECT state_edn FROM collection_state")
                              edn/read-string)]
      (is (= created-at
             (:collection/created-at (first (:snapshot/collections snapshot)))))
      (is (= created-at
             (:collection/created-at persisted-state)))
      (is (= created-at
             (query-single-value db-path "SELECT created_at FROM collection_state")))
      (is (true? (get-in persisted-state [:collection/dispatch :dispatch/paused?]))))))

(deftest snapshot-counts-are-not-capped-by-sample-limit
  (let [{:keys [store reader]} (test-system)
        now "2026-04-01T00:00:00Z"
        transition-now "2026-04-01T00:01:00Z"
        future-now "2026-04-01T01:00:00Z"]
    (doseq [idx (range 101)]
      (store.protocol/enqueue-task! store
                                    (task (str "task-runnable-" idx)
                                          (str "work/runnable-" idx)
                                          now)))
    (doseq [idx (range 101)]
      (let [task-id (str "task-expired-" idx)
            run-id (str "run-expired-" idx)
            task-entity (store.protocol/enqueue-task! store
                                                      (task task-id
                                                            (str "work/expired-" idx)
                                                            now))]
        (store.protocol/create-run! store task-entity
                                    (run run-id 1 now)
                                    (lease (str "lease-expired-" idx)
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
                                                      (task task-id
                                                            (str "work/awaiting-" idx)
                                                            now))]
        (store.protocol/create-run! store task-entity
                                    (run run-id 1 now)
                                    (lease (str "lease-awaiting-" idx)
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
