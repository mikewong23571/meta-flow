(ns meta-flow.store.sqlite-runs-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.store.sqlite.run.lifecycle :as lifecycle]
            [meta-flow.store.sqlite.run.rows :as run-rows]
            [meta-flow.store.sqlite.runs :as runs]))

(deftest runs-namespace-delegates-read-and-write-boundaries
  (let [calls (atom [])]
    (with-redefs [run-rows/find-run-row (fn [connection run-id]
                                          (swap! calls conj [:find-run-row connection run-id])
                                          {:run-id run-id})
                  run-rows/find-latest-run-row-for-task (fn [connection task-id]
                                                          (swap! calls conj [:find-latest-run-row-for-task connection task-id])
                                                          {:task-id task-id})
                  run-rows/run-event-count (fn [connection run-id]
                                             (swap! calls conj [:run-event-count connection run-id])
                                             3)
                  run-rows/next-run-attempt (fn [connection task-id]
                                              (swap! calls conj [:next-run-attempt connection task-id])
                                              4)
                  run-rows/update-run-row! (fn [connection run-id update-fn]
                                             (swap! calls conj [:update-run-row! connection run-id update-fn])
                                             {:updated-run run-id})
                  run-rows/require-run-task-id! (fn [connection run-id]
                                                  (swap! calls conj [:require-run-task-id! connection run-id])
                                                  "task-1")
                  run-rows/insert-run! (fn [connection run]
                                         (swap! calls conj [:insert-run! connection run])
                                         :inserted)
                  run-rows/update-run-artifact! (fn [connection run-id artifact-id]
                                                  (swap! calls conj [:update-run-artifact! connection run-id artifact-id])
                                                  {:artifact-id artifact-id})
                  run-rows/update-run-transition! (fn [connection run-id from-state to-state now transition]
                                                    (swap! calls conj [:update-run-transition! connection run-id from-state to-state now transition])
                                                    {:transition to-state})
                  run-rows/transition-run-via-connection! (fn [connection run-id transition now]
                                                            (swap! calls conj [:transition-run-via-connection! connection run-id transition now])
                                                            {:run-id run-id :transition transition})
                  lifecycle/create-run! (fn [db-path task run lease]
                                          (swap! calls conj [:create-run! db-path task run lease])
                                          {:run run :lease lease})
                  lifecycle/claim-task-for-run! (fn [db-path task run lease task-transition run-transition now]
                                                  (swap! calls conj [:claim-task-for-run! db-path task run lease task-transition run-transition now])
                                                  {:task task :run run})
                  lifecycle/recover-run-startup-failure! (fn [db-path task run now]
                                                           (swap! calls conj [:recover-run-startup-failure! db-path task run now])
                                                           {:recovery/status :recovered})
                  lifecycle/find-run (fn [db-path run-id]
                                       (swap! calls conj [:find-run db-path run-id])
                                       {:run/id run-id})
                  lifecycle/find-latest-run-for-task (fn [db-path task-id]
                                                       (swap! calls conj [:find-latest-run-for-task db-path task-id])
                                                       {:task/id task-id})
                  lifecycle/transition-run! (fn [db-path run-id transition now]
                                              (swap! calls conj [:transition-run! db-path run-id transition now])
                                              {:run/id run-id :run/state (:transition/to transition)})]
      (let [connection ::connection
            db-path "var/test.sqlite3"
            update-fn identity
            task {:task/id "task-1"}
            run {:run/id "run-1"}
            lease {:lease/id "lease-1"}
            task-transition {:transition/from :task.state/queued
                             :transition/to :task.state/leased}
            run-transition {:transition/from :run.state/created
                            :transition/to :run.state/leased}
            transition {:transition/from :run.state/leased
                        :transition/to :run.state/dispatched}]
        (is (= {:run-id "run-1"} (runs/find-run-row connection "run-1")))
        (is (= {:task-id "task-1"} (runs/find-latest-run-row-for-task connection "task-1")))
        (is (= 3 (runs/run-event-count connection "run-1")))
        (is (= 4 (runs/next-run-attempt connection "task-1")))
        (is (= {:updated-run "run-1"} (runs/update-run-row! connection "run-1" update-fn)))
        (is (= "task-1" (runs/require-run-task-id! connection "run-1")))
        (is (= :inserted (runs/insert-run! connection run)))
        (is (= {:artifact-id "artifact-1"} (runs/update-run-artifact! connection "run-1" "artifact-1")))
        (is (= {:transition :run.state/dispatched}
               (runs/update-run-transition! connection "run-1" :run.state/leased :run.state/dispatched "2026-04-01T00:00:00Z" transition)))
        (is (= {:run-id "run-1" :transition transition}
               (runs/transition-run-via-connection! connection "run-1" transition "2026-04-01T00:00:00Z")))
        (is (= {:run run :lease lease} (runs/create-run! db-path task run lease)))
        (is (= {:task task :run run}
               (runs/claim-task-for-run! db-path task run lease task-transition run-transition "2026-04-01T00:00:00Z")))
        (is (= {:recovery/status :recovered}
               (runs/recover-run-startup-failure! db-path task run "2026-04-01T00:00:00Z")))
        (is (= {:run/id "run-1"} (runs/find-run db-path "run-1")))
        (is (= {:task/id "task-1"} (runs/find-latest-run-for-task db-path "task-1")))
        (is (= {:run/id "run-1" :run/state :run.state/dispatched}
               (runs/transition-run! db-path "run-1" transition "2026-04-01T00:00:00Z"))))
      (is (= 16 (count @calls))))))
