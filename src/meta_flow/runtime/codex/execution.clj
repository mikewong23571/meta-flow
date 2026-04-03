(ns meta-flow.runtime.codex.execution
  (:require [clojure.java.io :as io]
            [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.db :as db]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.codex.events :as codex.events]
            [meta-flow.runtime.codex.fs :as fs]
            [meta-flow.runtime.codex.home :as codex.home]
            [meta-flow.runtime.codex.process :as codex.process]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]))
(def ^:private helper-recovery-grace-seconds
  (inc (long (Math/ceil (/ (double (:busy_timeout db/sqlite-pragmas)) 1000.0)))))
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
(defn- prepared-runtime-profile
  [runtime-profile run-id]
  (-> runtime-profile
      (assoc :runtime-profile/codex-home-root
             (codex.home/codex-home-root runtime-profile))
      (assoc :runtime-profile/helper-script-path
             (fs/absolute-path (:runtime-profile/helper-script-path runtime-profile)))
      (assoc :runtime-profile/worker-prompt-path
             (fs/absolute-path (fs/worker-prompt-path run-id)))))
(defn- artifact-contract-ready?
  [process-state contract]
  (and (:artifactRoot process-state)
       (every? #(.exists (io/file (:artifactRoot process-state) %))
               (:artifact-contract/required-paths contract))))
