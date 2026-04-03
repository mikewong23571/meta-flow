(ns meta-flow.cli
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.codex.home :as codex.home]
            [meta-flow.scheduler :as scheduler]))

(def usage-text
  (str/join
   \newline
   ["Usage:"
    "  clojure -M -m meta-flow.main init"
    "  clojure -M -m meta-flow.main defs validate"
    "  clojure -M -m meta-flow.main runtime init-codex-home"
    "  clojure -M -m meta-flow.main enqueue [--work-key <work-key>]"
    "  clojure -M -m meta-flow.main scheduler once"
    "  clojure -M -m meta-flow.main demo happy-path"
    "  clojure -M -m meta-flow.main demo retry-path"
    "  clojure -M -m meta-flow.main inspect task --task-id <task-id>"
    "  clojure -M -m meta-flow.main inspect run --run-id <run-id>"
    "  clojure -M -m meta-flow.main inspect collection"]))

(defn- ensure-system-ready!
  []
  (let [repository (defs.loader/filesystem-definition-repository)]
    (defs.protocol/load-workflow-defs repository)
    (db/initialize-database!)
    (db/ensure-runtime-directories!)
    repository))

(defn- option-value
  [args option-name]
  (let [indexed (map-indexed vector args)]
    (some (fn [[idx value]]
            (when (= value option-name)
              (nth args (inc idx) nil)))
          indexed)))

(defn- require-option!
  [args option-name]
  (or (option-value args option-name)
      (throw (ex-info (str "Missing required option " option-name)
                      {:args args
                       :option option-name}))))

(defn- run-init!
  []
  (let [repository (defs.loader/filesystem-definition-repository)
        _ (defs.protocol/load-workflow-defs repository)
        {:keys [db-path pragmas]} (db/initialize-database!)
        runtime-dirs (db/ensure-runtime-directories!)]
    (println (str "Initialized database at " db-path))
    (println "Loaded workflow definitions from resources/meta_flow/defs")
    (println (str "Ensured runtime directories: " (str/join ", " runtime-dirs)))
    (println (str "SQLite pragmas applied: journal_mode="
                  (:journal_mode pragmas)
                  ", busy_timeout="
                  (:busy_timeout pragmas)))))

(defn- run-defs-validate!
  []
  (let [repository (defs.loader/filesystem-definition-repository)
        definitions (defs.protocol/load-workflow-defs repository)
        summary (defs.loader/definitions-summary definitions)]
    (println "Definitions valid")
    (println (str "Task types: " (:task-types summary)))
    (println (str "Task FSMs: " (:task-fsms summary)))
    (println (str "Run FSMs: " (:run-fsms summary)))
    (println (str "Runtime profiles: " (:runtime-profiles summary)))))

(defn- run-runtime-init-codex-home!
  []
  (let [repository (defs.loader/filesystem-definition-repository)
        _ (defs.protocol/load-workflow-defs repository)
        _ (db/ensure-runtime-directories!)
        runtime-profile (or (defs.protocol/find-runtime-profile repository
                                                                :runtime-profile/codex-worker
                                                                1)
                            (throw (ex-info "Codex runtime profile not found"
                                            {:runtime-profile/id :runtime-profile/codex-worker
                                             :runtime-profile/version 1})))
        {:keys [codex-home/root codex-home/installed-paths codex-home/skipped-paths]}
        (codex.home/install-home! runtime-profile)]
    (println (str "Ensured project CODEX_HOME at " root))
    (println "Installed runtime templates for codex worker")
    (println (str "Templates installed: " (count installed-paths)))
    (println (str "Templates preserved: " (count skipped-paths)))))

(defn- run-scheduler-once!
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

(defn- run-enqueue!
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

(defn- run-demo-happy-path!
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

(defn- run-demo-retry-path!
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

(defn- print-inspect!
  [value]
  (pprint/pprint value))

(defn- run-inspect-task!
  [args]
  (print-inspect! (scheduler/inspect-task! db/default-db-path
                                           (require-option! args "--task-id"))))

(defn- run-inspect-run!
  [args]
  (print-inspect! (scheduler/inspect-run! db/default-db-path
                                          (require-option! args "--run-id"))))

(defn- run-inspect-collection!
  []
  (print-inspect! (scheduler/inspect-collection! db/default-db-path)))

(defn dispatch-command!
  [args]
  (cond
    (= args ["init"])
    (run-init!)

    (= args ["defs" "validate"])
    (run-defs-validate!)

    (= args ["runtime" "init-codex-home"])
    (run-runtime-init-codex-home!)

    (and (>= (count args) 1)
         (= "enqueue" (first args)))
    (run-enqueue! args)

    (= args ["scheduler" "once"])
    (run-scheduler-once!)

    (= args ["demo" "happy-path"])
    (run-demo-happy-path!)

    (= args ["demo" "retry-path"])
    (run-demo-retry-path!)

    (and (>= (count args) 2)
         (= ["inspect" "task"] (subvec args 0 2)))
    (run-inspect-task! args)

    (and (>= (count args) 2)
         (= ["inspect" "run"] (subvec args 0 2)))
    (run-inspect-run! args)

    (= args ["inspect" "collection"])
    (run-inspect-collection!)

    :else
    (do
      (println usage-text)
      (throw (ex-info "Unsupported command" {:args args})))))
