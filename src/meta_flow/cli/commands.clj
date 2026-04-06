(ns meta-flow.cli.commands
  (:require [clojure.string :as str]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.codex :as runtime.codex]
            [meta-flow.runtime.codex.home :as codex.home]
            [meta-flow.runtime.codex.process.launch :as codex.launch]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.ui.http :as ui.http]))

(defn ensure-system-ready!
  []
  (let [repository (defs.loader/filesystem-definition-repository)]
    (defs.protocol/load-workflow-defs repository)
    (db/initialize-database!)
    (db/ensure-runtime-directories!)
    repository))

(defn option-value
  [args option-name]
  (let [indexed (map-indexed vector args)]
    (some (fn [[idx value]]
            (when (= value option-name)
              (nth args (inc idx) nil)))
          indexed)))

(defn require-option!
  [args option-name]
  (or (option-value args option-name)
      (throw (ex-info (str "Missing required option " option-name)
                      {:args args
                       :option option-name}))))

(defn run-init!
  []
  (let [repository (defs.loader/filesystem-definition-repository)
        _ (defs.protocol/load-workflow-defs repository)
        {:keys [db-path pragmas]} (db/initialize-database!)
        runtime-dirs (db/ensure-runtime-directories!)]
    (println (str "Initialized database at " db-path))
    (println "Loaded workflow definitions from resources/meta_flow/defs with defs/ overlay support")
    (println (str "Ensured runtime directories: " (str/join ", " runtime-dirs)))
    (println (str "SQLite pragmas applied: journal_mode="
                  (:journal_mode pragmas)
                  ", busy_timeout="
                  (:busy_timeout pragmas)))))

(defn run-defs-init-overlay!
  []
  (let [{:keys [overlay-root draft-root created-files]} (defs.loader/init-overlay!)]
    (println (str "Initialized definitions overlay at " overlay-root))
    (println (str "Drafts directory: " draft-root))
    (println (str "Active definition files ready: " (count created-files)))))

(defn run-defs-validate!
  []
  (let [repository (defs.loader/filesystem-definition-repository)
        definitions (defs.protocol/load-workflow-defs repository)
        summary (defs.loader/definitions-summary definitions)]
    (println "Definitions valid")
    (println (str "Task types: " (:task-types summary)))
    (println (str "Task FSMs: " (:task-fsms summary)))
    (println (str "Run FSMs: " (:run-fsms summary)))
    (println (str "Runtime profiles: " (:runtime-profiles summary)))))

(defn run-runtime-init-codex-home!
  []
  (let [repository (defs.loader/filesystem-definition-repository)
        definitions (defs.protocol/load-workflow-defs repository)
        _ (db/ensure-runtime-directories!)
        codex-profiles (filter #(= :runtime.adapter/codex (:runtime-profile/adapter-id %))
                               (:runtime-profiles definitions))]
    (when (empty? codex-profiles)
      (throw (ex-info "No Codex runtime profiles found in definitions" {})))
    (doseq [profile codex-profiles]
      (let [{:keys [codex-home/root codex-home/installed-paths codex-home/skipped-paths
                    codex-home/skills-installed codex-home/skills-skipped codex-home/skills-not-found]}
            (codex.home/install-home! profile)]
        (println (str "Profile " (:runtime-profile/id profile) ":"))
        (println (str "  CODEX_HOME: " root))
        (println (str "  Templates installed: " (count installed-paths)
                      ", preserved: " (count skipped-paths)))
        (when (seq skills-installed)
          (println (str "  Skills installed: " (str/join ", " skills-installed))))
        (when (seq skills-skipped)
          (println (str "  Skills preserved: " (str/join ", " skills-skipped))))
        (when (seq skills-not-found)
          (println (str "  Skills not found in ~/.codex/skills (run `npx skills add`): "
                        (str/join ", " skills-not-found))))))))

(defn run-ui-serve!
  [args]
  (let [port-text (option-value args "--port")
        db-path (or (option-value args "--db-path") db/default-db-path)
        port (if port-text
               (Integer/parseInt port-text)
               8788)
        server (ui.http/start-server! {:db-path db-path
                                       :port port})
        stop-server! #(ui.http/stop-server! server)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                       (reify Runnable
                         (run [_]
                           (stop-server!)))))
    (println (str "Meta-Flow UI API listening on http://localhost:" (:port server)))
    (println (str "Scheduler overview: http://localhost:" (:port server) "/api/scheduler/overview"))
    (println (str "Using SQLite DB " db-path))
    (ui.http/block-forever!)))

