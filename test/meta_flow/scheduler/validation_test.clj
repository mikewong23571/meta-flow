(ns meta-flow.scheduler.validation-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.control.events :as events]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.state :as scheduler.state]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest rejected-validation-is-recorded-once-per-run
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [task (support/enqueue-demo-task! db-path)
            task-id (:task/id task)
            _ (support/advance-scheduler! db-path 4)
            run-id (:run_id (support/query-one db-path
                                               "SELECT run_id FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1"
                                               [task-id]))
            run-before-validation (scheduler/inspect-run! db-path run-id)
            artifact-root (:run/artifact-root run-before-validation)]
        (testing "a rejected validation converges after the first scheduler pass"
          (is (.delete (io/file artifact-root "notes.md")))
          (let [first-step (scheduler/run-scheduler-step db-path)
                task-after-first (scheduler/inspect-task! db-path task-id)
                run-after-first (scheduler/inspect-run! db-path run-id)
                second-step (scheduler/run-scheduler-step db-path)
                task-after-second (scheduler/inspect-task! db-path task-id)
                run-after-second (scheduler/inspect-run! db-path run-id)]
            (is (empty? (:created-runs first-step)))
            (is (empty? (:created-runs second-step)))
            (is (= :task.state/retryable-failed (:task/state task-after-first)))
            (is (= :run.state/retryable-failed (:run/state run-after-first)))
            (is (= [task-id] (:requeued-task-ids second-step)))
            (is (= :task.state/queued (:task/state task-after-second)))
            (is (= :run.state/retryable-failed (:run/state run-after-second)))
            (is (= 9 (:run/event-count run-after-second)))
            (is (string? (:run/last-heartbeat run-after-second)))
            (is (= {:status ":assessment/rejected"
                    :item_count 1}
                   (select-keys (support/query-one db-path
                                                   "SELECT status, COUNT(*) AS item_count FROM assessments WHERE run_id = ? GROUP BY status"
                                                   [run-id])
                                [:status :item_count])))
            (is (= {:disposition_type ":disposition/rejected"
                    :item_count 1}
                   (select-keys (support/query-one db-path
                                                   (str "SELECT disposition_type, COUNT(*) AS item_count "
                                                        "FROM dispositions WHERE run_id = ? GROUP BY disposition_type")
                                                   [run-id])
                                [:disposition_type :item_count])))
            (is (= [events/run-dispatched
                    events/task-worker-started
                    events/run-worker-started
                    events/run-worker-heartbeat
                    events/run-worker-exited
                    events/run-artifact-ready
                    events/task-artifact-ready
                    events/run-assessment-rejected
                    events/task-assessment-rejected]
                   (mapv :event/type
                         (store.protocol/list-run-events (store.sqlite/sqlite-state-store db-path) run-id))))))))))

(deftest awaiting-validation-retries-reuse-the-same-assessment-and-disposition-records
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [task (support/enqueue-demo-task! db-path)
            task-id (:task/id task)
            _ (support/advance-scheduler! db-path 4)
            run-id (:run_id (support/query-one db-path
                                               "SELECT run_id FROM runs WHERE task_id = ? ORDER BY attempt DESC LIMIT 1"
                                               [task-id]))]
        (with-redefs-fn {#'scheduler.state/emit-event! (fn [& _] nil)}
          (fn []
            (scheduler/run-scheduler-step db-path)
            (let [task-before (scheduler/inspect-task! db-path task-id)
                  run-before (scheduler/inspect-run! db-path run-id)]
              (testing "the seed scheduler step reaches awaiting-validation"
                (is (= :task.state/awaiting-validation
                       (:task/state task-before)))
                (is (= :run.state/awaiting-validation
                       (:run/state run-before))))
              (scheduler/run-scheduler-step db-path)
              (scheduler/run-scheduler-step db-path)
              (testing "repeated scheduler passes reuse one semantic validation round"
                (is (= {:item_count 1
                        :assessment_key "validation/current"
                        :status ":assessment/accepted"}
                       (select-keys (support/query-one db-path
                                                       (str "SELECT COUNT(*) AS item_count, assessment_key, status "
                                                            "FROM assessments WHERE run_id = ? GROUP BY assessment_key, status")
                                                       [run-id])
                                    [:item_count :assessment_key :status])))
                (is (= {:item_count 1
                        :disposition_key "decision/current"
                        :disposition_type ":disposition/accepted"}
                       (select-keys (support/query-one db-path
                                                       (str "SELECT COUNT(*) AS item_count, disposition_key, disposition_type "
                                                            "FROM dispositions WHERE run_id = ? GROUP BY disposition_key, disposition_type")
                                                       [run-id])
                                    [:item_count :disposition_key :disposition_type])))
                (is (= :task.state/awaiting-validation
                       (:task/state (scheduler/inspect-task! db-path task-id))))
                (is (= :run.state/awaiting-validation
                       (:run/state (scheduler/inspect-run! db-path run-id))))
                (is (= (:task/updated-at task-before)
                       (:updated_at (support/query-one db-path
                                                       "SELECT updated_at FROM tasks WHERE task_id = ?"
                                                       [task-id]))))
                (is (= (:run/updated-at run-before)
                       (:updated_at (support/query-one db-path
                                                       "SELECT updated_at FROM runs WHERE run_id = ?"
                                                       [run-id]))))))))))))
