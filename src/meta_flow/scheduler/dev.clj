(ns meta-flow.scheduler.dev
  (:require [meta-flow.scheduler.dev.demo :as demo]
            [meta-flow.scheduler.dev.inspect :as inspect]
            [meta-flow.scheduler.dev.task :as task]))

(def demo-runtime-profile-ref
  task/demo-runtime-profile-ref)

(def codex-runtime-profile-ref
  task/codex-runtime-profile-ref)

(def max-demo-scheduler-steps
  demo/max-demo-scheduler-steps)

(def codex-smoke-sleep-ms
  demo/codex-smoke-sleep-ms)

(def codex-smoke-timeout-grace-seconds
  demo/codex-smoke-timeout-grace-seconds)

(defn codex-max-steps
  [runtime-profile sleep-ms]
  (demo/codex-max-steps runtime-profile sleep-ms))

(defn codex-smoke-max-steps
  [runtime-profile]
  (demo/codex-smoke-max-steps runtime-profile))

(defn build-demo-task
  [defs-repo options]
  (task/build-demo-task defs-repo options))

(defn build-repo-arch-task
  [defs-repo options]
  (task/build-repo-arch-task defs-repo options))

(defn enqueue-repo-arch-task!
  [db-path options]
  (task/enqueue-repo-arch-task! db-path options))

(defn enqueue-demo-task!
  ([db-path]
   (task/enqueue-demo-task! db-path))
  ([db-path options]
   (task/enqueue-demo-task! db-path options)))

(defn happy-path-complete?
  [task run]
  (demo/happy-path-complete? task run))

(defn retry-path-complete?
  [task run]
  (demo/retry-path-complete? task run))

(defn demo-happy-path!
  [db-path run-scheduler-step-fn]
  (demo/demo-happy-path! db-path run-scheduler-step-fn))

(defn demo-retry-path!
  [db-path run-scheduler-step-fn]
  (demo/demo-retry-path! db-path run-scheduler-step-fn))

(defn demo-codex-smoke!
  [db-path run-scheduler-step-fn]
  (demo/demo-codex-smoke! db-path run-scheduler-step-fn))

(defn inspect-task!
  [db-path task-id]
  (inspect/inspect-task! db-path task-id))

(defn inspect-run!
  [db-path run-id]
  (inspect/inspect-run! db-path run-id))

(defn inspect-collection!
  [db-path]
  (inspect/inspect-collection! db-path))
