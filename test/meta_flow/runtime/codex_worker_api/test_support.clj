(ns meta-flow.runtime.codex-worker-api.test-support
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.codex :as codex]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn repository-with-temp-codex-home
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

(defn run-helper!
  [command & args]
  (let [script-path (.getCanonicalPath (io/file "script/worker_api.bb"))
        {:keys [exit err]} (apply shell/sh (concat ["bb" script-path command] args))]
    (when-not (zero? exit)
      (throw (ex-info "Helper command failed"
                      {:command command
                       :args args
                       :exit exit
                       :err err})))))

(defn prepare-dispatched-run!
  [db-path artifacts-dir runs-dir codex-home-dir]
  (let [repository (repository-with-temp-codex-home (defs.loader/filesystem-definition-repository)
                                                    codex-home-dir)
        adapter (codex/codex-runtime)
        store (store.sqlite/sqlite-state-store db-path)
        task (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-HELPER"})
        run-id "run-codex-helper"
        lease-id "lease-codex-helper"
        now "2026-04-03T00:00:00Z"
        run {:run/id run-id
             :run/task-id (:task/id task)
             :run/attempt 1
             :run/run-fsm-ref (:task/run-fsm-ref task)
             :run/runtime-profile-ref (:task/runtime-profile-ref task)
             :run/state :run.state/leased
             :run/created-at now
             :run/updated-at now}
        lease {:lease/id lease-id
               :lease/run-id run-id
               :lease/token (str lease-id "-token")
               :lease/state :lease.state/active
               :lease/expires-at "2099-04-03T00:30:00Z"
               :lease/created-at now
               :lease/updated-at now}]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn
                                                                   ([] repository)
                                                                   ([_] repository))]
        (store.protocol/create-run! store task run lease)
        (store.protocol/transition-task! store (:task/id task)
                                         {:transition/from :task.state/queued
                                          :transition/to :task.state/leased}
                                         now)
        (runtime.protocol/prepare-run! adapter
                                       {:db-path db-path
                                        :store store
                                        :repository repository
                                        :now now}
                                       task
                                       run)
        (codex.fs/write-json-file! (codex.fs/process-path run-id)
                                   {:runId run-id
                                    :taskId (:task/id task)
                                    :status "dispatched"
                                    :workdir (codex.fs/run-workdir run-id)
                                    :artifactId (str "artifact-" run-id)
                                    :artifactRoot (codex.fs/artifact-root-path (:task/id task) run-id)})
        (store.protocol/transition-run! store run-id
                                        {:transition/from :run.state/leased
                                         :transition/to :run.state/dispatched
                                         :changes {:run/execution-handle
                                                   {:runtime-run/process-path (codex.fs/process-path run-id)}}}
                                        now)
        {:adapter adapter
         :repository repository
         :store store
         :task (store.protocol/find-task store (:task/id task))
         :run (store.protocol/find-run store run-id)
         :workdir (codex.fs/run-workdir run-id)
         :artifact-root (codex.fs/artifact-root-path (:task/id task) run-id)}))))
