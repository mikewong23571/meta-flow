(ns meta-flow.store.sqlite.runs
  (:require [meta-flow.store.sqlite.run.lifecycle :as lifecycle]
            [meta-flow.store.sqlite.run.rows :as run-rows]))

(defn find-run-row
  [connection run-id]
  (run-rows/find-run-row connection run-id))

(defn find-latest-run-row-for-task
  [connection task-id]
  (run-rows/find-latest-run-row-for-task connection task-id))

(defn run-event-count
  [connection run-id]
  (run-rows/run-event-count connection run-id))

(defn next-run-attempt
  [connection task-id]
  (run-rows/next-run-attempt connection task-id))

(defn update-run-row!
  [connection run-id update-fn]
  (run-rows/update-run-row! connection run-id update-fn))

(defn require-run-task-id!
  [connection run-id]
  (run-rows/require-run-task-id! connection run-id))

(defn insert-run!
  [connection run]
  (run-rows/insert-run! connection run))

(defn update-run-artifact!
  [connection run-id artifact-id]
  (run-rows/update-run-artifact! connection run-id artifact-id))

(defn update-run-transition!
  [connection run-id from-state to-state now transition]
  (run-rows/update-run-transition! connection run-id from-state to-state now transition))

(defn transition-run-via-connection!
  [connection run-id transition now]
  (run-rows/transition-run-via-connection! connection run-id transition now))

(defn create-run!
  [db-path task run lease]
  (lifecycle/create-run! db-path task run lease))

(defn claim-task-for-run!
  [db-path task run lease task-transition run-transition now]
  (lifecycle/claim-task-for-run! db-path task run lease task-transition run-transition now))

(defn recover-run-startup-failure!
  [db-path task run now]
  (lifecycle/recover-run-startup-failure! db-path task run now))

(defn find-run
  [db-path run-id]
  (lifecycle/find-run db-path run-id))

(defn find-latest-run-for-task
  [db-path task-id]
  (lifecycle/find-latest-run-for-task db-path task-id))

(defn transition-run!
  [db-path run-id transition now]
  (lifecycle/transition-run! db-path run-id transition now))
