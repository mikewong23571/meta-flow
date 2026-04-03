(ns meta-flow.runtime.codex.helper
  (:require [clojure.edn :as edn]
            [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.runtime.codex.events :as codex.events]
            [meta-flow.runtime.codex.fs :as fs]
            [meta-flow.runtime.codex.process.state :as process.state]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]))

(def ^:private max-busy-retries
  5)

(def ^:private busy-retry-sleep-ms
  25)

(defn- retryable-write-exception?
  [throwable]
  (sql/retryable-write-exception? throwable))

(defn- with-busy-retry
  [f]
  (loop [attempt 1]
    (let [result (try
                   {:status :ok
                    :value (f)}
                   (catch java.sql.SQLException ex
                     {:status :error
                      :throwable ex}))]
      (cond
        (= :ok (:status result))
        (:value result)

        (and (< attempt max-busy-retries)
             (retryable-write-exception? (:throwable result)))
        (do
          (Thread/sleep busy-retry-sleep-ms)
          (recur (inc attempt)))

        :else
        (throw (:throwable result))))))

(defn workdir-context
  [workdir]
  (let [run (fs/read-edn-file (str workdir "/run.edn"))
        task (fs/read-edn-file (str workdir "/task.edn"))
        runtime-profile (fs/read-edn-file (str workdir "/runtime-profile.edn"))
        artifact-contract (fs/read-edn-file (str workdir "/artifact-contract.edn"))
        process-state (or (fs/read-json-file (str workdir "/process.json")) {})]
    {:workdir workdir
     :run run
     :task task
     :runtime-profile runtime-profile
     :artifact-contract artifact-contract
     :process-state process-state}))

(defn update-process-state!
  [workdir f]
  (let [path (str workdir "/process.json")
        preserve-cancellation (fn [current updated]
                                ;; Helper callbacks share the same file lock as cancel-run!,
                                ;; so this merge only needs to preserve an already-persisted cancel marker.
                                (cond-> updated
                                  (process.state/cancelled? current)
                                  (assoc :cancelReason (or (:cancelReason updated)
                                                           (:cancelReason current))
                                         :cancelled true)
                                  (and (= "cancel-requested" (:status current))
                                       (not= "exited" (:status updated)))
                                  (assoc :status "cancel-requested")))]
    (fs/update-json-file! path
                          (fn [current]
                            (->> (f current)
                                 (preserve-cancellation current))))))

(defn artifact-id
  [{:keys [run process-state]} options]
  (or (:artifact-id options)
      (:artifactId process-state)
      (str "artifact-" (:run/id run))))

(defn artifact-root
  [{:keys [task run process-state]} options]
  (or (:artifact-root options)
      (:artifactRoot process-state)
      (fs/absolute-path (fs/artifact-root-path (:task/id task) (:run/id run)))))

(defn- existing-event-types
  [store run-id]
  (->> (store.protocol/list-run-events store run-id)
       (map :event/type)
       set))

(defn- ensure-artifact-attached!
  [store {:keys [run task]} artifact-id-now artifact-root-now now-value]
  (store.protocol/attach-artifact! store
                                   (:run/id run)
                                   {:artifact/id artifact-id-now
                                    :artifact/run-id (:run/id run)
                                    :artifact/task-id (:task/id task)
                                    :artifact/contract-ref (:task/artifact-contract-ref task)
                                    :artifact/root-path artifact-root-now
                                    :artifact/created-at now-value}))

(defn- artifact-ready-payload
  [task artifact-id-now artifact-root-now]
  {:artifact/id artifact-id-now
   :artifact/root-path artifact-root-now
   :artifact/contract-ref (:task/artifact-contract-ref task)})

(defn- heartbeat-payload
  [options]
  (cond-> {}
    (:status options) (assoc :worker/status (edn/read-string (:status options)))
    (:stage options) (assoc :worker/stage (edn/read-string (:stage options)))
    (:message options) (assoc :worker/message (:message options))))

