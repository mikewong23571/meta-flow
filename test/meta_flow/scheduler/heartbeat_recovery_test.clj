(ns meta-flow.scheduler.heartbeat-recovery-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.control.events :as events]
            [meta-flow.control.projection :as projection]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.scheduler.runtime :as scheduler.runtime]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.shared :as scheduler.shared]
            [meta-flow.scheduler.step :as scheduler.step]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest heartbeat-timeout-recovery-releases-the-lease-and-stops-reprocessing
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        task (support/enqueue-demo-task! db-path)
        task-id (:task/id task)
        {:keys [run-id lease-id]} (support/create-heartbeat-timeout-run! db-path task)
        store (store.sqlite/sqlite-state-store db-path)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [first-step (scheduler/run-scheduler-step db-path)
            task-after-first (scheduler/inspect-task! db-path task-id)
            run-after-first (scheduler/inspect-run! db-path run-id)
            first-events (store.protocol/list-run-events store run-id)]
        (testing "the first scheduler step converts the stale heartbeat into retryable failure"
          (is (empty? (:created-runs first-step)))
          (is (empty? (:task-errors first-step)))
          (is (= [run-id] (:heartbeat-timeout-run-ids first-step)))
          (is (= :task.state/retryable-failed (:task/state task-after-first)))
          (is (= :run.state/retryable-failed (:run/state run-after-first)))
          (is (= {:state ":lease.state/released"}
                 (select-keys (support/query-one db-path
                                                 "SELECT state FROM leases WHERE lease_id = ?"
                                                 [lease-id])
                              [:state])))
          (is (= [events/run-worker-started
                  events/run-worker-heartbeat
                  events/run-heartbeat-timed-out
                  events/task-heartbeat-timed-out]
                 (mapv :event/type first-events)))
          (is (= :timeout.kind/heartbeat
                 (get-in (nth first-events 2) [:event/payload :timeout/kind]))))
        (testing "a later scheduler step does not re-emit heartbeat timeout events"
          (let [second-step (scheduler/run-scheduler-step db-path)
                task-after-second (scheduler/inspect-task! db-path task-id)
                run-after-second (scheduler/inspect-run! db-path run-id)
                second-events (store.protocol/list-run-events store run-id)]
            (is (empty? (:created-runs second-step)))
            (is (empty? (:task-errors second-step)))
            (is (= [task-id] (:requeued-task-ids second-step)))
            (is (empty? (:heartbeat-timeout-run-ids second-step)))
            (is (= :task.state/queued (:task/state task-after-second)))
            (is (= :run.state/retryable-failed (:run/state run-after-second)))
            (is (= first-events second-events))))))))

(deftest heartbeat-timeout-recovery-rechecks-freshness-before-failing-a-run
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        task (support/enqueue-demo-task! db-path)
        task-id (:task/id task)
        {:keys [run-id lease-id]} (support/create-heartbeat-timeout-run! db-path task)
        fresh-now "2026-04-01T00:02:30Z"]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (with-redefs [scheduler.shared/now (constantly fresh-now)
                    projection/list-active-run-ids (fn [_ _ _] [])
                    projection/list-heartbeat-timeout-run-ids (fn [_ _ _] [run-id])
                    scheduler.step/enrich-run (fn [_ run]
                                                (assoc run :run/last-heartbeat fresh-now))]
        (let [step-result (scheduler/run-scheduler-step db-path)
              task-after (scheduler/inspect-task! db-path task-id)
              run-after (scheduler/inspect-run! db-path run-id)
              store (store.sqlite/sqlite-state-store db-path)
              events-after (store.protocol/list-run-events store run-id)]
          (testing "a refreshed heartbeat suppresses timeout recovery even if the candidate list is stale"
            (is (empty? (:created-runs step-result)))
            (is (empty? (:task-errors step-result)))
            (is (empty? (:heartbeat-timeout-run-ids step-result)))
            (is (= :task.state/running (:task/state task-after)))
            (is (= :run.state/running (:run/state run-after)))
            (is (= {:state ":lease.state/active"}
                   (select-keys (support/query-one db-path
                                                   "SELECT state FROM leases WHERE lease_id = ?"
                                                   [lease-id])
                                [:state])))
            (is (= [events/run-worker-started
                    events/run-worker-heartbeat]
                   (mapv :event/type events-after)))
            (is (= "2026-04-01T00:02:00Z" (:run/last-heartbeat run-after)))))))))

