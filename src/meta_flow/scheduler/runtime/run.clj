(ns meta-flow.scheduler.runtime.run
  (:require [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.scheduler.dev :as dev]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(def poll-ms
  2000)

(defn max-run-steps
  [runtime-profile]
  (dev/codex-max-steps runtime-profile poll-ms))

(defn- task-terminal?
  [task]
  (when task
    (contains? #{:task.state/completed :task.state/needs-review} (:task/state task))))

(defn run-task-until-complete!
  [db-path task-id run-scheduler-step-fn]
  (let [store (store.sqlite/sqlite-state-store db-path)
        defs-repo (defs.loader/filesystem-definition-repository)
        task (or (store.protocol/find-task store task-id)
                 (throw (ex-info (str "Task not found: " task-id) {:task-id task-id})))
        profile (defs.protocol/find-runtime-profile
                 defs-repo
                 (get-in task [:task/runtime-profile-ref :definition/id])
                 (get-in task [:task/runtime-profile-ref :definition/version]))
        max-steps (max-run-steps profile)]
    (loop [step 0]
      (let [task-now (store.protocol/find-task store task-id)
            run-now (shared/latest-run store task-id)
            state (:task/state task-now)]
        (println (str "  step " step
                      "  task=" (name state)
                      (when run-now (str "  run=" (name (:run/state run-now))))))
        (cond
          (task-terminal? task-now)
          {:task task-now
           :run run-now
           :artifact-root (shared/run-artifact-root store run-now)
           :scheduler-steps step}

          (>= step max-steps)
          (throw (ex-info "Task did not reach terminal state within the expected scheduler steps"
                          {:task task-now
                           :run run-now
                           :scheduler-steps step
                           :max-steps max-steps}))

          :else
          (do
            (run-scheduler-step-fn db-path)
            (Thread/sleep poll-ms)
            (recur (inc step))))))))
