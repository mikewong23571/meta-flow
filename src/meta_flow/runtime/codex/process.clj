(ns meta-flow.runtime.codex.process
  (:require [clojure.java.io :as io]
            [meta-flow.runtime.codex.fs :as fs]))

(declare infer-process-state cancelled? exited?)

(defn environment
  [runtime-profile]
  (reduce (fn [acc key-name]
            (if-let [value (System/getenv key-name)]
              (assoc acc key-name value)
              acc))
          {}
          (:runtime-profile/env-allowlist runtime-profile)))

(defn process-path-for-run
  [run]
  (or (get-in run [:run/execution-handle :runtime-run/process-path])
      (fs/process-path (:run/id run))))

(defn launch-command
  [db-path run-id runtime-profile]
  ["bb"
   (fs/absolute-path (:runtime-profile/helper-script-path runtime-profile))
   "stub-worker"
   "--db-path" db-path
   "--workdir" (fs/absolute-path (fs/run-workdir run-id))])

(defn repo-root
  []
  (.getCanonicalPath (io/file ".")))

(defn build-process-builder
  [command runtime-profile home-install task run]
  (let [log-file (io/file (fs/run-log-path (:task/id task) (:run/id run)))
        process-builder (doto (ProcessBuilder. command)
                          (.directory (io/file (repo-root)))
                          (.redirectErrorStream true)
                          (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo log-file)))
        env (.environment process-builder)]
    (doto env
      (.clear))
    (doseq [[key-name value] (assoc (environment runtime-profile)
                                    "CODEX_HOME" (:codex-home/root home-install)
                                    "JAVA_HOME" (System/getProperty "java.home"))]
      (.put env key-name value))
    process-builder))

(defn base-process-state
  [runtime-profile home-install task run now]
  {:adapterId (str (:runtime-profile/adapter-id runtime-profile))
   :runId (:run/id run)
   :taskId (:task/id task)
   :status "launch-pending"
   :dispatchMode (str (:runtime-profile/dispatch-mode runtime-profile))
   :launchMode "stub-worker"
   :codexHome (:codex-home/root home-install)
   :workdir (fs/absolute-path (fs/run-workdir (:run/id run)))
   :artifactRoot (fs/absolute-path (fs/artifact-root-path (:task/id task) (:run/id run)))
   :artifactId (str "artifact-" (:run/id run))
   :logPath (fs/absolute-path (fs/run-log-path (:task/id task) (:run/id run)))
   :promptPath (fs/absolute-path (fs/worker-prompt-path (:run/id run)))
   :helperScriptPath (fs/absolute-path (:runtime-profile/helper-script-path runtime-profile))
   :allowedMcpServers (mapv str (:runtime-profile/allowed-mcp-servers runtime-profile))
   :webSearchEnabled (boolean (:runtime-profile/web-search-enabled? runtime-profile))
   :envKeys (vec (keys (environment runtime-profile)))
   :installedTemplates (mapv fs/absolute-path
                             (:codex-home/installed-paths home-install))
   :helperEvents {}
   :createdAt now})

(defn started-process-state
  [process-state command now process]
  (-> process-state
      (assoc :status "dispatched"
             :command command
             :pid (.pid process)
             :dispatchedAt now)
      (dissoc :launchClaimedAt :launchClaimToken)))

(defn merge-started-process-state
  [current-state command now process]
  (-> current-state
      (assoc :status (if (cancelled? current-state) "cancel-requested" "dispatched")
             :command command
             :pid (.pid process)
             :dispatchedAt now)
      (dissoc :launchClaimedAt :launchClaimToken :neverStarted)))

