(ns meta-flow.runtime.codex-launch-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.control.events :as events]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.codex :as codex]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.process :as codex.process]
            [meta-flow.runtime.codex-worker-api-test :as worker-api]
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

(deftest launch-pending-is-claimed-before-start-and-only-one-poller-launches
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (repository-with-temp-codex-home (defs.loader/filesystem-definition-repository)
                                                    codex-home-dir)
        adapter (codex/codex-runtime)
        store (store.sqlite/sqlite-state-store db-path)
        observed-state (atom nil)
        launch-count (atom 0)]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn ([] repository) ([_] repository))]
        (let [_ (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-LAUNCH-CLAIM"})
              first-step (scheduler/run-scheduler-step db-path)
              run-id (get-in first-step [:created-runs 0 :run :run/id])
              run (store.protocol/find-run store run-id)
              process-path (codex.fs/process-path run-id)]
          (with-redefs [codex.process/build-process-builder
                        (fn [& _]
                          (swap! launch-count inc)
                          (reset! observed-state (codex.fs/read-json-file process-path))
                          (doto (ProcessBuilder. ["bash" "-lc" "sleep 0.2"])
                            (.directory (io/file "."))))]
            @(future (runtime.protocol/poll-run! adapter
                                                 {:store store
                                                  :repository repository
                                                  :db-path db-path}
                                                 run
                                                 "2026-04-03T00:00:01Z"))
            @(future (runtime.protocol/poll-run! adapter
                                                 {:store store
                                                  :repository repository
                                                  :db-path db-path}
                                                 run
                                                 "2026-04-03T00:00:01Z")))
          (testing "the launcher claims the process handle before building the worker command"
            (is (= "launching" (:status @observed-state)))
            (is (= "2026-04-03T00:00:01Z" (:launchClaimedAt @observed-state)))
            (is (string? (:launchClaimToken @observed-state))))
          (testing "overlapping polls only launch one external worker"
            (is (= 1 @launch-count))
            (is (contains? #{"dispatched" "exited" "completed"}
                           (:status (codex.fs/read-json-file process-path))))))))))

(deftest stale-launching-claims-are-recovered-and-relaunched
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (repository-with-temp-codex-home (defs.loader/filesystem-definition-repository)
                                                    codex-home-dir)
        adapter (codex/codex-runtime)
        store (store.sqlite/sqlite-state-store db-path)
        observed-state (atom nil)
        launch-count (atom 0)]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn ([] repository) ([_] repository))]
        (let [_ (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-STALE-LAUNCH"})
              first-step (scheduler/run-scheduler-step db-path)
              run-id (get-in first-step [:created-runs 0 :run :run/id])
              run (store.protocol/find-run store run-id)
              process-path (codex.fs/process-path run-id)]
          (codex.fs/write-json-file! process-path
                                     (assoc (codex.fs/read-json-file process-path)
                                            :status "launching"
                                            :launchClaimedAt "2026-04-03T01:14:00Z"
                                            :launchClaimToken "stale-claim"))
          (with-redefs [codex.process/build-process-builder
                        (fn [& _]
                          (swap! launch-count inc)
                          (reset! observed-state (codex.fs/read-json-file process-path))
                          (doto (ProcessBuilder. ["bash" "-lc" "sleep 0.2"])
                            (.directory (io/file "."))))]
            (is (= []
                   (runtime.protocol/poll-run! adapter
                                               {:store store
                                                :repository repository
                                                :db-path db-path}
                                               run
                                               "2026-04-03T01:15:00Z"))))
          (testing "stale launching claims are reclaimed before relaunch"
            (is (= 1 @launch-count))
            (is (= "launching" (:status @observed-state)))
            (is (= "2026-04-03T01:15:00Z" (:launchClaimedAt @observed-state)))
            (is (not= "stale-claim" (:launchClaimToken @observed-state))))
          (testing "the recovered run leaves launch limbo after the relaunch"
            (is (contains? #{"dispatched" "exited" "completed"}
                           (:status (codex.fs/read-json-file process-path))))))))))

