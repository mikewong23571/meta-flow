(ns meta-flow.scheduler.dispatch-capacity-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.control.projection :as projection]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.test-support :as support]))

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
      (let [blocked-task (support/enqueue-codex-task! db-path)
            runnable-task (support/enqueue-demo-task! db-path)]
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