(deftest heartbeat-timeout-recovery-does-not-preempt-poll-driven-completion
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        task (support/enqueue-demo-task! db-path)
        task-id (:task/id task)
        store (store.sqlite/sqlite-state-store db-path)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (with-redefs [scheduler.shared/now (constantly "2026-04-01T00:00:00Z")]
        (scheduler/run-scheduler-step db-path))
      (let [run-id (:run_id (support/query-one db-path
                                               "SELECT run_id FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1"
                                               [task-id]))]
        (with-redefs [scheduler.shared/now (constantly "2026-04-01T00:01:00Z")]
          (scheduler/run-scheduler-step db-path))
        (with-redefs [scheduler.shared/now (constantly "2026-04-01T00:02:00Z")]
          (scheduler/run-scheduler-step db-path))
        (store.protocol/transition-run! store run-id
                                        {:transition/from :run.state/running
                                         :transition/to :run.state/running
                                         :changes {:run/heartbeat-timeout-seconds 1}}
                                        "2026-04-01T00:02:00Z")
        (with-redefs [scheduler.shared/now (constantly "2026-04-01T00:10:00Z")]
          (let [step-result (scheduler/run-scheduler-step db-path)
                task-after (scheduler/inspect-task! db-path task-id)
                run-after (scheduler/inspect-run! db-path run-id)]
            (testing "a stale heartbeat is allowed to poll to exit before timeout recovery runs"
              (is (= 1 (:snapshot/heartbeat-timeout-count (:snapshot step-result))))
              (is (empty? (:heartbeat-timeout-run-ids step-result)))
              (is (= :task.state/running (:task/state task-after)))
              (is (= :run.state/exited (:run/state run-after)))
              (is (= events/run-worker-exited
                     (:event/type (last (store.protocol/list-run-events store run-id))))))))))))

(deftest heartbeat-timeout-recovery-skips-when-run-has-already-advanced-in-db
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        task (support/enqueue-demo-task! db-path)
        {:keys [run-id lease-id]} (support/create-heartbeat-timeout-run! db-path task)
        store (store.sqlite/sqlite-state-store db-path)
        stale-run (scheduler/inspect-run! db-path run-id)
        stale-task (scheduler/inspect-task! db-path (:task/id task))
        env {:db-path db-path
             :store store
             :defs-repo (defs.loader/filesystem-definition-repository)
             :now "2026-04-01T01:00:00Z"}]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (store.protocol/transition-run! store run-id
                                      {:transition/from :run.state/running
                                       :transition/to :run.state/exited}
                                      "2026-04-01T00:03:00Z")
      (store.protocol/transition-task! store (:task/id task)
                                       {:transition/from :task.state/running
                                        :transition/to :task.state/awaiting-validation}
                                       "2026-04-01T00:03:00Z")
      (let [result (scheduler.runtime/recover-heartbeat-timeout! env
                                                                 stale-run
                                                                 stale-task
                                                                 {:timeout/kind :timeout.kind/heartbeat
                                                                  :timeout/seconds 60
                                                                  :timeout/last-heartbeat-at "2026-04-01T00:02:00Z"})
            run-after (scheduler/inspect-run! db-path run-id)
            task-after (scheduler/inspect-task! db-path (:task/id task))]
        (testing "the recovery transaction rechecks current row state before releasing the lease"
          (is (nil? result))
          (is (= :run.state/exited (:run/state run-after)))
          (is (= :task.state/awaiting-validation (:task/state task-after)))
          (is (= {:state ":lease.state/active"}
                 (select-keys (support/query-one db-path
                                                 "SELECT state FROM leases WHERE lease_id = ?"
                                                 [lease-id])
                              [:state])))
          (is (= [events/run-worker-started
                  events/run-worker-heartbeat]
                 (mapv :event/type (store.protocol/list-run-events store run-id)))))))))

(deftest heartbeat-timeout-recovery-rolls-back-when-a-transition-loses-the-race
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        task (support/enqueue-demo-task! db-path)
        {:keys [run-id lease-id]} (support/create-heartbeat-timeout-run! db-path task)
        store (store.sqlite/sqlite-state-store db-path)
        stale-run (scheduler/inspect-run! db-path run-id)
        stale-task (scheduler/inspect-task! db-path (:task/id task))
        env {:db-path db-path
             :store store
             :defs-repo (defs.loader/filesystem-definition-repository)
             :now "2026-04-01T01:00:00Z"}]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (with-redefs [store.sqlite/transition-task-via-connection! (fn [_ _ _ _] nil)]
        (let [result (scheduler.runtime/recover-heartbeat-timeout! env
                                                                   stale-run
                                                                   stale-task
                                                                   {:timeout/kind :timeout.kind/heartbeat
                                                                    :timeout/seconds 60
                                                                    :timeout/last-heartbeat-at "2026-04-01T00:02:00Z"})
              run-after (scheduler/inspect-run! db-path run-id)
              task-after (scheduler/inspect-task! db-path (:task/id task))]
          (testing "a partial optimistic write is rolled back instead of committing half the recovery"
            (is (nil? result))
            (is (= :run.state/running (:run/state run-after)))
            (is (= :task.state/running (:task/state task-after)))
            (is (= {:state ":lease.state/active"}
                   (select-keys (support/query-one db-path
                                                   "SELECT state FROM leases WHERE lease_id = ?"
                                                   [lease-id])
                                [:state])))
            (is (= [events/run-worker-started
                    events/run-worker-heartbeat]
                   (mapv :event/type (store.protocol/list-run-events store run-id))))))))))
