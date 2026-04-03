(ns meta-flow.cli.inspect
  (:require [clojure.pprint :as pprint]
            [meta-flow.db :as db]
            [meta-flow.scheduler :as scheduler]))

(defn- print-inspect!
  [value]
  (pprint/pprint value))

(defn run-task!
  [task-id]
  (print-inspect! (scheduler/inspect-task! db/default-db-path task-id)))

(defn run-run!
  [run-id]
  (print-inspect! (scheduler/inspect-run! db/default-db-path run-id)))

(defn run-collection!
  []
  (print-inspect! (scheduler/inspect-collection! db/default-db-path)))
