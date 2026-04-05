(ns meta-flow.cli
  (:require [clojure.string :as str]
            [meta-flow.cli.commands :as commands]
            [meta-flow.cli.inspect :as cli.inspect]
            [meta-flow.db :as db]
            [meta-flow.scheduler :as scheduler]))

(def usage-text
  (str/join
   \newline
   ["Usage:"
    "  clojure -M -m meta-flow.main init"
    "  clojure -M -m meta-flow.main defs validate"
    "  clojure -M -m meta-flow.main runtime init-codex-home"
    "  clojure -M -m meta-flow.main enqueue [--work-key <work-key>]"
    "  clojure -M -m meta-flow.main enqueue-repo-arch --repo <repo-url> --notify-email <email>"
    "  clojure -M -m meta-flow.main scheduler once"
    "  META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main scheduler run --task-id <task-id>"
    "  clojure -M -m meta-flow.main demo happy-path"
    "  clojure -M -m meta-flow.main demo retry-path"
    "  META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main demo codex-smoke"
    "  clojure -M -m meta-flow.main inspect task --task-id <task-id>"
    "  clojure -M -m meta-flow.main inspect run --run-id <run-id>"
    "  clojure -M -m meta-flow.main inspect collection"]))

(defn dispatch-command!
  [args]
  (cond
    (= args ["init"])
    (commands/run-init!)

    (= args ["defs" "validate"])
    (commands/run-defs-validate!)

    (= args ["runtime" "init-codex-home"])
    (commands/run-runtime-init-codex-home!)

    (and (>= (count args) 1)
         (= "enqueue" (first args)))
    (commands/run-enqueue! args)

    (and (>= (count args) 1)
         (= "enqueue-repo-arch" (first args)))
    (commands/run-enqueue-repo-arch! args)

    (= args ["scheduler" "once"])
    (commands/run-scheduler-once!)

    (and (>= (count args) 2)
         (= ["scheduler" "run"] (subvec args 0 2)))
    (let [task-id (commands/require-option! args "--task-id")]
      (commands/ensure-system-ready!)
      (println (str "Running scheduler until task " task-id " completes..."))
      (let [{:keys [task run artifact-root scheduler-steps]}
            (scheduler/run-task-until-complete! db/default-db-path task-id)]
        (println (str "Done in " scheduler-steps " steps"))
        (println (str "Task " (:task/id task) " -> " (:task/state task)))
        (when run
          (println (str "Run  " (:run/id run) " -> " (:run/state run))))
        (when artifact-root
          (println (str "Artifact root: " artifact-root)))))

    (= args ["demo" "happy-path"])
    (commands/run-demo-happy-path!)

    (= args ["demo" "retry-path"])
    (commands/run-demo-retry-path!)

    (= args ["demo" "codex-smoke"])
    (commands/run-demo-codex-smoke!)

    (and (>= (count args) 2)
         (= ["inspect" "task"] (subvec args 0 2)))
    (cli.inspect/run-task! (commands/require-option! args "--task-id"))

    (and (>= (count args) 2)
         (= ["inspect" "run"] (subvec args 0 2)))
    (cli.inspect/run-run! (commands/require-option! args "--run-id"))

    (= args ["inspect" "collection"])
    (cli.inspect/run-collection!)

    :else
    (do
      (println usage-text)
      (throw (ex-info "Unsupported command" {:args args})))))
