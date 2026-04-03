(ns meta-flow.runtime.codex.launch-mode-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.codex :as codex]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.process :as codex.process]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn- repository-with-temp-codex-home
  [repository codex-home-dir]
  (reify defs.protocol/DefinitionRepository
    (load-workflow-defs [_] (defs.protocol/load-workflow-defs repository))
    (find-task-type-def [_ task-type-id version] (defs.protocol/find-task-type-def repository task-type-id version))
    (find-run-fsm-def [_ run-fsm-id version] (defs.protocol/find-run-fsm-def repository run-fsm-id version))
    (find-task-fsm-def [_ task-fsm-id version] (defs.protocol/find-task-fsm-def repository task-fsm-id version))
    (find-artifact-contract [_ contract-id version] (defs.protocol/find-artifact-contract repository contract-id version))
    (find-validator-def [_ validator-id version] (defs.protocol/find-validator-def repository validator-id version))
    (find-runtime-profile [_ runtime-profile-id version]
      (cond-> (defs.protocol/find-runtime-profile repository runtime-profile-id version)
        (= runtime-profile-id :runtime-profile/codex-worker)
        (assoc :runtime-profile/codex-home-root codex-home-dir)))
    (find-resource-policy [_ resource-policy-id version] (defs.protocol/find-resource-policy repository resource-policy-id version))))

(deftest launch-process-uses-the-persisted-launch-mode-instead-of-re-reading-the-env
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (repository-with-temp-codex-home (defs.loader/filesystem-definition-repository)
                                                    codex-home-dir)
        adapter (codex/codex-runtime)
        store (store.sqlite/sqlite-state-store db-path)
        launched-command (atom nil)]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn ([] repository) ([_] repository))
                    codex/ensure-launch-supported! (constantly {:launch/ready? true})
                    codex.process/launch-mode (constantly :launch.mode/codex-exec)]
        (let [_ (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-PERSISTED-LAUNCH-MODE"})
              first-step (scheduler/run-scheduler-step db-path)
              run-id (get-in first-step [:created-runs 0 :run :run/id])
              run (store.protocol/find-run store run-id)
              runtime-profile (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1)
              process-path (codex.fs/process-path run-id)]
          (is (= "codex-exec"
                 (:launchMode (codex.fs/read-json-file process-path))))
          (with-redefs [codex.process/launch-mode (constantly :launch.mode/stub-worker)
                        codex.process/build-process-builder
                        (fn [command & _]
                          (reset! launched-command command)
                          (doto (ProcessBuilder. ["bash" "-lc" "sleep 0.2"])
                            (.directory (io/file "."))))]
            (is (= []
                   (runtime.protocol/poll-run! adapter
                                               {:store store
                                                :repository repository
                                                :db-path db-path}
                                               run
                                               "2026-04-03T01:10:00Z"))))
          (is (= (codex.process/launch-command db-path
                                               run-id
                                               runtime-profile
                                               :launch.mode/codex-exec)
                 @launched-command)))))))
