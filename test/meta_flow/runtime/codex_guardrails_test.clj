(ns meta-flow.runtime.codex-guardrails-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex-worker-api-test :as worker-api]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]))

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

(defn- seed-foreign-artifact!
  [db-path store artifact-id now]
  (let [task (support/enqueue-codex-task! db-path {:work-key (str "CVE-2024-CODEX-FOREIGN-"
                                                                  (java.util.UUID/randomUUID))})
        run-id (str "run-foreign-" (java.util.UUID/randomUUID))
        lease-id (str "lease-foreign-" (java.util.UUID/randomUUID))]
    (store.protocol/create-run! store task
                                {:run/id run-id
                                 :run/task-id (:task/id task)
                                 :run/attempt 99
                                 :run/run-fsm-ref (:task/run-fsm-ref task)
                                 :run/runtime-profile-ref (:task/runtime-profile-ref task)
                                 :run/state :run.state/leased
                                 :run/created-at now
                                 :run/updated-at now}
                                {:lease/id lease-id
                                 :lease/run-id run-id
                                 :lease/token (str lease-id "-token")
                                 :lease/state :lease.state/active
                                 :lease/expires-at "2099-04-03T00:30:00Z"
                                 :lease/created-at now
                                 :lease/updated-at now})
    (store.protocol/attach-artifact! store run-id
                                     {:artifact/id artifact-id
                                      :artifact/run-id run-id
                                      :artifact/task-id (:task/id task)
                                      :artifact/contract-ref (:task/artifact-contract-ref task)
                                      :artifact/root-path (str "/tmp/" artifact-id "-foreign")
                                      :artifact/created-at now})
    run-id))

(deftest poll-run-reads-process-state-under-the-process-file-lock
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter store run]} (worker-api/prepare-dispatched-run! db-path
                                                                        artifacts-dir
                                                                        runs-dir
                                                                        codex-home-dir)
        process-path (get-in run [:run/execution-handle :runtime-run/process-path])
        original-read-json-file codex.fs/read-json-file
        lock-held? (atom false)]
    (with-redefs [codex.fs/with-file-lock! (fn [path f]
                                             (is (= process-path path))
                                             (reset! lock-held? true)
                                             (try
                                               (f)
                                               (finally
                                                 (reset! lock-held? false))))
                  codex.fs/read-json-file (fn [path]
                                            (is (= process-path path))
                                            (is @lock-held?)
                                            (original-read-json-file path))]
      (runtime.protocol/poll-run! adapter
                                  {:store store}
                                  run
                                  "2026-04-03T01:16:00Z"))))

(deftest helper-script-rejects-foreign-artifact-ids
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [store run workdir artifact-root]} (worker-api/prepare-dispatched-run! db-path
                                                                                      artifacts-dir
                                                                                      runs-dir
                                                                                      codex-home-dir)
        foreign-run-id (seed-foreign-artifact! db-path store "artifact-run-codex-helper"
                                               "2026-04-03T00:12:00Z")]
    (spit (str artifact-root "/manifest.json") "{\"status\":\"ok\"}\n")
    (spit (str artifact-root "/notes.md") "notes\n")
    (spit (str artifact-root "/run.log") "log\n")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Helper command failed"
                          (run-helper! "artifact-ready"
                                       "--db-path" db-path
                                       "--workdir" workdir
                                       "--token" "artifact-ready"
                                       "--artifact-id" "artifact-run-codex-helper"
                                       "--artifact-root" artifact-root)))
    (testing "a foreign artifact id is not emitted or rebound onto the current run"
      (is (= [] (store.protocol/list-run-events store (:run/id run))))
      (is (= foreign-run-id
             (:artifact/run-id (store.protocol/find-artifact store "artifact-run-codex-helper"))))
      (is (nil? (:run/artifact-id (store.protocol/find-run store (:run/id run))))))))

(deftest poller-rejects-foreign-artifact-ids-during-artifact-recovery
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter store run artifact-root]} (worker-api/prepare-dispatched-run! db-path
                                                                                      artifacts-dir
                                                                                      runs-dir
                                                                                      codex-home-dir)
        foreign-run-id (seed-foreign-artifact! db-path store "artifact-run-codex-helper"
                                               "2026-04-03T00:57:00Z")]
    (spit (str artifact-root "/manifest.json") "{\"status\":\"ok\"}\n")
    (spit (str artifact-root "/notes.md") "notes\n")
    (spit (str artifact-root "/run.log") "log\n")
    (codex.fs/write-json-file! (get-in run [:run/execution-handle :runtime-run/process-path])
                               {:runId (:run/id run)
                                :taskId (:run/task-id run)
                                :status "completed"
                                :exitCode 0
                                :artifactId "artifact-run-codex-helper"
                                :artifactRoot artifact-root})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Artifact id already belongs to another run"
                          (runtime.protocol/poll-run! adapter
                                                      {:store store}
                                                      run
                                                      "2026-04-03T00:57:01Z")))
    (testing "artifact ownership stays with the original run after poller rejection"
      (is (= foreign-run-id
             (:artifact/run-id (store.protocol/find-artifact store "artifact-run-codex-helper"))))
      (is (nil? (:run/artifact-id (store.protocol/find-run store (:run/id run))))))))
