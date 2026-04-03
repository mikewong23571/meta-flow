(ns meta-flow.runtime.codex.worker
  (:require [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [meta-flow.runtime.codex.helper :as codex.helper]
            [meta-flow.runtime.codex.fs :as fs]
            [meta-flow.runtime.codex.process :as codex.process]
            [meta-flow.sql :as sql]
            [meta-flow.store.sqlite :as store.sqlite]))

(declare cancelled?)

(def ^:private wait-poll-ms
  1000)

(defn- current-time-ms
  []
  (System/currentTimeMillis))

(defn- required-artifact-paths-present?
  [artifact-root-now artifact-contract]
  (every? #(.exists (io/file artifact-root-now %))
          (:artifact-contract/required-paths artifact-contract)))

(defn- codex-exec-input
  [{:keys [task run artifact-contract]} artifact-root-now]
  (str/join
   "\n"
   [(slurp (fs/worker-prompt-path (:run/id run)))
    ""
    "## Codex Smoke Task"
    "Use shell commands only. Do not call the helper script directly; the runtime wrapper owns control-plane callbacks."
    (str "Write the required artifact files under `" artifact-root-now "`:")
    (str "  " (str/join ", " (:artifact-contract/required-paths artifact-contract)))
    ""
    "Required contents:"
    "- `manifest.json`: valid JSON with `task/id`, `run/id`, and `status` = `completed`."
    (str "- `notes.md`: a short note mentioning task `" (:task/id task) "` and run `" (:run/id run) "`.")
    "- `run.log`: append a few plain-text lines describing what you created."
    ""
    "Stop after the files are written."]))

(defn- heartbeat-interval-ms
  [runtime-profile]
  (* 1000 (long (or (:runtime-profile/heartbeat-interval-seconds runtime-profile)
                    30))))

(defn- start-codex-process!
  [workdir runtime-profile input]
  (let [builder (doto (ProcessBuilder. (codex.process/codex-exec-command workdir runtime-profile))
                  (.directory (io/file workdir))
                  (.redirectOutput java.lang.ProcessBuilder$Redirect/INHERIT)
                  (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT))
        proc (.start builder)]
    (with-open [writer (io/writer (.getOutputStream proc))]
      (.write writer input))
    proc))

(defn- record-codex-child-process!
  [workdir runtime-profile ^Process proc]
  (let [child-pid (.pid proc)
        child-command (codex.process/codex-exec-command workdir runtime-profile)]
    (codex.helper/update-process-state! workdir
                                        (fn [current]
                                          (let [wrapper-pid (:pid current)
                                                wrapper-command (:command current)]
                                            (cond-> (assoc current
                                                           :pid child-pid
                                                           :command child-command)
                                              (and (number? wrapper-pid)
                                                   (not= (long wrapper-pid) child-pid))
                                              (assoc :wrapperPid wrapper-pid)

                                              (and (vector? wrapper-command)
                                                   (not= wrapper-command child-command))
                                              (assoc :wrapperCommand wrapper-command)))))))

(defn- stop-process!
  [^Process proc]
  (.destroy proc)
  (when-not (.waitFor proc 2000 java.util.concurrent.TimeUnit/MILLISECONDS)
    (.destroyForcibly proc)
    (.waitFor proc 2000 java.util.concurrent.TimeUnit/MILLISECONDS)))

(defn- emit-codex-wait-heartbeat!
  [store ctx heartbeat-index]
  (codex.helper/emit-heartbeat! store
                                ctx
                                {:token (str "heartbeat-codex-wait-" heartbeat-index)
                                 :at (sql/utc-now)
                                 :status ":worker.status/running"
                                 :stage ":worker.stage/synthesizing"
                                 :message "Waiting for codex exec to finish"}))

(defn run-codex-worker!
  [{:keys [workdir runtime-profile artifact-contract] :as ctx} db-path artifact-root-now artifact-id-now]
  (let [store (store.sqlite/sqlite-state-store db-path)
        started-at (sql/utc-now)
        heartbeat-ms (heartbeat-interval-ms runtime-profile)]
    (fs/ensure-directory! artifact-root-now)
    (codex.helper/emit-worker-started! store ctx {:token "worker-started"
                                                  :at started-at})
    (codex.helper/emit-heartbeat! store
                                  ctx
                                  {:token "heartbeat-codex-start"
                                   :at started-at
                                   :status ":worker.status/running"
                                   :stage ":worker.stage/research"
                                   :message "Launching real codex exec worker"})
    (let [proc (start-codex-process! workdir
                                     runtime-profile
                                     (codex-exec-input ctx artifact-root-now))]
      (record-codex-child-process! workdir runtime-profile proc)
      (loop [heartbeat-index 1
             last-heartbeat-ms (current-time-ms)]
        (cond
          (.waitFor proc wait-poll-ms java.util.concurrent.TimeUnit/MILLISECONDS)
          (let [cancelled-now? (cancelled? workdir)
                exit-code (if cancelled-now?
                            130
                            (long (.exitValue proc)))
                exited-at (sql/utc-now)]
            (codex.helper/emit-worker-exit! store
                                            ctx
                                            {:token (cond
                                                      cancelled-now? "worker-cancelled"
                                                      (zero? exit-code) "worker-exited"
                                                      :else "worker-failed")
                                             :exit-code exit-code
                                             :cancelled cancelled-now?
                                             :at exited-at})
            (when (and (zero? exit-code)
                       (not cancelled-now?)
                       (required-artifact-paths-present? artifact-root-now artifact-contract))
              (codex.helper/emit-artifact-ready! store
                                                 ctx
                                                 {:token "artifact-ready"
                                                  :artifact-id artifact-id-now
                                                  :artifact-root artifact-root-now
                                                  :at (sql/utc-now)})))

          (cancelled? workdir)
          (do
            (stop-process! proc)
            (codex.helper/emit-worker-exit! store
                                            ctx
                                            {:token "worker-cancelled"
                                             :exit-code 130
                                             :cancelled true
                                             :at (sql/utc-now)}))

          (>= (- (current-time-ms) last-heartbeat-ms) heartbeat-ms)
          (do
            (emit-codex-wait-heartbeat! store ctx heartbeat-index)
            (recur (inc heartbeat-index) (current-time-ms)))

          :else
          (recur heartbeat-index last-heartbeat-ms))))))

(defn write-stub-artifact!
  [{:keys [task run artifact-contract]} artifact-root-now]
  (doseq [relative-path (:artifact-contract/required-paths artifact-contract)]
    (let [path (str artifact-root-now "/" relative-path)]
      (case relative-path
        "manifest.json"
        (fs/write-text-file! path (cheshire/generate-string {:task/id (:task/id task)
                                                             :run/id (:run/id run)
                                                             :status "completed"
                                                             :generated/at (sql/utc-now)}
                                                            {:pretty true}))
        "notes.md"
        (fs/write-text-file! path (str "Codex-managed run " (:run/id run) " completed.\n"))
        "run.log"
        (fs/append-text-file! path (str "stub codex worker completed run " (:run/id run) "\n"))
        (fs/write-text-file! path "")))))

(defn cancelled?
  [workdir]
  (= "cancel-requested"
     (:status (or (fs/read-json-file (str workdir "/process.json")) {}))))

(defn run-stub-worker!
  [ctx db-path artifact-root-now artifact-id-now]
  (let [{:keys [workdir]} ctx
        store (store.sqlite/sqlite-state-store db-path)]
    (fs/ensure-directory! artifact-root-now)
    (when-not (cancelled? workdir)
      (codex.helper/emit-worker-started! store ctx {:token "worker-started"}))
    (Thread/sleep 50)
    (when-not (cancelled? workdir)
      (codex.helper/emit-heartbeat! store
                                    ctx
                                    {:token "heartbeat-1"
                                     :status ":worker.status/running"
                                     :stage ":worker.stage/research"}))
    (Thread/sleep 50)
    (when-not (cancelled? workdir)
      (codex.helper/emit-heartbeat! store
                                    ctx
                                    {:token "progress-1"
                                     :status ":worker.status/running"
                                     :stage ":worker.stage/synthesizing"
                                     :message "Writing managed artifact bundle"}))
    (Thread/sleep 50)
    (write-stub-artifact! ctx artifact-root-now)
    (if (cancelled? workdir)
      (codex.helper/emit-worker-exit! store
                                      ctx
                                      {:token "worker-cancelled"
                                       :exit-code 130
                                       :cancelled true})
      (do
        (codex.helper/emit-worker-exit! store
                                        ctx
                                        {:token "worker-exited"
                                         :exit-code 0})
        (codex.helper/emit-artifact-ready! store
                                           ctx
                                           {:token "artifact-ready"
                                            :artifact-id artifact-id-now
                                            :artifact-root artifact-root-now})))))
