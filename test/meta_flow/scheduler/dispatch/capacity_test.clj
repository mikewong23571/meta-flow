(ns meta-flow.scheduler.dispatch.capacity-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.control.projection :as projection]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest stale-runnable-ids-do-not-consume-dispatch-capacity
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [task (support/enqueue-demo-task! db-path)
            task-id (:task/id task)]
        (with-redefs [projection/list-runnable-task-ids (fn [_ _ _]
                                                          ["missing-task-id" task-id])]
          (let [step-result (scheduler/run-scheduler-step db-path)]
            (is (= 1 (count (:created-runs step-result))))
            (is (empty? (:task-errors step-result)))
            (is (= 1
                   (:item_count (support/query-one db-path
                                                   "SELECT COUNT(*) AS item_count FROM runs WHERE task_id = ?"
                                                   [task-id]))))
            (is (= :task.state/leased
                   (:task/state (scheduler/inspect-task! db-path task-id))))))))))

(deftest scheduler-continues-past-runnable-tasks-that-fail-to-dispatch
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [blocked-task (support/enqueue-codex-task! db-path {:work-key "CVE-2024-AAAA-BLOCKED"})
            runnable-task (support/enqueue-demo-task! db-path {:work-key "CVE-2024-ZZZZ-RUNNABLE"})]
        (with-redefs [projection/list-runnable-task-ids (fn [_ _ _]
                                                          [(:task/id blocked-task)
                                                           (:task/id runnable-task)])]
          (let [step-result (scheduler/run-scheduler-step db-path)
                run-id (:run_id (support/query-one db-path
                                                   "SELECT run_id FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1"
                                                   [(:task/id runnable-task)]))]
            (is (= 1 (count (:created-runs step-result))))
            (is (= [{:task/id (:task/id blocked-task)
                     :task/work-key (:task/work-key blocked-task)
                     :error/message "Unsupported runtime adapter :runtime.adapter/codex"
                     :error/data {:adapter-id :runtime.adapter/codex}}]
                   (:task-errors step-result)))
            (is (= :task.state/queued
                   (:task/state (scheduler/inspect-task! db-path (:task/id blocked-task)))))
            (is (= :task.state/leased
                   (:task/state (scheduler/inspect-task! db-path (:task/id runnable-task)))))
            (is (= :run.state/dispatched
                   (:run/state (scheduler/inspect-run! db-path run-id))))))))))

(deftest task-policy-queue-order-prioritizes-work-key-ordered-tasks
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [later-work-key-task (support/enqueue-demo-task! db-path {:work-key "CVE-2024-ZZZZ"})
            earlier-work-key-task (support/enqueue-demo-task! db-path {:work-key "CVE-2024-AAAA"})
            step-result (scheduler/run-scheduler-step db-path)
            created-run (first (:created-runs step-result))]
        (is (= 1 (count (:created-runs step-result))))
        (is (= (:task/id earlier-work-key-task)
               (get-in created-run [:run :run/task-id])))
        (is (= :task.state/queued
               (:task/state (scheduler/inspect-task! db-path
                                                     (:task/id later-work-key-task)))))
        (is (= :task.state/leased
               (:task/state (scheduler/inspect-task! db-path
                                                     (:task/id earlier-work-key-task)))))))))

(deftest task-policy-queue-order-scans-beyond-the-default-runnable-window
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (doseq [idx (range 100)]
        (support/enqueue-demo-task! db-path
                                    {:work-key (format "CVE-2024-ZZZZ-%03d" idx)}))
      (let [preferred-task (support/enqueue-demo-task! db-path {:work-key "CVE-2024-AAAA"})
            step-result (scheduler/run-scheduler-step db-path)
            created-run (first (:created-runs step-result))]
        (is (= 1 (count (:created-runs step-result))))
        (is (= (:task/id preferred-task)
               (get-in created-run [:run :run/task-id])))
        (is (= :task.state/leased
               (:task/state (scheduler/inspect-task! db-path
                                                     (:task/id preferred-task)))))))))

(deftest resource-policy-ceilings-skip-saturated-policy-and-use-remaining-collection-capacity
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        serial-task (support/enqueue-demo-task! db-path {:work-key "CVE-2024-RESOURCE-A"})
        blocked-task (support/enqueue-demo-task! db-path {:work-key "CVE-2024-RESOURCE-B"})
        fallback-task (support/enqueue-demo-task! db-path {:work-key "CVE-2024-RESOURCE-C"
                                                           :resource-policy-ref {:definition/id :resource-policy/default
                                                                                 :definition/version 3}})
        store (store.sqlite/sqlite-state-store db-path)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (store.protocol/upsert-collection-state! store
                                               {:collection/id :collection/default
                                                :collection/dispatch {:dispatch/paused? false
                                                                      :dispatch/cooldown-until nil}
                                                :collection/resource-policy-ref {:definition/id :resource-policy/parallel-collection
                                                                                 :definition/version 1}
                                                :collection/created-at "2026-04-01T00:00:00Z"
                                                :collection/updated-at "2026-04-01T00:00:00Z"})
      (store.protocol/create-run! store serial-task
                                  {:run/id "run-serial-active"
                                   :run/attempt 1
                                   :run/run-fsm-ref (:task/run-fsm-ref serial-task)
                                   :run/runtime-profile-ref (:task/runtime-profile-ref serial-task)
                                   :run/state :run.state/leased
                                   :run/created-at "2026-04-01T00:00:00Z"
                                   :run/updated-at "2026-04-01T00:00:00Z"}
                                  {:lease/id "lease-serial-active"
                                   :lease/run-id "run-serial-active"
                                   :lease/token "lease-serial-active-token"
                                   :lease/state :lease.state/active
                                   :lease/expires-at "2099-04-01T00:30:00Z"
                                   :lease/created-at "2026-04-01T00:00:00Z"
                                   :lease/updated-at "2026-04-01T00:00:00Z"})
      (store.protocol/transition-task! store (:task/id serial-task)
                                       {:transition/from :task.state/queued
                                        :transition/to :task.state/leased}
                                       "2026-04-01T00:01:00Z")
      (let [step-result (scheduler/run-scheduler-step db-path)
            created-run (first (:created-runs step-result))]
        (is (= 1 (count (:created-runs step-result))))
        (is (= [(:task/id blocked-task)]
               (:capacity-skipped-task-ids step-result)))
        (is (= (:task/id fallback-task)
               (get-in created-run [:run :run/task-id])))
        (is (= :task.state/queued
               (:task/state (scheduler/inspect-task! db-path (:task/id blocked-task)))))
        (is (= :task.state/leased
               (:task/state (scheduler/inspect-task! db-path (:task/id fallback-task)))))))))