(defn terminated-process-state
  [process-state now]
  (cond-> process-state
    (contains? #{"prepared" "dispatched" "running" "cancel-requested"} (:status process-state))
    (assoc :status "exited"
           :exitCode (long (or (:exitCode process-state)
                               (when (= "cancel-requested" (:status process-state)) 130)
                               1)))
    (nil? (:exitedAt process-state))
    (assoc :exitedAt now)))

(defn- start-recorded?
  [process-state]
  (boolean (or (:pid process-state)
               (:dispatchedAt process-state)
               (:startedAt process-state)
               (:lastHeartbeatAt process-state)
               (:artifactReadyAt process-state))))

(defn never-started?
  [process-state]
  (and (not (start-recorded? process-state))
       (or (:neverStarted process-state)
           (contains? #{"launch-pending" "launching" "launch-failed"}
                      (:status process-state)))))

(defn- stale-launch-claim?
  [process-state now grace-seconds]
  (and (= "launching" (:status process-state))
       (never-started? process-state)
       (let [claimed-at (some-> (:launchClaimedAt process-state) java.time.Instant/parse)]
         (or (nil? claimed-at)
             (not (.isAfter (.plusSeconds claimed-at grace-seconds)
                            (java.time.Instant/parse now)))))))

(defn claim-launch-state
  [process-state now claim-token grace-seconds]
  (when (or (= "launch-pending" (:status process-state))
            (stale-launch-claim? process-state now grace-seconds))
    (assoc process-state
           :status "launching"
           :launchClaimedAt now
           :launchClaimToken claim-token)))

(defn launch-failed-process-state
  [process-state now throwable]
  (cond-> (assoc process-state
                 :status "launch-failed"
                 :exitCode 1
                 :launchAttemptedAt now
                 :launchFailedAt now
                 :launchError {:message (.getMessage throwable)
                               :class (.getName (class throwable))})
    (never-started? process-state)
    (assoc :neverStarted true)
    :always
    (dissoc :launchClaimedAt :launchClaimToken)))

(defn persist-launch-failure!
  [process-path process-state now throwable read-process-state! write-process-state! with-lock!]
  (with-lock! process-path
    (fn []
      (let [current-state (or (read-process-state! process-path) process-state)
            failed-state (launch-failed-process-state current-state now throwable)]
        (write-process-state! process-path failed-state)
        failed-state))))

(defn claim-launch!
  [process-path now grace-seconds read-process-state! write-process-state! with-lock!]
  (let [claim-token (str "launch-claim:" (java.util.UUID/randomUUID))]
    (with-lock! process-path
      (fn []
        (when-let [process-state (some-> (read-process-state! process-path)
                                         infer-process-state)]
          (when-let [claimed-state (claim-launch-state process-state
                                                       now
                                                       claim-token
                                                       grace-seconds)]
            (write-process-state! process-path claimed-state)
            claimed-state))))))

(defn cancelled?
  [process-state]
  (boolean (or (= "cancel-requested" (:status process-state))
               (:cancelReason process-state)
               (:cancelled process-state))))

(defn launched?
  [process-state]
  (boolean (or (:pid process-state)
               (:startedAt process-state)
               (:lastHeartbeatAt process-state)
               (:exitedAt process-state)
               (:artifactReadyAt process-state)
               (contains? process-state :exitCode))))

(defn started?
  [process-state]
  (and (not (:neverStarted process-state))
       (launched? process-state)
       (contains? #{"dispatched" "running" "exited" "completed" "cancel-requested"}
                  (:status process-state))))

(defn successful-exit?
  [process-state]
  (and (exited? process-state)
       (zero? (long (or (:exitCode process-state) 0)))))

(defn exited?
  [process-state]
  (and (launched? process-state)
       (or (contains? process-state :exitCode)
           (contains? #{"exited" "completed"}
                      (:status process-state)))))

(defn infer-process-state
  [process-state]
  (if-let [pid (:pid process-state)]
    (if-let [handle (java.lang.ProcessHandle/of (long pid))]
      (if (.isPresent handle)
        (if (.isAlive (.get handle))
          process-state
          (terminated-process-state process-state nil))
        (terminated-process-state process-state nil))
      process-state)
    process-state))
