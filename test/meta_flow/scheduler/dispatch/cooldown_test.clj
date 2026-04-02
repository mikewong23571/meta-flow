(ns meta-flow.scheduler.dispatch.cooldown-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest dispatch-cooldown-blocks-new-runs-but-allows-requeue-convergence
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        store (store.sqlite/sqlite-state-store db-path)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [{:keys [task]} (scheduler/demo-retry-path! db-path)
            queued-task (support/enqueue-demo-task! db-path {:work-key "CVE-2024-COOLDOWN-QUEUED"})
            cooldown-until "2099-04-01T00:30:00Z"]
        (store.protocol/upsert-collection-state! store
                                                 {:collection/id :collection/default
                                                  :collection/dispatch {:dispatch/paused? false
                                                                        :dispatch/cooldown-until cooldown-until}
                                                  :collection/resource-policy-ref {:definition/id :resource-policy/default
                                                                                   :definition/version 3}
                                                  :collection/created-at "2026-04-01T00:00:00Z"
                                                  :collection/updated-at "2026-04-01T00:10:00Z"})
        (let [step-result (scheduler/run-scheduler-step db-path)
              queued-task-after (scheduler/inspect-task! db-path (:task/id queued-task))
              failed-task-after (scheduler/inspect-task! db-path (:task/id task))]
          (testing "dispatch is skipped while retry control remains active"
            (is (empty? (:created-runs step-result)))
            (is (= :dispatch.block/cooldown
                   (:dispatch-block-reason step-result)))
            (is (= [(:task/id task)]
                   (:requeued-task-ids step-result)))
            (is (= :task.state/queued
                   (:task/state failed-task-after)))
            (is (= :task.state/queued
                   (:task/state queued-task-after)))
            (is (= 0
                   (:item_count (support/query-one db-path
                                                   "SELECT COUNT(*) AS item_count FROM runs WHERE task_id = ?"
                                                   [(:task/id queued-task)]))))))))))
