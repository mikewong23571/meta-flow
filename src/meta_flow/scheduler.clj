(ns meta-flow.scheduler
  (:require [meta-flow.scheduler.dev :as dev]
            [meta-flow.scheduler.runtime.run :as run]
            [meta-flow.scheduler.step :as step]))

(defn run-scheduler-step
  [db-path]
  (step/run-scheduler-step db-path))

(defn enqueue-demo-task!
  ([db-path]
   (dev/enqueue-demo-task! db-path))
  ([db-path options]
   (dev/enqueue-demo-task! db-path options)))

(defn enqueue-repo-arch-task!
  [db-path options]
  (dev/enqueue-repo-arch-task! db-path options))

(defn run-task-until-complete!
  [db-path task-id]
  (run/run-task-until-complete! db-path task-id run-scheduler-step))

(defn demo-happy-path!
  [db-path]
  (dev/demo-happy-path! db-path run-scheduler-step))

(defn demo-retry-path!
  [db-path]
  (dev/demo-retry-path! db-path run-scheduler-step))

(defn demo-codex-smoke!
  [db-path]
  (dev/demo-codex-smoke! db-path run-scheduler-step))

(defn inspect-task!
  [db-path task-id]
  (dev/inspect-task! db-path task-id))

(defn inspect-run!
  [db-path run-id]
  (dev/inspect-run! db-path run-id))

(defn inspect-collection!
  [db-path]
  (dev/inspect-collection! db-path))