(deftest cancel-requested-does-not-emit-worker-exited-until-the-process-is-gone
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter store run workdir artifact-root]}
        (worker-api/prepare-dispatched-run! db-path artifacts-dir runs-dir codex-home-dir)
        process (-> (ProcessBuilder. ["bash" "-lc" "sleep 5"])
                    (.directory (io/file "."))
                    (.start))]
    (try
      (codex.fs/write-json-file! (get-in run [:run/execution-handle :runtime-run/process-path])
                                 {:runId (:run/id run)
                                  :taskId (:run/task-id run)
                                  :status "running"
                                  :pid (.pid process)
                                  :workdir workdir
                                  :artifactId "artifact-run-codex-helper"
                                  :artifactRoot artifact-root})
      (runtime.protocol/cancel-run! adapter {} run {:reason :test/cancel})
      (let [live-poll-events (runtime.protocol/poll-run! adapter
                                                         {:store store}
                                                         run
                                                         "2026-04-03T00:50:00Z")]
        (testing "a live cancelled process does not get marked exited yet"
          (is (.isAlive process))
          (is (not-any? #(= events/run-worker-exited (:event/type %))
                        live-poll-events))))
      (.destroyForcibly process)
      (.waitFor process)
      (let [dead-poll-events (runtime.protocol/poll-run! adapter
                                                         {:store store}
                                                         run
                                                         "2026-04-03T00:50:01Z")
            exit-event (some #(when (= events/run-worker-exited (:event/type %)) %) dead-poll-events)]
        (testing "the exit event is synthesized after the pid has actually terminated"
          (is exit-event)
          (is (= 130 (get-in exit-event [:event/payload :worker/exit-code])))
          (is (= true (get-in exit-event [:event/payload :worker/cancelled?])))))
      (finally
        (when (.isAlive process)
          (.destroyForcibly process)
          (.waitFor process))))))

(deftest launch-failure-preserves-a-concurrent-cancel-request
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (repository-with-temp-codex-home (defs.loader/filesystem-definition-repository)
                                                    codex-home-dir)
        adapter (codex/codex-runtime)
        store (store.sqlite/sqlite-state-store db-path)]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn ([] repository) ([_] repository))]
        (let [_ (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-CANCELLED-LAUNCH-FAIL"})
              first-step (scheduler/run-scheduler-step db-path)
              run-id (get-in first-step [:created-runs 0 :run :run/id])
              run (store.protocol/find-run store run-id)
              process-path (codex.fs/process-path run-id)]
          (with-redefs [codex.process/build-process-builder
                        (fn [& _]
                          (runtime.protocol/cancel-run! adapter {} run {:reason :test/cancel})
                          (throw (ex-info "synthetic cancelled launch failure"
                                          {:run-id run-id})))]
            (is (= []
                   (runtime.protocol/poll-run! adapter
                                               {:store store
                                                :repository repository
                                                :db-path db-path}
                                               run
                                               "2026-04-03T01:00:00Z"))))
          (testing "launch failure keeps the concurrent cancel marker instead of erasing it"
            (is (= {:status "launch-failed"
                    :cancelReason "{:reason :test/cancel}"}
                   (select-keys (codex.fs/read-json-file process-path)
                                [:status :cancelReason])))
            (is (= 1 (:exitCode (codex.fs/read-json-file process-path)))))
          (testing "cancelled launch failures converge through timeout events the FSM can apply"
            (is (= [events/run-heartbeat-timed-out
                    events/task-heartbeat-timed-out]
                   (mapv :event/type
                         (runtime.protocol/poll-run! adapter
                                                     {:store store
                                                      :repository repository
                                                      :db-path db-path}
                                                     run
                                                     "2026-04-03T01:00:01Z"))))))))))

