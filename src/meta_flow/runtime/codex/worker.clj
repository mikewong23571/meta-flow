(ns meta-flow.runtime.codex.worker
  (:require [cheshire.core :as cheshire]
            [meta-flow.runtime.codex.helper :as codex.helper]
            [meta-flow.runtime.codex.fs :as fs]
            [meta-flow.sql :as sql]
            [meta-flow.store.sqlite :as store.sqlite]))

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
