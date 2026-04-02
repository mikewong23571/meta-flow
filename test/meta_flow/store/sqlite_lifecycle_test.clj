(ns meta-flow.store.sqlite-lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.control.projection :as projection]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite.tasks :as store.sqlite.tasks]
            [meta-flow.store.sqlite-test-support :as support]))

(deftest enqueue-task-is-idempotent-by-work-key
  (let [{:keys [db-path store reader]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (support/task "task-1" "work/cve-1" now)
        task-duplicate (assoc (support/task "task-2" "work/cve-1" now)
                              :task/created-at "2026-04-01T00:01:00Z")]
    (testing "enqueue returns the original task when the work key already exists"
      (is (= task-1
             (store.protocol/enqueue-task! store task-1)))
      (is (= task-1
             (store.protocol/enqueue-task! store task-duplicate))))
    (testing "only one row is stored and the runnable projection sees it"
      (is (= 1
             (support/query-single-value db-path "SELECT COUNT(*) FROM tasks")))
      (is (= ["task-1"]
             (projection/list-runnable-task-ids reader now 10))))))

(deftest create-run-enforces-phase1-run-and-lease-uniqueness
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (store.protocol/enqueue-task! store (support/task "task-1" "work/cve-2" now))
        run-1 (support/run "run-1" 1 now)
        lease-1 (support/lease "lease-1" "run-1" "2026-04-01T00:10:00Z" now)]
    (testing "the first run and lease are created atomically"
      (is (= {:run (assoc run-1 :run/task-id "task-1" :run/lease-id "lease-1")
              :lease lease-1}
             (store.protocol/create-run! store task-1 run-1 lease-1))))
    (testing "the same task cannot own two non-terminal runs"
      (is (thrown? java.sql.SQLException
                   (store.protocol/create-run! store
                                               task-1
                                               (support/run "run-2" 2 now)
                                               (support/lease "lease-2" "run-2" "2026-04-01T00:11:00Z" now))))
      (is (= 1
             (support/query-single-value db-path "SELECT COUNT(*) FROM runs")))
      (is (= 1
             (support/query-single-value db-path "SELECT COUNT(*) FROM leases"))))
    (testing "the same run cannot own two active leases"
      (is (thrown? java.sql.SQLException
                   (support/execute-sql! db-path
                                         (str "INSERT INTO leases "
                                              "(lease_id, run_id, state, lease_token, lease_expires_at, lease_edn, created_at, updated_at) VALUES "
                                              "('lease-extra', 'run-1', ':lease.state/active', 'lease-extra-token', "
                                              "'2026-04-01T00:12:00Z', '{}', '2026-04-01T00:00:00Z', '2026-04-01T00:00:00Z')")))))))

(deftest create-run-rejects-mismatched-lease-run-id
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (store.protocol/enqueue-task! store (support/task "task-1" "work/cve-2-a" now))
        task-2 (store.protocol/enqueue-task! store (support/task "task-2" "work/cve-2-b" now))]
    (store.protocol/create-run! store task-1
                                (support/run "run-1" 1 now)
                                (support/lease "lease-1" "run-1" "2026-04-01T00:10:00Z" now))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"lease/run-id"
                          (store.protocol/create-run! store task-2
                                                      (support/run "run-2" 1 now)
                                                      (support/lease "lease-2" "run-1" "2026-04-01T00:11:00Z" now))))
    (is (= 1
           (support/query-single-value db-path "SELECT COUNT(*) FROM runs")))
    (is (= 1
           (support/query-single-value db-path "SELECT COUNT(*) FROM leases")))))

(deftest claim-task-for-run-is-atomic-and-computes-attempt
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        lease-now "2026-04-01T00:01:00Z"
        task-entity (store.protocol/enqueue-task! store (support/task "task-claim" "work/claim" now))
        claimed (store.protocol/claim-task-for-run! store
                                                    task-entity
                                                    (-> (support/run "run-claim" nil now)
                                                        (assoc :run/state :run.state/created)
                                                        (dissoc :run/attempt))
                                                    (support/lease "lease-claim" "run-claim" "2026-04-01T00:10:00Z" now)
                                                    {:transition/from :task.state/queued
                                                     :transition/to :task.state/leased}
                                                    {:transition/from :run.state/created
                                                     :transition/to :run.state/leased}
                                                    lease-now)]
    (is (= :task.state/leased
           (get-in claimed [:task :task/state])))
    (is (= :run.state/leased
           (get-in claimed [:run :run/state])))
    (is (= 1
           (get-in claimed [:run :run/attempt])))
    (is (= "lease-claim"
           (get-in claimed [:run :run/lease-id])))
    (is (= 1
           (support/query-single-value db-path "SELECT COUNT(*) FROM runs")))
    (is (= 1
           (support/query-single-value db-path "SELECT COUNT(*) FROM leases")))
    (is (= ":task.state/leased"
           (support/query-single-value db-path "SELECT state FROM tasks WHERE task_id = 'task-claim'")))
    (is (= ":run.state/leased"
           (support/query-single-value db-path "SELECT state FROM runs WHERE run_id = 'run-claim'")))))

(deftest claim-task-for-run-skips-non-runnable-tasks-without-side-effects
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task-entity (store.protocol/enqueue-task! store (support/task "task-claim-skipped" "work/claim-skipped" now))
        _ (store.protocol/transition-task! store "task-claim-skipped"
                                           {:transition/from :task.state/queued
                                            :transition/to :task.state/leased}
                                           "2026-04-01T00:01:00Z")]
    (is (nil? (store.protocol/claim-task-for-run! store
                                                  task-entity
                                                  (-> (support/run "run-claim-skipped" nil now)
                                                      (assoc :run/state :run.state/created)
                                                      (dissoc :run/attempt))
                                                  (support/lease "lease-claim-skipped" "run-claim-skipped" "2026-04-01T00:10:00Z" now)
                                                  {:transition/from :task.state/queued
                                                   :transition/to :task.state/leased}
                                                  {:transition/from :run.state/created
                                                   :transition/to :run.state/leased}
                                                  "2026-04-01T00:01:00Z")))
    (is (= 0
           (support/query-single-value db-path "SELECT COUNT(*) FROM runs")))
    (is (= 0
           (support/query-single-value db-path "SELECT COUNT(*) FROM leases")))))

