(ns meta-flow.runtime.codex.execution
  (:require [clojure.java.io :as io]
            [meta-flow.control.events :as events]
            [meta-flow.db :as db]
            [meta-flow.runtime.codex.execution.dispatch :as dispatch]
            [meta-flow.runtime.codex.events :as codex.events]
            [meta-flow.runtime.codex.fs :as fs]
            [meta-flow.runtime.codex.process :as codex.process]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]))
(def ^:private helper-recovery-grace-seconds
  (inc (long (Math/ceil (/ (double (:busy_timeout db/sqlite-pragmas)) 1000.0)))))
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

(defn prepare-run!
  [{:keys [repository]} task run]
  (dispatch/prepare-run! repository task run))

(defn dispatch-run!
  [ctx task run]
  (dispatch/dispatch-run! ctx task run))

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
              (dispatch/launch-process! {:repository repository :db-path db-path}
                                        run
                                        claimed-state
                                        (workdir-path run claimed-state)
                                        now)
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
