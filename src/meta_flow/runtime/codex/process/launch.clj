(ns meta-flow.runtime.codex.process.launch
  (:require [clojure.java.io :as io]
            [meta-flow.runtime.codex.fs :as fs]
            [meta-flow.runtime.codex.launch.support :as launch.support]))

(def env-value
  launch.support/env-value)

(def smoke-enabled?
  launch.support/smoke-enabled?)

(def launch-mode
  launch.support/launch-mode)

(def provider-env-keys
  launch.support/provider-env-keys)

(def codex-command-available?
  launch.support/codex-command-available?)

(def launch-support
  launch.support/launch-support)

(def ensure-launch-supported!
  launch.support/ensure-launch-supported!)

(defn normalize-launch-mode
  [value]
  (case value
    :launch.mode/codex-exec :launch.mode/codex-exec
    "codex-exec" :launch.mode/codex-exec
    :launch.mode/stub-worker :launch.mode/stub-worker
    "stub-worker" :launch.mode/stub-worker
    nil))

(defn persisted-launch-mode
  [process-state runtime-profile]
  (or (normalize-launch-mode (:launchMode process-state))
      (launch-mode runtime-profile)))

(defn environment
  [runtime-profile]
  (reduce (fn [acc key-name]
            (if-let [value (env-value key-name)]
              (assoc acc key-name value)
              acc))
          {}
          (:runtime-profile/env-allowlist runtime-profile)))

(defn process-path-for-run
  [run]
  (or (get-in run [:run/execution-handle :runtime-run/process-path])
      (fs/process-path (:run/id run))))

(defn launch-command
  ([db-path run-id runtime-profile]
   (launch-command db-path run-id runtime-profile (launch-mode runtime-profile)))
  ([db-path run-id runtime-profile selected-launch-mode]
   (let [helper-script (fs/absolute-path (:runtime-profile/helper-script-path runtime-profile))
         workdir (fs/absolute-path (fs/run-workdir run-id))
         launch-mode-now (or (normalize-launch-mode selected-launch-mode)
                             (launch-mode runtime-profile))]
     (case launch-mode-now
       :launch.mode/codex-exec
       ["bb"
        helper-script
        "codex-worker"
        "--db-path" db-path
        "--workdir" workdir]
       ["bb"
        helper-script
        "stub-worker"
        "--db-path" db-path
        "--workdir" workdir]))))

(defn codex-exec-command
  [workdir runtime-profile]
  (into ["codex"
         "exec"
         "--dangerously-bypass-approvals-and-sandbox"
         "--skip-git-repo-check"
         "-C" workdir]
        (cond-> []
          (:runtime-profile/web-search-enabled? runtime-profile)
          (conj "--search")
          :always
          (conj "-"))))

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
  ([runtime-profile home-install task run now]
   (base-process-state runtime-profile
                       home-install
                       task
                       run
                       now
                       (launch-mode runtime-profile)))
  ([runtime-profile home-install task run now selected-launch-mode]
   {:adapterId (str (:runtime-profile/adapter-id runtime-profile))
    :runId (:run/id run)
    :taskId (:task/id task)
    :status "launch-pending"
    :dispatchMode (str (:runtime-profile/dispatch-mode runtime-profile))
    :launchMode (name (or (normalize-launch-mode selected-launch-mode)
                          (launch-mode runtime-profile)))
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
    :createdAt now}))
