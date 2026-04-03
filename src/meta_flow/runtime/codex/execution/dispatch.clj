(ns meta-flow.runtime.codex.execution.dispatch
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.codex.events :as codex.events]
            [meta-flow.runtime.codex.fs :as fs]
            [meta-flow.runtime.codex.home :as codex.home]
            [meta-flow.runtime.codex.process.launch :as process.launch]
            [meta-flow.runtime.codex.process.state :as process.state]))

(defn- runtime-profile
  [repository runtime-profile-ref]
  (defs.protocol/find-runtime-profile repository
                                      (:definition/id runtime-profile-ref)
                                      (:definition/version runtime-profile-ref)))

(defn- artifact-contract
  [repository artifact-contract-ref]
  (defs.protocol/find-artifact-contract repository
                                        (:definition/id artifact-contract-ref)
                                        (:definition/version artifact-contract-ref)))

(defn- pinned-definitions
  [repository task run]
  (let [workflow-defs (defs.protocol/load-workflow-defs repository)]
    {:workflow (:workflow workflow-defs)
     :task-type (defs.protocol/find-task-type-def repository
                                                  (get-in task [:task/task-type-ref :definition/id])
                                                  (get-in task [:task/task-type-ref :definition/version]))
     :task-fsm (defs.protocol/find-task-fsm-def repository
                                                (get-in task [:task/task-fsm-ref :definition/id])
                                                (get-in task [:task/task-fsm-ref :definition/version]))
     :run-fsm (defs.protocol/find-run-fsm-def repository
                                              (get-in run [:run/run-fsm-ref :definition/id])
                                              (get-in run [:run/run-fsm-ref :definition/version]))
     :artifact-contract (artifact-contract repository (:task/artifact-contract-ref task))
     :validator (defs.protocol/find-validator-def repository
                                                  (get-in task [:task/validator-ref :definition/id])
                                                  (get-in task [:task/validator-ref :definition/version]))
     :runtime-profile (runtime-profile repository (:task/runtime-profile-ref task))
     :resource-policy (defs.protocol/find-resource-policy repository
                                                          (get-in task [:task/resource-policy-ref :definition/id])
                                                          (get-in task [:task/resource-policy-ref :definition/version]))}))

(defn- render-worker-prompt
  [prompt-resource-path task run artifact-root log-path artifact-contract-now]
  (let [template (if-let [resource (io/resource prompt-resource-path)]
                   (slurp resource)
                   (throw (ex-info (str "Missing worker prompt resource " prompt-resource-path)
                                   {:resource-path prompt-resource-path})))]
    (str template
         "\n\n## Prepared Run\n"
         "- Task ID: `" (:task/id task) "`\n"
         "- Run ID: `" (:run/id run) "`\n"
         "- Artifact root: `" (fs/absolute-path artifact-root) "`\n"
         "- Run log path: `" (fs/absolute-path log-path) "`\n"
         "- Snapshot files: `task.edn`, `run.edn`, `definitions.edn`, `runtime-profile.edn`, `artifact-contract.edn`, `worker-prompt.md`\n"
         "- Required artifact paths: `" (str/join "`, `" (:artifact-contract/required-paths artifact-contract-now)) "`\n"
         "\nThe runtime wrapper will emit worker lifecycle events. Your job in this run is to produce the artifact bundle only.\n")))

(defn- prepared-runtime-profile
  [runtime-profile-now run-id]
  (-> runtime-profile-now
      (assoc :runtime-profile/codex-home-root
             (codex.home/codex-home-root runtime-profile-now))
      (assoc :runtime-profile/helper-script-path
             (fs/absolute-path (:runtime-profile/helper-script-path runtime-profile-now)))
      (assoc :runtime-profile/worker-prompt-path
             (fs/absolute-path (fs/worker-prompt-path run-id)))))

(defn prepare-run!
  [repository task run]
  (let [task-id (:task/id task)
        run-id (:run/id run)
        workdir (fs/run-workdir run-id)
        artifact-root (fs/artifact-root-path task-id run-id)
        log-path (fs/run-log-path task-id run-id)
        runtime-profile-now (runtime-profile repository (:task/runtime-profile-ref task))
        prepared-profile (prepared-runtime-profile runtime-profile-now run-id)
        definitions (pinned-definitions repository task run)
        artifact-contract-now (artifact-contract repository (:task/artifact-contract-ref task))]
    (fs/ensure-directory! workdir)
    (fs/ensure-directory! artifact-root)
    (fs/write-edn-file! (fs/definitions-path run-id) definitions)
    (fs/write-edn-file! (fs/task-path run-id) task)
    (fs/write-edn-file! (fs/run-path run-id) run)
    (fs/write-edn-file! (fs/runtime-profile-path run-id) prepared-profile)
    (fs/write-edn-file! (fs/artifact-contract-path run-id) artifact-contract-now)
    (fs/write-text-file! (fs/worker-prompt-path run-id)
                         (render-worker-prompt (:runtime-profile/worker-prompt-path runtime-profile-now)
                                               task
                                               run
                                               artifact-root
                                               log-path
                                               artifact-contract-now))
    (fs/append-text-file! log-path
                          (str "prepared codex run " run-id " for task " task-id "\n"))
    {:runtime-run/workdir (fs/absolute-path workdir)
     :runtime-run/artifact-root (fs/absolute-path artifact-root)
     :runtime-run/log-path (fs/absolute-path log-path)}))

(defn dispatch-run!
  [{:keys [repository now store db-path]} task run]
  (let [runtime-profile-now (runtime-profile repository (:task/runtime-profile-ref task))
        home-install (codex.home/install-home! runtime-profile-now)
        process-path (fs/process-path (:run/id run))
        launch-mode (process.launch/launch-mode runtime-profile-now)
        process-state (process.launch/base-process-state runtime-profile-now
                                                         home-install
                                                         task
                                                         run
                                                         now
                                                         launch-mode)
        command (process.launch/launch-command db-path
                                               (:run/id run)
                                               runtime-profile-now
                                               launch-mode)]
    (fs/write-json-file! process-path process-state)
    (event-ingest/ingest-run-event! store
                                    (codex.events/runtime-event-intent run
                                                                       events/run-dispatched
                                                                       "dispatched"
                                                                       {:launch/mode (name launch-mode)
                                                                        :launch/status :launch.status/pending}
                                                                       now))
    {:runtime-run/dispatch "external-process"
     :runtime-run/process-path (fs/absolute-path process-path)
     :runtime-run/workdir (fs/absolute-path (fs/run-workdir (:run/id run)))
     :runtime-run/artifact-root (fs/absolute-path (fs/artifact-root-path (:task/id task)
                                                                         (:run/id run)))
     :runtime-run/command command
     :runtime-run/launch-mode launch-mode}))

(defn launch-process!
  [{:keys [repository db-path]} run process-state workdir now]
  (let [runtime-profile-now (runtime-profile repository (:run/runtime-profile-ref run))
        task (fs/read-edn-file (str workdir "/task.edn"))
        home-install {:codex-home/root (codex.home/codex-home-root runtime-profile-now)}
        command (process.launch/launch-command db-path
                                               (:run/id run)
                                               runtime-profile-now
                                               (process.launch/persisted-launch-mode process-state runtime-profile-now))
        process (.start (process.launch/build-process-builder command
                                                              runtime-profile-now
                                                              home-install
                                                              task
                                                              run))
        process-path (process.launch/process-path-for-run run)]
    (fs/update-json-file! process-path #(process.state/merge-started-process-state % command now process))))