(deftest pre-launch-cancel-converges-via-heartbeat-timeout-events
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (repository-with-temp-codex-home (defs.loader/filesystem-definition-repository)
                                                    codex-home-dir)
        adapter (codex/codex-runtime)
        store (store.sqlite/sqlite-state-store db-path)]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn ([] repository) ([_] repository))]
        (let [_ (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-PRELAUNCH-CANCEL"})
              first-step (scheduler/run-scheduler-step db-path)
              run-id (get-in first-step [:created-runs 0 :run :run/id])
              run (store.protocol/find-run store run-id)
              _ (runtime.protocol/cancel-run! adapter {} run {:reason :test/cancel})
              _ (scheduler/run-scheduler-step db-path)
              run-after (store.protocol/find-run store run-id)
              task-after (store.protocol/find-task store (:run/task-id run-after))
              event-types (mapv :event/type (store.protocol/list-run-events store run-id))]
          (testing "pre-launch cancellations use timeout events the existing FSM can apply"
            (is (= :run.state/retryable-failed (:run/state run-after)))
            (is (= :task.state/retryable-failed (:task/state task-after)))
            (is (some #(= events/run-heartbeat-timed-out %) event-types))
            (is (some #(= events/task-heartbeat-timed-out %) event-types))
            (is (not-any? #(= events/run-worker-exited %) event-types)))
          (testing "the process handle remains marked as a never-started cancellation"
            (is (= {:status "cancel-requested"
                    :exitCode 130
                    :neverStarted true}
                   (select-keys (codex.fs/read-json-file (codex.fs/process-path run-id))
                                [:status :exitCode :neverStarted])))))))))

(deftest poller-does-not-recover-artifact-ready-after-a-failed-exit
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter store task run workdir artifact-root]}
        (worker-api/prepare-dispatched-run! db-path artifacts-dir runs-dir codex-home-dir)]
    (spit (str artifact-root "/manifest.json") "{\"status\":\"crashed\"}\n")
    (spit (str artifact-root "/notes.md") "partial\n")
    (spit (str artifact-root "/run.log") "log\n")
    (codex.fs/write-json-file! (get-in run [:run/execution-handle :runtime-run/process-path])
                               {:runId (:run/id run)
                                :taskId (:task/id task)
                                :status "exited"
                                :exitCode 1
                                :exitedAt "2026-04-03T01:20:00Z"
                                :workdir workdir
                                :artifactId "artifact-run-codex-helper"
                                :artifactRoot artifact-root})
    (let [poll-events (runtime.protocol/poll-run! adapter
                                                  {:store store}
                                                  (store.protocol/find-run store (:run/id run))
                                                  "2026-04-03T01:20:01Z")]
      (testing "failed exits do not synthesize artifact-ready recovery"
        (is (= [events/task-worker-started
                events/run-worker-started
                events/run-worker-exited]
               (mapv :event/type poll-events)))
        (is (nil? (store.protocol/find-artifact store "artifact-run-codex-helper")))))))

(deftest worker-api-bridge-preserves-allowlisted-env-vars
  (let [script-path (.getCanonicalPath (io/file "script/worker_api.bb"))
        temp-dir (.getCanonicalPath (.toFile (java.nio.file.Files/createTempDirectory
                                              "meta-flow-worker-api-env"
                                              (make-array java.nio.file.attribute.FileAttribute 0))))
        fake-bin (str temp-dir "/bin")
        env-dump (str temp-dir "/env.txt")
        fake-clojure (str fake-bin "/clojure")
        path-value (str fake-bin ":" (or (System/getenv "PATH") ""))]
    (.mkdirs (io/file fake-bin))
    (spit fake-clojure
          (str "#!/usr/bin/env bash\n"
               "printenv > \"" env-dump "\"\n"
               "exit 0\n"))
    (.setExecutable (io/file fake-clojure) true)
    (let [{:keys [exit err]} (shell/sh "bb"
                                       script-path
                                       "worker-started"
                                       "--db-path" "var/meta-flow.sqlite3"
                                       "--workdir" "."
                                       "--token" "worker-started"
                                       :env {"PATH" path-value
                                             "HOME" (System/getProperty "user.home")
                                             "JAVA_HOME" (or (System/getenv "JAVA_HOME")
                                                             (System/getProperty "java.home"))
                                             "OPENAI_API_KEY" "test-openai-key"
                                             "ANTHROPIC_API_KEY" "test-anthropic-key"})]
      (when-not (zero? exit)
        (throw (ex-info "worker_api.bb env bridge failed"
                        {:exit exit
                         :err err})))
      (let [env-text (slurp env-dump)]
        (testing "the bb-to-clojure bridge preserves incoming credentials"
          (is (.contains env-text "OPENAI_API_KEY=test-openai-key"))
          (is (.contains env-text "ANTHROPIC_API_KEY=test-anthropic-key")))))))
