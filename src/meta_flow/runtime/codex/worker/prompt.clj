(ns meta-flow.runtime.codex.worker.prompt
  (:require [clojure.string :as str]
            [meta-flow.runtime.codex.fs :as fs]))

(def ^:private smoke-runtime-profile-id
  :runtime-profile/codex-worker)

(defn- smoke-task?
  [{:keys [runtime-profile]}]
  (= smoke-runtime-profile-id
     (:runtime-profile/id runtime-profile)))

(defn- smoke-task-instructions
  [{:keys [task run artifact-contract]} artifact-root-now]
  (str/join
   "\n"
   [""
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

(defn codex-exec-input
  [{:keys [run] :as ctx} artifact-root-now]
  (let [base-prompt (slurp (fs/worker-prompt-path (:run/id run)))]
    (if (smoke-task? ctx)
      (str base-prompt
           "\n"
           (smoke-task-instructions ctx artifact-root-now))
      base-prompt)))
