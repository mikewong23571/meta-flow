(ns meta-flow.store.sqlite.dispatch-projection-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.control.projection :as projection]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite.test-support :as support]))

(deftest scheduler-snapshot-exposes-active-dispatch-cooldown
  (let [{:keys [store reader]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        cooldown-until "2026-04-01T01:00:00Z"
        snapshot (do
                   (store.protocol/upsert-collection-state! store
                                                            (support/collection-state false
                                                                                      now
                                                                                      {:cooldown-until cooldown-until}))
                   (projection/load-scheduler-snapshot reader now))]
    (is (false? (:snapshot/dispatch-paused? snapshot)))
    (is (true? (:snapshot/dispatch-cooldown-active? snapshot)))
    (is (= cooldown-until
           (:snapshot/dispatch-cooldown-until snapshot)))))

(deftest projection-counts-active-runs-by-resource-policy
  (let [{:keys [store reader]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        serial-task (store.protocol/enqueue-task! store
                                                  (assoc (support/task "task-serial"
                                                                       "work/serial"
                                                                       now)
                                                         :task/resource-policy-ref {:definition/id :resource-policy/serial-cve
                                                                                    :definition/version 3}))
        default-task (store.protocol/enqueue-task! store
                                                   (support/task "task-default"
                                                                 "work/default"
                                                                 now))]
    (store.protocol/create-run! store serial-task
                                (support/run "run-serial" 1 now)
                                (support/lease "lease-serial" "run-serial" "2026-04-01T00:10:00Z" now))
    (store.protocol/create-run! store default-task
                                (support/run "run-default" 1 now)
                                (support/lease "lease-default" "run-default" "2026-04-01T00:11:00Z" now))
    (is (= 1
           (projection/count-active-runs-for-resource-policy reader
                                                             {:definition/id :resource-policy/serial-cve
                                                              :definition/version 3}
                                                             now)))
    (is (= 1
           (projection/count-active-runs-for-resource-policy reader
                                                             {:definition/id :resource-policy/default
                                                              :definition/version 3}
                                                             now)))))