(defn run-scheduler-once!
  []
  (ensure-system-ready!)
  (let [{:keys [created-runs requeued-task-ids escalated-task-ids expired-lease-run-ids
                heartbeat-timeout-run-ids dispatch-block-reason capacity-skipped-task-ids
                task-errors now snapshot]}
        (scheduler/run-scheduler-step db/default-db-path)]
    (println (str "Scheduler step completed at " now))
    (println (str "Created runs: " (count created-runs)))
    (println (str "Requeued tasks: " (count requeued-task-ids)))
    (println (str "Escalated tasks: " (count escalated-task-ids)))
    (println (str "Recovered expired leases: " (count expired-lease-run-ids)))
    (println (str "Recovered heartbeat timeouts: " (count heartbeat-timeout-run-ids)))
    (println (str "Dispatch blocked: " (boolean dispatch-block-reason)))
    (when dispatch-block-reason
      (println (str "Dispatch block reason: " dispatch-block-reason)))
    (println (str "Capacity skipped tasks: " (count capacity-skipped-task-ids)))
    (println (str "Dispatch errors: " (count task-errors)))
    (println (str "Runnable tasks before step: " (:snapshot/runnable-count snapshot)))
    (println (str "Retryable failures before step: " (:snapshot/retryable-failed-count snapshot)))
    (println (str "Awaiting validation before step: " (:snapshot/awaiting-validation-count snapshot)))
    (println (str "Expired leases before step: " (:snapshot/expired-lease-count snapshot)))
    (println (str "Heartbeat timeouts before step: " (:snapshot/heartbeat-timeout-count snapshot)))
    (println (str "Dispatch cooldown active before step: " (:snapshot/dispatch-cooldown-active? snapshot)))
    (when-let [cooldown-until (:snapshot/dispatch-cooldown-until snapshot)]
      (println (str "Dispatch cooldown until: " cooldown-until)))
    (doseq [task-error task-errors]
      (println (str "Task " (:task/id task-error) " failed: " (:error/message task-error))))))

(defn run-enqueue!
  [args]
  (ensure-system-ready!)
  (let [work-key (option-value args "--work-key")
        {:keys [task reused?]} (scheduler/enqueue-demo-task! db/default-db-path
                                                             (cond-> {}
                                                               work-key (assoc :work-key work-key)))]
    (println (str "Enqueued task " (:task/id task) " for " (:task/work-key task)))
    (when reused?
      (println "Task already existed for that work key; reused persisted control-plane state"))
    (println (str "Task " (:task/id task) " -> " (:task/state task)))
    (println (str "Runtime profile " (get-in task [:task/runtime-profile-ref :definition/id])
                  " v" (get-in task [:task/runtime-profile-ref :definition/version])))))

(defn run-enqueue-repo-arch!
  [args]
  (ensure-system-ready!)
  (let [repo-url (require-option! args "--repo")
        notify-email (require-option! args "--notify-email")
        {:keys [task reused?]} (scheduler/enqueue-repo-arch-task! db/default-db-path
                                                                  {:repo-url repo-url
                                                                   :notify-email notify-email})]
    (println (str "Enqueued repo-arch task " (:task/id task) " for " repo-url))
    (when reused?
      (println "Task already existed for that repo and recipient; reused persisted control-plane state"))
    (println (str "Notify email: " notify-email))
    (println (str "Task " (:task/id task) " -> " (:task/state task)))
    (println (str "Runtime profile " (get-in task [:task/runtime-profile-ref :definition/id])
                  " v" (get-in task [:task/runtime-profile-ref :definition/version])))))

(defn run-demo-happy-path!
  []
  (ensure-system-ready!)
  (let [{:keys [task run artifact-root scheduler-steps]} (scheduler/demo-happy-path! db/default-db-path)]
    (println (str "Enqueued task " (:task/id task) " for " (:task/work-key task)))
    (println (str "Created run " (:run/id run) " attempt " (:run/attempt run)))
    (println (str "Mock worker produced artifact " artifact-root))
    (println "Assessment accepted")
    (println (str "Task " (:task/id task) " -> " (:task/state task)))
    (println (str "Run " (:run/id run) " -> " (:run/state run)))
    (println (str "Scheduler steps: " scheduler-steps))))

(defn run-demo-retry-path!
  []
  (ensure-system-ready!)
  (let [{:keys [task run artifact-root assessment disposition scheduler-steps]}
        (scheduler/demo-retry-path! db/default-db-path)]
    (println (str "Enqueued task " (:task/id task) " for " (:task/work-key task)))
    (println (str "Created run " (:run/id run) " attempt " (:run/attempt run)))
    (println (str "Mock worker produced artifact " artifact-root))
    (println (str "Assessment rejected: " (:assessment/notes assessment)))
    (println (str "Disposition " (:disposition/action disposition)))
    (println (str "Task " (:task/id task) " -> " (:task/state task)))
    (println (str "Run " (:run/id run) " -> " (:run/state run)))
    (println (str "Scheduler steps: " scheduler-steps))))

(defn run-demo-codex-smoke!
  []
  (let [repository (ensure-system-ready!)]
    (when-not (codex.launch/smoke-enabled?)
      (throw (ex-info "Codex smoke test cannot start: set META_FLOW_ENABLE_CODEX_SMOKE=1"
                      {:env/name "META_FLOW_ENABLE_CODEX_SMOKE"})))
    (let [runtime-profile (or (defs.protocol/find-runtime-profile repository
                                                                  :runtime-profile/codex-worker
                                                                  1)
                              (throw (ex-info "Codex runtime profile not found"
                                              {:runtime-profile/id :runtime-profile/codex-worker
                                               :runtime-profile/version 1})))
          _ (runtime.codex/ensure-launch-supported! runtime-profile)
          {:keys [task run artifact-root scheduler-steps]} (scheduler/demo-codex-smoke! db/default-db-path)]
      (println (str "Enqueued task " (:task/id task) " for " (:task/work-key task)))
      (println (str "Created run " (:run/id run) " attempt " (:run/attempt run)))
      (println "Dispatched codex worker with :runtime-profile/codex-worker")
      (println (str "Codex worker produced artifact " artifact-root))
      (println "Assessment accepted")
      (println (str "Task " (:task/id task) " -> " (:task/state task)))
      (println (str "Run " (:run/id run) " -> " (:run/state run)))
      (println (str "Scheduler steps: " scheduler-steps)))))
