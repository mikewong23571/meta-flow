(ns meta-flow.runtime.codex-smoke-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [meta-flow.cli :as cli]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.process :as codex.process]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.support.test-support :as support]))

(defn- repository-with-temp-codex-home
  [repository codex-home-dir]
  (reify defs.protocol/DefinitionRepository
    (load-workflow-defs [_]
      (defs.protocol/load-workflow-defs repository))
    (find-task-type-def [_ task-type-id version]
      (defs.protocol/find-task-type-def repository task-type-id version))
    (find-run-fsm-def [_ run-fsm-id version]
      (defs.protocol/find-run-fsm-def repository run-fsm-id version))
    (find-task-fsm-def [_ task-fsm-id version]
      (defs.protocol/find-task-fsm-def repository task-fsm-id version))
    (find-artifact-contract [_ contract-id version]
      (defs.protocol/find-artifact-contract repository contract-id version))
    (find-validator-def [_ validator-id version]
      (defs.protocol/find-validator-def repository validator-id version))
    (find-runtime-profile [_ runtime-profile-id version]
      (cond-> (defs.protocol/find-runtime-profile repository runtime-profile-id version)
        (= runtime-profile-id :runtime-profile/codex-worker)
        (assoc :runtime-profile/codex-home-root codex-home-dir)))
    (find-resource-policy [_ resource-policy-id version]
      (defs.protocol/find-resource-policy repository resource-policy-id version))))

(deftest codex-smoke-cli-is-explicit-and-runs-real-codex-only-when-the-environment-is-ready
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (repository-with-temp-codex-home (defs.loader/filesystem-definition-repository)
                                                    codex-home-dir)
        runtime-profile (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1)
        support-state (codex.process/launch-support runtime-profile)]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [db/default-db-path db-path
                    defs.loader/filesystem-definition-repository (fn
                                                                   ([] repository)
                                                                   ([_] repository))]
        (if-not (codex.process/smoke-enabled?)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Codex smoke test cannot start: set META_FLOW_ENABLE_CODEX_SMOKE=1"
                                (cli/dispatch-command! ["demo" "codex-smoke"])))
          (if-not (:launch/ready? support-state)
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  (re-pattern (java.util.regex.Pattern/quote
                                               (:launch/message support-state)))
                                  (cli/dispatch-command! ["demo" "codex-smoke"])))
            (let [output (with-out-str
                           (cli/dispatch-command! ["demo" "codex-smoke"]))
                  task-id (some->> output
                                   (re-find #"Enqueued task ([^ ]+)")
                                   second)
                  run-id (some->> output
                                  (re-find #"Created run ([^ ]+)")
                                  second)
                  task (scheduler/inspect-task! db-path task-id)
                  run (scheduler/inspect-run! db-path run-id)
                  process-state (codex.fs/read-json-file (codex.fs/process-path run-id))]
              (is (str/includes? output "Dispatched codex worker with :runtime-profile/codex-worker"))
              (is (= :task.state/completed (:task/state task)))
              (is (= :run.state/finalized (:run/state run)))
              (is (= "codex-exec" (:launchMode process-state)))
              (is (= (.getCanonicalPath (io/file codex-home-dir))
                     (:codexHome process-state)))
              (is (= (mapv str (:runtime-profile/allowed-mcp-servers runtime-profile))
                     (:allowedMcpServers process-state)))
              (is (= (boolean (:runtime-profile/web-search-enabled? runtime-profile))
                     (:webSearchEnabled process-state)))
              (is (every? (set (:runtime-profile/env-allowlist runtime-profile))
                          (:envKeys process-state)))
              (is (.exists (io/file artifacts-dir (:task/id task) run-id "manifest.json")))
              (is (.exists (io/file artifacts-dir (:task/id task) run-id "notes.md")))
              (is (.exists (io/file artifacts-dir (:task/id task) run-id "run.log"))))))))))