(defn- existing-event-types
  [store run-id]
  (if store (->> (store.protocol/list-run-events store run-id) (map :event/type) set) #{}))
(defn- workdir-path
  [run process-state]
  (or (:workdir process-state)
      (when-let [process-path (get-in run [:run/execution-handle :runtime-run/process-path])]
        (.getCanonicalPath (.getParentFile (io/file process-path))))
      (fs/run-workdir (:run/id run))))
(defn- maybe-attach-artifact!
  [store run task process-state now]
  (let [artifact-id (:artifactId process-state)
        artifact-root (:artifactRoot process-state)]
    (when (and store
               task
               artifact-id
               artifact-root)
      (store.protocol/attach-artifact! store
                                       (:run/id run)
                                       {:artifact/id artifact-id
                                        :artifact/run-id (:run/id run)
                                        :artifact/task-id (:task/id task)
                                        :artifact/contract-ref (:task/artifact-contract-ref task)
                                        :artifact/root-path artifact-root
                                        :artifact/created-at now}))))
(defn- helper-transition-in-flight?
  [process-state status-key active-states grace-seconds now]
  (when-let [updated-at (get-in process-state [status-key :updated-at])]
    (let [state (get-in process-state [status-key :state])]
      (and (contains? active-states state)
           (.isAfter (.plusSeconds (java.time.Instant/parse updated-at)
                                   grace-seconds)
                     (java.time.Instant/parse now))))))
(defn- poll-start-events
  [run event-types process-state now]
  (when (and (codex.process/started? process-state)
             (not (get-in process-state [:helperEvents :workerStarted]))
             (not (helper-transition-in-flight? process-state
                                                :workerStartedStatus
                                                #{"in-flight"}
                                                helper-recovery-grace-seconds
                                                now))
             (or (not (contains? event-types events/task-worker-started))
                 (not (contains? event-types events/run-worker-started))))
    (cond-> []
      (not (contains? event-types events/task-worker-started))
      (conj (codex.events/poll-event-intent run events/task-worker-started
                                            "worker-started" {} now))
      (not (contains? event-types events/run-worker-started))
      (conj (codex.events/poll-event-intent run events/run-worker-started
                                            "worker-started" {} now)))))
(defn- poll-never-started-cancel-events
  [run event-types process-state now]
  (when (and (codex.process/cancelled? process-state)
             (codex.process/exited? process-state)
             (codex.process/never-started? process-state)
             (or (not (contains? event-types events/run-heartbeat-timed-out))
                 (not (contains? event-types events/task-heartbeat-timed-out))))
    (let [payload {:timeout/kind :timeout.kind/heartbeat
                   :timeout/reason :timeout.reason/cancelled-before-start}]
      (cond-> []
        (not (contains? event-types events/run-heartbeat-timed-out))
        (conj (codex.events/poll-event-intent run events/run-heartbeat-timed-out
                                              "never-started-cancel" payload now))
        (not (contains? event-types events/task-heartbeat-timed-out))
        (conj (codex.events/poll-event-intent run events/task-heartbeat-timed-out
                                              "never-started-cancel" payload now))))))
(defn- poll-exit-events
  [run event-types process-state now]
  (when (and (codex.process/exited? process-state)
             (not (codex.process/never-started? process-state))
             (or (not= "launch-failed" (:status process-state))
                 (codex.process/cancelled? process-state))
             (not (contains? event-types events/run-worker-exited)))
    [(codex.events/poll-event-intent run events/run-worker-exited "worker-exited"
                                     {:worker/exit-code (long (or (:exitCode process-state) 0))
                                      :worker/cancelled? (codex.process/cancelled? process-state)}
                                     now)]))
(defn- poll-artifact-events
  [store run task event-types process-state contract now]
  (when (and task
             contract
             (codex.process/successful-exit? process-state)
             (not (codex.process/cancelled? process-state))
             (not (get-in process-state [:helperEvents :artifactReady]))
             (not (helper-transition-in-flight? process-state
                                                :artifactReadyStatus
                                                #{"in-flight" "run-emitted"}
                                                helper-recovery-grace-seconds
                                                now))
             (artifact-contract-ready? process-state contract)
             (or (not (contains? event-types events/run-artifact-ready))
                 (not (contains? event-types events/task-artifact-ready))))
    (maybe-attach-artifact! store run task process-state now)
    (cond-> []
      (not (contains? event-types events/run-artifact-ready))
      (conj (codex.events/poll-event-intent run
                                            events/run-artifact-ready
                                            "artifact-ready"
                                            {:artifact/id (:artifactId process-state)
                                             :artifact/root-path (:artifactRoot process-state)
                                             :artifact/contract-ref (:task/artifact-contract-ref task)}
                                            now))
      (not (contains? event-types events/task-artifact-ready))
      (conj (codex.events/poll-event-intent run
                                            events/task-artifact-ready
                                            "artifact-ready"
                                            {:artifact/id (:artifactId process-state)
                                             :artifact/root-path (:artifactRoot process-state)
                                             :artifact/contract-ref (:task/artifact-contract-ref task)}
                                            now)))))

(defn ensure-launch-supported! [_] nil)
(defn- launch-process!
  [{:keys [repository db-path]} run process-state now]
  (let [runtime-profile (runtime-profile repository (:run/runtime-profile-ref run))
        task (fs/read-edn-file (str (workdir-path run process-state) "/task.edn"))
        home-install {:codex-home/root (codex.home/codex-home-root runtime-profile)}
        command (codex.process/launch-command db-path (:run/id run) runtime-profile)
        process (.start (codex.process/build-process-builder command
                                                             runtime-profile
                                                             home-install
                                                             task
                                                             run))
        process-path (codex.process/process-path-for-run run)]
    (fs/update-json-file! process-path #(codex.process/merge-started-process-state % command now process))))
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
        artifact-contract-now (artifact-contract repository (:task/artifact-contract-ref task))]
    (fs/ensure-directory! workdir)
    (fs/ensure-directory! artifact-root)
    (fs/write-edn-file! (fs/definitions-path run-id) definitions)
    (fs/write-edn-file! (fs/task-path run-id) task)
    (fs/write-edn-file! (fs/run-path run-id) run)
    (fs/write-edn-file! (fs/runtime-profile-path run-id) prepared-profile)
    (fs/write-edn-file! (fs/artifact-contract-path run-id) artifact-contract-now)
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
  [{:keys [repository now store db-path]} task run]
  (let [runtime-profile (runtime-profile repository (:task/runtime-profile-ref task))
        home-install (codex.home/install-home! runtime-profile)
        process-path (fs/process-path (:run/id run))
        process-state (codex.process/base-process-state runtime-profile home-install task run now)
        command (codex.process/launch-command db-path (:run/id run) runtime-profile)]
    (fs/write-json-file! process-path process-state)
    (event-ingest/ingest-run-event! store
                                    (codex.events/runtime-event-intent run
                                                                       events/run-dispatched
                                                                       "dispatched"
                                                                       {:launch/mode "stub-worker"
                                                                        :launch/status :launch.status/pending}
                                                                       now))
    {:runtime-run/dispatch "external-process"
     :runtime-run/process-path (fs/absolute-path process-path)
     :runtime-run/workdir (fs/absolute-path (fs/run-workdir (:run/id run)))
     :runtime-run/artifact-root (fs/absolute-path (fs/artifact-root-path (:task/id task)
                                                                         (:run/id run)))
     :runtime-run/command command
     :runtime-run/launch-mode :launch.mode/stub-worker}))
(defn poll-run!
  [{:keys [store repository db-path]} run now]
  (let [process-path (codex.process/process-path-for-run run)
        process-state (some-> (fs/read-json-file-locked process-path)
                              codex.process/infer-process-state)]
    (if (nil? process-state)
      []
      (if (contains? #{"launch-pending" "launching"} (:status process-state))
        (do
          (when-let [claimed-state (codex.process/claim-launch! process-path
                                                                now helper-recovery-grace-seconds
                                                                fs/read-json-file fs/write-json-file!
                                                                fs/with-file-lock!)]
            (try
              (launch-process! {:repository repository :db-path db-path} run claimed-state now)
              (catch Throwable throwable
                (codex.process/persist-launch-failure! process-path
                                                       claimed-state
                                                       now throwable fs/read-json-file
                                                       fs/write-json-file!
                                                       fs/with-file-lock!))))
          [])
        (let [workdir (workdir-path run process-state)
              task (fs/read-edn-file (str workdir "/task.edn"))
              contract (fs/read-edn-file (str workdir "/artifact-contract.edn"))
              event-types (existing-event-types store (:run/id run))]
          (vec (concat (poll-start-events run event-types process-state now)
                       (poll-never-started-cancel-events run event-types process-state now)
                       (poll-exit-events run event-types process-state now)
                       (poll-artifact-events store run task event-types process-state contract now))))))))
(defn cancel-run!
  [run reason]
  (let [run-id (:run/id run)
        process-path (codex.process/process-path-for-run run)
        now (sql/utc-now)]
    (fs/update-json-file! process-path #(cond-> (assoc % :runId (or (:runId %) run-id)
                                                       :status "cancel-requested"
                                                       :cancelReason (pr-str reason))
                                          (= "launch-pending" (:status %))
                                          (assoc :exitCode 130 :exitedAt now :neverStarted true)))
    {:status :cancel-requested}))
