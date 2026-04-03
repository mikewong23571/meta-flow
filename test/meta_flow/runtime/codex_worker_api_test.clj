(ns meta-flow.runtime.codex-worker-api-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.codex :as codex]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler.state :as scheduler.state]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

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

(defn- run-helper!
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

(deftest helper-script-writes-idempotent-events-through-the-shared-ingestion-path
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter store repository task run workdir artifact-root]}
        (prepare-dispatched-run! db-path artifacts-dir runs-dir codex-home-dir)]
    (spit (str artifact-root "/manifest.json") "{\"status\":\"ok\"}\n")
    (spit (str artifact-root "/notes.md") "notes\n")
    (spit (str artifact-root "/run.log") "log\n")
    (run-helper! "worker-started"
                 "--db-path" db-path
                 "--workdir" workdir
                 "--token" "worker-started")
    (run-helper! "heartbeat"
                 "--db-path" db-path
                 "--workdir" workdir
                 "--token" "heartbeat-1"
                 "--status" ":worker.status/running"
                 "--stage" ":worker.stage/research")
    (run-helper! "worker-exit"
                 "--db-path" db-path
                 "--workdir" workdir
                 "--token" "worker-exited"
                 "--exit-code" "0")
    (run-helper! "artifact-ready"
                 "--db-path" db-path
                 "--workdir" workdir
                 "--token" "artifact-ready"
                 "--artifact-id" "artifact-run-codex-helper"
                 "--artifact-root" artifact-root)
    (run-helper! "artifact-ready"
                 "--db-path" db-path
                 "--workdir" workdir
                 "--token" "artifact-ready"
                 "--artifact-id" "artifact-run-codex-helper"
                 "--artifact-root" artifact-root)
    (let [events-now (store.protocol/list-run-events store (:run/id run))
          applied (scheduler.state/apply-event-stream! store
                                                       repository
                                                       (store.protocol/find-run store (:run/id run))
                                                       (store.protocol/find-task store (:task/id task))
                                                       "2026-04-03T00:10:00Z")]
      (testing "helper subcommands emit the expected event sequence without duplicates"
        (is (= [:task.event/worker-started
                :run.event/worker-started
                :run.event/worker-heartbeat
                :run.event/worker-exited
                :run.event/artifact-ready
                :task.event/artifact-ready]
               (mapv :event/type events-now)))
        (is (= 6 (count events-now))))
      (testing "the emitted helper events move task and run to awaiting validation"
        (is (= :task.state/awaiting-validation
               (:task/state (:task applied))))
        (is (= :run.state/awaiting-validation
               (:run/state (:run applied)))))
      (testing "polling does not synthesize duplicate exit or artifact events after helper success"
        (is (= []
               (runtime.protocol/poll-run! adapter
                                           {:store store}
                                           (store.protocol/find-run store (:run/id run))
                                           "2026-04-03T00:11:00Z")))))))

(deftest poller-recovers-a-finished-process-when-helper-events-are-missing
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter repository store task run artifact-root]} (prepare-dispatched-run! db-path
                                                                                           artifacts-dir
                                                                                           runs-dir
                                                                                           codex-home-dir)]
    (spit (str artifact-root "/manifest.json") "{\"status\":\"ok\"}\n")
    (spit (str artifact-root "/notes.md") "notes\n")
    (spit (str artifact-root "/run.log") "log\n")
    (codex.fs/write-json-file! (get-in run [:run/execution-handle :runtime-run/process-path])
                               {:runId (:run/id run)
                                :taskId (:task/id task)
                                :status "completed"
                                :exitCode 0
                                :artifactId "artifact-run-codex-helper"
                                :artifactRoot artifact-root})
    (let [poll-events (runtime.protocol/poll-run! adapter
                                                  {:store store}
                                                  run
                                                  "2026-04-03T00:20:00Z")]
      (doseq [event-intent poll-events]
        (store.protocol/ingest-run-event! store event-intent))
      (let [applied (scheduler.state/apply-event-stream! store
                                                         repository
                                                         (store.protocol/find-run store (:run/id run))
                                                         (store.protocol/find-task store (:task/id task))
                                                         "2026-04-03T00:21:00Z")]
        (testing "poller can synthesize the missing lifecycle events exactly once"
          (is (= [:task.event/worker-started
                  :run.event/worker-started
                  :run.event/worker-exited
                  :run.event/artifact-ready
                  :task.event/artifact-ready]
                 (mapv :event/type poll-events)))
          (is (= [] (runtime.protocol/poll-run! adapter
                                                {:store store}
                                                (store.protocol/find-run store (:run/id run))
                                                "2026-04-03T00:22:00Z"))))
        (testing "the recovered events move task and run back onto the validation path"
          (is (= :task.state/awaiting-validation
                 (:task/state (:task applied))))
          (is (= :run.state/awaiting-validation
                 (:run/state (:run applied)))))))))

(deftest helper-script-ignores-artifact-ready-after-a-cancel-request
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter store run workdir artifact-root]} (prepare-dispatched-run! db-path
                                                                                   artifacts-dir
                                                                                   runs-dir
                                                                                   codex-home-dir)]
    (spit (str artifact-root "/manifest.json") "{\"status\":\"ok\"}\n")
    (spit (str artifact-root "/notes.md") "notes\n")
    (spit (str artifact-root "/run.log") "log\n")
    (run-helper! "worker-exit"
                 "--db-path" db-path
                 "--workdir" workdir
                 "--token" "worker-exited"
                 "--exit-code" "0")
    (runtime.protocol/cancel-run! adapter {} run {:reason :test/cancel})
    (run-helper! "artifact-ready"
                 "--db-path" db-path
                 "--workdir" workdir
                 "--token" "artifact-ready"
                 "--artifact-id" "artifact-run-codex-helper"
                 "--artifact-root" artifact-root)
    (testing "cancelled runs do not accept helper artifact-ready callbacks"
      (is (= [:run.event/worker-exited]
             (mapv :event/type (store.protocol/list-run-events store (:run/id run)))))
      (is (nil? (store.protocol/find-artifact store "artifact-run-codex-helper")))
      (is (= {:status "cancel-requested"
              :cancelReason "{:reason :test/cancel}"
              :helperEvents {:workerExited true}}
             (select-keys (codex.fs/read-json-file (str workdir "/process.json"))
                          [:status :cancelReason :helperEvents]))))))

(deftest helper-bridge-resolves-relative-path-args-before-changing-cwd
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [store run workdir]} (prepare-dispatched-run! db-path
                                                             artifacts-dir
                                                             runs-dir
                                                             codex-home-dir)
        relative-db-path (str (.relativize (.toPath (io/file workdir))
                                           (.toPath (io/file db-path))))
        script-path (.getCanonicalPath (io/file "script/worker_api.bb"))
        {:keys [exit err]}
        (shell/sh "bb"
                  script-path
                  "worker-started"
                  "--db-path" relative-db-path
                  "--workdir" "."
                  "--token" "worker-started"
                  :dir workdir)]
    (when-not (zero? exit)
      (throw (ex-info "Helper command failed"
                      {:command "worker-started"
                       :exit exit
                       :err err})))
    (testing "the bridge emits events into the original SQLite database and run workdir even from another cwd"
      (is (= [:task.event/worker-started
              :run.event/worker-started]
             (mapv :event/type (store.protocol/list-run-events store (:run/id run))))))))