(defn emit-worker-started!
  [store {:keys [run workdir]} options]
  (let [token (:token options)
        now-value (or (:at options) (sql/utc-now))
        event-types (existing-event-types store (:run/id run))]
    (update-process-state! workdir
                           #(assoc % :workerStartedStatus {:state "in-flight"
                                                           :updated-at now-value}))
    (when-not (contains? event-types events/task-worker-started)
      (event-ingest/ingest-run-event! store
                                      (codex.events/helper-event-intent run
                                                                        events/task-worker-started
                                                                        token
                                                                        {}
                                                                        now-value)))
    (when-not (contains? event-types events/run-worker-started)
      (event-ingest/ingest-run-event! store
                                      (codex.events/helper-event-intent run
                                                                        events/run-worker-started
                                                                        token
                                                                        {}
                                                                        now-value)))
    (update-process-state! workdir
                           #(-> %
                                (assoc :status "running"
                                       :startedAt now-value
                                       :workerStartedStatus {:state "completed"
                                                             :updated-at now-value})
                                (assoc-in [:helperEvents :workerStarted] true)))))

(defn emit-heartbeat!
  [store {:keys [run workdir]} options]
  (let [token (:token options)
        now-value (or (:at options) (sql/utc-now))]
    (event-ingest/ingest-run-event! store
                                    (codex.events/helper-event-intent run
                                                                      events/run-worker-heartbeat
                                                                      token
                                                                      (heartbeat-payload options)
                                                                      now-value))
    (update-process-state! workdir
                           #(-> %
                                (assoc :status "running"
                                       :lastHeartbeatAt now-value)
                                (update :heartbeatCount (fnil inc 0))))))

(defn emit-worker-exit!
  [store {:keys [run workdir]} options]
  (let [token (:token options)
        now-value (or (:at options) (sql/utc-now))
        exit-code (long (or (:exit-code options) 0))
        cancelled? (boolean (:cancelled options))
        event-types (existing-event-types store (:run/id run))]
    (when-not (contains? event-types events/run-worker-exited)
      (event-ingest/ingest-run-event! store
                                      (codex.events/helper-event-intent run
                                                                        events/run-worker-exited
                                                                        token
                                                                        {:worker/exit-code exit-code
                                                                         :worker/cancelled? cancelled?}
                                                                        now-value)))
    (update-process-state! workdir
                           #(cond-> (assoc % :status "exited"
                                           :exitCode exit-code
                                           :exitedAt now-value)
                              cancelled? (assoc :cancelled true)
                              true (assoc-in [:helperEvents :workerExited] true)))))

(defn emit-artifact-ready!
  [store {:keys [run workdir] :as ctx} options]
  (let [token (:token options)
        now-value (or (:at options) (sql/utc-now))
        artifact-id-now (artifact-id ctx options)
        artifact-root-now (artifact-root ctx options)
        event-types (existing-event-types store (:run/id run))
        process-path (str workdir "/process.json")
        payload (artifact-ready-payload (:task ctx) artifact-id-now artifact-root-now)]
    (fs/with-file-lock! process-path
      (fn []
        (let [current (or (fs/read-json-file process-path) {})]
          (when-not (or (process.state/cancelled? current)
                        (get-in current [:helperEvents :artifactReady]))
            (let [in-flight (-> current
                                (assoc :artifactId artifact-id-now
                                       :artifactRoot artifact-root-now)
                                (assoc :artifactReadyStatus {:state "in-flight"
                                                             :updated-at now-value}))]
              (fs/write-json-file! process-path in-flight)
              (with-busy-retry
                #(ensure-artifact-attached! store ctx artifact-id-now artifact-root-now now-value))
              (when-not (contains? event-types events/run-artifact-ready)
                (with-busy-retry
                  #(event-ingest/ingest-run-event! store
                                                   (codex.events/helper-event-intent run
                                                                                     events/run-artifact-ready
                                                                                     token
                                                                                     payload
                                                                                     now-value))))
              (let [run-emitted (assoc in-flight :artifactReadyStatus {:state "run-emitted"
                                                                       :updated-at now-value})]
                (fs/write-json-file! process-path run-emitted)
                (when-not (contains? event-types events/task-artifact-ready)
                  (with-busy-retry
                    #(event-ingest/ingest-run-event! store
                                                     (codex.events/helper-event-intent run
                                                                                       events/task-artifact-ready
                                                                                       token
                                                                                       payload
                                                                                       now-value))))
                (fs/write-json-file! process-path
                                     (-> run-emitted
                                         (assoc :status "completed"
                                                :artifactReadyAt now-value
                                                :artifactReadyStatus {:state "completed"
                                                                      :updated-at now-value})
                                         (assoc-in [:helperEvents :artifactReady] true)))))))))))