(deftest recover-run-startup-failure-reverts-only-clean-claimed-runs
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        leased-at "2026-04-01T00:01:00Z"
        task-entity (store.protocol/enqueue-task! store (support/task "task-recover" "work/recover" now))
        claimed (store.protocol/claim-task-for-run! store
                                                    task-entity
                                                    {:run/id "run-recover"
                                                     :run/run-fsm-ref {:definition/id :run-fsm/default
                                                                       :definition/version 1}
                                                     :run/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                                                               :definition/version 1}
                                                     :run/state :run.state/created
                                                     :run/created-at now
                                                     :run/updated-at now}
                                                    {:lease/id "lease-recover"
                                                     :lease/run-id "run-recover"
                                                     :lease/token "lease-recover-token"
                                                     :lease/state :lease.state/active
                                                     :lease/expires-at "2026-04-01T00:30:00Z"
                                                     :lease/created-at now
                                                     :lease/updated-at now}
                                                    {:transition/from :task.state/queued
                                                     :transition/to :task.state/leased}
                                                    {:transition/from :run.state/created
                                                     :transition/to :run.state/leased}
                                                    leased-at)
        claimed-task (:task claimed)
        claimed-run (:run claimed)]
    (testing "clean claimed runs are compensated back to queued/finalized"
      (is (= :recovered
             (:recovery/status (store.protocol/recover-run-startup-failure! store claimed-task claimed-run "2026-04-01T00:02:00Z"))))
      (is (= :task.state/queued
             (:task/state (store.protocol/find-task store "task-recover"))))
      (is (= :run.state/finalized
             (:run/state (store.protocol/find-run store "run-recover"))))
      (is (= ":lease.state/released"
             (support/query-single-value db-path "SELECT state FROM leases WHERE lease_id = 'lease-recover'"))))
    (testing "runs with emitted events are no longer compensated"
      (let [task-2 (store.protocol/enqueue-task! store (support/task "task-recover-skip" "work/recover-skip" now))
            claimed-skip (store.protocol/claim-task-for-run! store
                                                             task-2
                                                             {:run/id "run-recover-skip"
                                                              :run/run-fsm-ref {:definition/id :run-fsm/default
                                                                                :definition/version 1}
                                                              :run/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                                                                        :definition/version 1}
                                                              :run/state :run.state/created
                                                              :run/created-at now
                                                              :run/updated-at now}
                                                             {:lease/id "lease-recover-skip"
                                                              :lease/run-id "run-recover-skip"
                                                              :lease/token "lease-recover-skip-token"
                                                              :lease/state :lease.state/active
                                                              :lease/expires-at "2026-04-01T00:30:00Z"
                                                              :lease/created-at now
                                                              :lease/updated-at now}
                                                             {:transition/from :task.state/queued
                                                              :transition/to :task.state/leased}
                                                             {:transition/from :run.state/created
                                                              :transition/to :run.state/leased}
                                                             leased-at)
            claimed-task-skip (:task claimed-skip)
            claimed-run-skip (:run claimed-skip)]
        (event-ingest/ingest-run-event! store {:event/run-id "run-recover-skip"
                                               :event/type events/run-dispatched
                                               :event/idempotency-key "recover-skip-dispatched"
                                               :event/payload {}
                                               :event/caused-by {:actor/type :runtime.adapter/mock
                                                                 :actor/id "mock-runtime"}
                                               :event/emitted-at "2026-04-01T00:02:00Z"})
        (is (= :skipped
               (:recovery/status (store.protocol/recover-run-startup-failure! store claimed-task-skip claimed-run-skip "2026-04-01T00:03:00Z"))))
        (is (= :task.state/leased
               (:task/state (store.protocol/find-task store "task-recover-skip"))))
        (is (= :run.state/leased
               (:run/state (store.protocol/find-run store "run-recover-skip"))))
        (is (= ":lease.state/active"
               (support/query-single-value db-path "SELECT state FROM leases WHERE lease_id = 'lease-recover-skip'")))))))

(deftest transition-task-cas-rejects-stale-row-image
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task-entity (store.protocol/enqueue-task! store (support/task "task-cas" "work/cas" now))
        stale-row {:task_id "task-cas"
                   :state ":task.state/queued"
                   :task_edn (pr-str task-entity)
                   :updated_at now}]
    (support/execute-sql! db-path
                          (str "UPDATE tasks "
                               "SET task_edn = '{:task/id \"task-cas\" :task/state :task.state/queued :task/updated-at \"2026-04-01T00:00:01Z\"}', "
                               "updated_at = '2026-04-01T00:00:01Z' "
                               "WHERE task_id = 'task-cas'"))
    (with-redefs [store.sqlite.tasks/find-task-row (fn [_ _] stale-row)]
      (is (nil? (store.protocol/transition-task! store "task-cas"
                                                 {:transition/from :task.state/queued
                                                  :transition/to :task.state/leased}
                                                 "2026-04-01T00:00:02Z"))))
    (is (= :task.state/queued
           (:task/state (store.protocol/find-task store "task-cas"))))
    (is (= "2026-04-01T00:00:01Z"
           (:task/updated-at (store.protocol/find-task store "task-cas"))))))
