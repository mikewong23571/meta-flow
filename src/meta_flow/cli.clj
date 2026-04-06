(ns meta-flow.cli
  (:require [clojure.string :as str]
            [meta-flow.cli.commands :as commands]
            [meta-flow.cli.defs :as cli.defs]
            [meta-flow.cli.inspect :as cli.inspect]
            [meta-flow.db :as db]
            [meta-flow.scheduler :as scheduler]))

(def usage-text
  (str/join
   \newline
   ["Usage:"
    "  clojure -M -m meta-flow.main init"
    "  clojure -M -m meta-flow.main defs init-overlay"
    "  clojure -M -m meta-flow.main defs validate"
    "  clojure -M -m meta-flow.main defs create-runtime-profile --from <runtime-profile-id> --new-id <runtime-profile-id> --name <name>"
    "  clojure -M -m meta-flow.main defs publish-runtime-profile --id <runtime-profile-id> --version <version>"
    "  clojure -M -m meta-flow.main defs create-task-type --from <task-type-id> --new-id <task-type-id> --name <name>"
    "  clojure -M -m meta-flow.main defs publish-task-type --id <task-type-id> --version <version>"
    "  clojure -M -m meta-flow.main runtime init-codex-home"
    "  clojure -M -m meta-flow.main enqueue [--work-key <work-key>]"
    "  clojure -M -m meta-flow.main enqueue-repo-arch --repo <repo-url> --notify-email <email>"
    "  clojure -M -m meta-flow.main scheduler once"
    "  META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main scheduler run --task-id <task-id>"
    "  clojure -M -m meta-flow.main demo happy-path"
    "  clojure -M -m meta-flow.main demo retry-path"
    "  META_FLOW_ENABLE_CODEX_SMOKE=1 clojure -M -m meta-flow.main demo codex-smoke"
    "  clojure -M -m meta-flow.main ui serve [--port <port>] [--db-path <db-path>]"
    "  clojure -M -m meta-flow.main inspect task --task-id <task-id>"
    "  clojure -M -m meta-flow.main inspect run --run-id <run-id>"
    "  clojure -M -m meta-flow.main inspect collection"]))

(defn dispatch-command!
  [args]
  (cond
    (= args ["init"])
    (commands/run-init!)

    (= args ["defs" "init-overlay"])
    (commands/run-defs-init-overlay!)

    (= args ["defs" "validate"])
    (commands/run-defs-validate!)

    (and (>= (count args) 2)
         (= ["defs" "create-runtime-profile"] (subvec args 0 2)))
    (cli.defs/run-create-runtime-profile! args)

    (and (>= (count args) 2)
         (= ["defs" "publish-runtime-profile"] (subvec args 0 2)))
    (cli.defs/run-publish-runtime-profile! args)

    (and (>= (count args) 2)
         (= ["defs" "create-task-type"] (subvec args 0 2)))
    (cli.defs/run-create-task-type! args)

    (and (>= (count args) 2)
         (= ["defs" "publish-task-type"] (subvec args 0 2)))
    (cli.defs/run-publish-task-type! args)

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
         (= ["ui" "serve"] (subvec args 0 2)))
    (commands/run-ui-serve! args)

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
