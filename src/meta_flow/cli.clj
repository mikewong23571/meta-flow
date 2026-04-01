(ns meta-flow.cli
  (:require [clojure.string :as str]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]))

(def usage-text
  (str/join
   \newline
   ["Usage:"
    "  clojure -M -m meta-flow.main init"
    "  clojure -M -m meta-flow.main defs validate"]))

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

(defn dispatch-command!
  [args]
  (cond
    (= args ["init"])
    (run-init!)

    (= args ["defs" "validate"])
    (run-defs-validate!)

    :else
    (do
      (println usage-text)
      (throw (ex-info "Unsupported command" {:args args})))))
