(ns meta-flow.runtime.codex.execution
  (:require [clojure.java.io :as io]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.codex.fs :as fs]
            [meta-flow.runtime.codex.home :as codex.home]))

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
  [prompt-resource-path task run artifact-root log-path]
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
         "- Snapshot files: `task.edn`, `run.edn`, `definitions.edn`, `runtime-profile.edn`, `artifact-contract.edn`, `worker-prompt.md`\n")))

(defn- environment-keys
  [runtime-profile]
  (->> (:runtime-profile/env-allowlist runtime-profile)
       (filter #(contains? (System/getenv) %))
       vec))

(defn- process-path-for-run
  [run]
  (or (get-in run [:run/execution-handle :runtime-run/process-path])
      (fs/process-path (:run/id run))))

(defn- prepared-runtime-profile
  [runtime-profile run-id]
  (-> runtime-profile
      (assoc :runtime-profile/codex-home-root
             (codex.home/codex-home-root runtime-profile))
      (assoc :runtime-profile/helper-script-path
             (fs/absolute-path (:runtime-profile/helper-script-path runtime-profile)))
      (assoc :runtime-profile/worker-prompt-path
             (fs/absolute-path (fs/worker-prompt-path run-id)))))

(defn ensure-launch-supported!
  [task]
  (throw (ex-info "Codex runtime launch is not implemented yet"
                  {:task/id (:task/id task)
                   :task/work-key (:task/work-key task)
                   :runtime-profile-ref (:task/runtime-profile-ref task)})))

(defn prepare-run!
  [{:keys [repository]} task run]
  (let [task-id (:task/id task)
        run-id (:run/id run)
        workdir (fs/run-workdir run-id)
        artifact-root (fs/artifact-root-path task-id run-id)
        log-path (fs/run-log-path task-id run-id)
        runtime-profile (runtime-profile repository (:task/runtime-profile-ref task))
        prepared-profile (prepared-runtime-profile runtime-profile run-id)
        definitions (pinned-definitions repository task run)
        artifact-contract (artifact-contract repository (:task/artifact-contract-ref task))]
    (fs/ensure-directory! workdir)
    (fs/ensure-directory! artifact-root)
    (fs/write-edn-file! (fs/definitions-path run-id) definitions)
    (fs/write-edn-file! (fs/task-path run-id) task)
    (fs/write-edn-file! (fs/run-path run-id) run)
    (fs/write-edn-file! (fs/runtime-profile-path run-id) prepared-profile)
    (fs/write-edn-file! (fs/artifact-contract-path run-id) artifact-contract)
    (fs/write-text-file! (fs/worker-prompt-path run-id)
                         (render-worker-prompt (:runtime-profile/worker-prompt-path runtime-profile)
                                               task
                                               run
                                               artifact-root
                                               log-path))
    (fs/append-text-file! log-path
                          (str "prepared codex run " run-id " for task " task-id "\n"))
    {:runtime-run/workdir (fs/absolute-path workdir)
     :runtime-run/artifact-root (fs/absolute-path artifact-root)
     :runtime-run/log-path (fs/absolute-path log-path)}))

(defn dispatch-run!
  [{:keys [repository now]} task run]
  (let [task-id (:task/id task)
        run-id (:run/id run)
        runtime-profile (runtime-profile repository (:task/runtime-profile-ref task))
        home-install (codex.home/install-home! runtime-profile)
        process-state {:adapterId (str (:runtime-profile/adapter-id runtime-profile))
                       :runId run-id
                       :taskId task-id
                       :status "prepared"
                       :dispatchMode (str (:runtime-profile/dispatch-mode runtime-profile))
                       :codexHome (:codex-home/root home-install)
                       :workdir (fs/absolute-path (fs/run-workdir run-id))
                       :artifactRoot (fs/absolute-path (fs/artifact-root-path task-id run-id))
                       :logPath (fs/absolute-path (fs/run-log-path task-id run-id))
                       :promptPath (fs/absolute-path (fs/worker-prompt-path run-id))
                       :helperScriptPath (fs/absolute-path (:runtime-profile/helper-script-path runtime-profile))
                       :allowedMcpServers (mapv str (:runtime-profile/allowed-mcp-servers runtime-profile))
                       :webSearchEnabled (boolean (:runtime-profile/web-search-enabled? runtime-profile))
                       :envKeys (environment-keys runtime-profile)
                       :installedTemplates (mapv fs/absolute-path
                                                 (:codex-home/installed-paths home-install))
                       :createdAt now}
        process-path (fs/process-path run-id)]
    (fs/write-json-file! process-path process-state)
    (fs/append-text-file! (fs/run-log-path task-id run-id)
                          (str "codex launch not implemented for run " run-id "\n"))
    (ensure-launch-supported! task)))

(defn poll-run!
  [_ run _]
  (let [process-state (fs/read-json-file (process-path-for-run run))]
    (cond
      (nil? process-state) []
      (= "cancel-requested" (:status process-state)) []
      :else [])))

(defn cancel-run!
  [run reason]
  (let [run-id (:run/id run)
        process-path (process-path-for-run run)
        process-state (or (fs/read-json-file process-path)
                          {:runId run-id
                           :status "missing"})]
    (fs/write-json-file! process-path
                         (assoc process-state
                                :status "cancel-requested"
                                :cancelReason (pr-str reason)))
    {:status :cancel-requested}))
