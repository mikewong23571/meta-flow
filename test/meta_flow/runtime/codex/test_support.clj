(ns meta-flow.runtime.codex.test-support
  (:require [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.support.test-support :as support]))

(defn codex-profile-with-temp-home
  [repository codex-home-dir]
  (assoc (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1)
         :runtime-profile/codex-home-root codex-home-dir))

(defn repository-with-temp-codex-home
  [repository codex-home-dir]
  (reify defs.protocol/DefinitionRepository
    (load-workflow-defs [_]
      (defs.protocol/load-workflow-defs repository))
    (find-task-type-def [_ task-type-id version]
      (defs.protocol/find-task-type-def repository task-type-id version))
    (find-run-fsm-def [_ run-fsm-id version]
      (defs.protocol/find-run-fsm-def repository run-fsm-id version))
    (find-task-fsm-def [_ task-fsm-id version]
      (defs.protocol/find-task-fsm-def repository task-fsm-id version))
    (find-artifact-contract [_ contract-id version]
      (defs.protocol/find-artifact-contract repository contract-id version))
    (find-validator-def [_ validator-id version]
      (defs.protocol/find-validator-def repository validator-id version))
    (find-runtime-profile [_ runtime-profile-id version]
      (cond-> (defs.protocol/find-runtime-profile repository runtime-profile-id version)
        (= runtime-profile-id :runtime-profile/codex-worker)
        (assoc :runtime-profile/codex-home-root codex-home-dir)))
    (find-resource-policy [_ resource-policy-id version]
      (defs.protocol/find-resource-policy repository resource-policy-id version))))

(def ^:private max-terminal-codex-run-attempts
  300)

(defn wait-for-terminal-codex-run!
  [db-path task-id]
  (loop [attempt 0]
    (let [result (try
                   {:status :ok
                    :task (scheduler/inspect-task! db-path task-id)
                    :run (some->> (:run_id (support/query-one db-path
                                                              (str "SELECT run_id FROM runs "
                                                                   "WHERE task_id = ? ORDER BY attempt DESC LIMIT 1")
                                                              [task-id]))
                                  (scheduler/inspect-run! db-path))}
                   (catch org.sqlite.SQLiteException ex
                     (if (re-find #"database is locked" (.getMessage ex))
                       {:status :busy}
                       (throw ex))))]
      (cond
        (= :busy (:status result))
        (do
          (Thread/sleep 100)
          (recur attempt))

        (and (:run result)
             (= :task.state/completed (get-in result [:task :task/state]))
             (= :run.state/finalized (get-in result [:run :run/state])))
        {:task (:task result)
         :run (:run result)}

        (>= attempt max-terminal-codex-run-attempts)
        (throw (ex-info "Codex managed worker did not converge"
                        {:task (:task result)
                         :run (:run result)
                         :attempt attempt}))

        :else
        (do
          (Thread/sleep 100)
          (scheduler/run-scheduler-step db-path)
          (recur (inc attempt)))))))
