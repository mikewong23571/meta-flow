(ns meta-flow.runtime.codex-worker-api.recovery-cancellation-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.control.events :as events]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.helper :as codex.helper]
            [meta-flow.runtime.codex-worker-api.test-support :as worker-api]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler.state :as scheduler.state]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]))

(deftest poller-recovers-worker-exit-after-cancel-without-helper-callback
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter repository store task run workdir artifact-root]}
        (worker-api/prepare-dispatched-run! db-path artifacts-dir runs-dir codex-home-dir)]
    (spit (str artifact-root "/manifest.json") "{\"status\":\"cancelled\"}\n")
    (spit (str artifact-root "/notes.md") "cancelled\n")
    (spit (str artifact-root "/run.log") "log\n")
    (codex.fs/write-json-file! (get-in run [:run/execution-handle :runtime-run/process-path])
                               {:runId (:run/id run)
                                :taskId (:task/id task)
                                :status "cancel-requested"
                                :exitCode 130
                                :workdir workdir
                                :cancelReason "{:reason :test/cancel}"
                                :artifactId "artifact-run-codex-helper"
                                :artifactRoot artifact-root})
    (let [poll-events (runtime.protocol/poll-run! adapter
                                                  {:store store}
                                                  (store.protocol/find-run store (:run/id run))
                                                  "2026-04-03T00:30:00Z")]
      (doseq [event-intent poll-events]
        (store.protocol/ingest-run-event! store event-intent))
      (let [applied (scheduler.state/apply-event-stream! store
                                                         repository
                                                         (store.protocol/find-run store (:run/id run))
                                                         (store.protocol/find-task store (:task/id task))
                                                         "2026-04-03T00:30:01Z")]
        (testing "cancel-requested runs still emit the missing start/exit sequence"
          (is (= [:task.event/worker-started
                  :run.event/worker-started
                  :run.event/worker-exited]
                 (mapv :event/type poll-events)))
          (is (= :run.state/exited
                 (:run/state (:run applied))))
          (is (= :task.state/running
                 (:task/state (:task applied)))))))))

(deftest helper-updates-do-not-clobber-a-persisted-cancel-request
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter run workdir]}
        (worker-api/prepare-dispatched-run! db-path artifacts-dir runs-dir codex-home-dir)]
    (runtime.protocol/cancel-run! adapter {} run {:reason :test/cancel})
    (codex.helper/update-process-state! workdir
                                        #(assoc % :status "running"
                                                :lastHeartbeatAt "2026-04-03T00:55:00Z"))
    (testing "helper state updates preserve the cancellation marker instead of erasing it"
      (is (= {:status "cancel-requested"
              :cancelReason "{:reason :test/cancel}"
              :lastHeartbeatAt "2026-04-03T00:55:00Z"}
             (select-keys (codex.fs/read-json-file (str workdir "/process.json"))
                          [:status :cancelReason :lastHeartbeatAt]))))))

(deftest cancelled-exit-preserves-cancel-markers-and-blocks-artifact-recovery
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter store run workdir artifact-root]}
        (worker-api/prepare-dispatched-run! db-path artifacts-dir runs-dir codex-home-dir)]
    (spit (str artifact-root "/manifest.json") "{\"status\":\"cancelled\"}\n")
    (spit (str artifact-root "/notes.md") "cancelled\n")
    (spit (str artifact-root "/run.log") "log\n")
    (runtime.protocol/cancel-run! adapter {} run {:reason :test/cancel})
    (codex.helper/emit-worker-exit! store
                                    {:run run
                                     :workdir workdir}
                                    {:token "worker-cancelled"
                                     :at "2026-04-03T00:56:00Z"
                                     :exit-code 130
                                     :cancelled true})
    (let [process-state (codex.fs/read-json-file (str workdir "/process.json"))]
      (testing "cancelled exit keeps explicit cancel markers on disk"
        (is (= {:status "exited"
                :cancelReason "{:reason :test/cancel}"
                :cancelled true
                :exitCode 130}
               (select-keys process-state [:status :cancelReason :cancelled :exitCode]))))
      (testing "poller does not synthesize artifact-ready for a cancelled exited process"
        (is (not-any? #(contains? #{events/run-artifact-ready
                                    events/task-artifact-ready}
                                  (:event/type %))
                      (runtime.protocol/poll-run! adapter
                                                  {:store store}
                                                  run
                                                  "2026-04-03T00:56:01Z")))))))

(deftest helper-script-ignores-artifact-ready-after-a-cancel-request
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter store run workdir artifact-root]}
        (worker-api/prepare-dispatched-run! db-path
                                            artifacts-dir
                                            runs-dir
                                            codex-home-dir)]
    (spit (str artifact-root "/manifest.json") "{\"status\":\"ok\"}\n")
    (spit (str artifact-root "/notes.md") "notes\n")
    (spit (str artifact-root "/run.log") "log\n")
    (worker-api/run-helper! "worker-exit"
                            "--db-path" db-path
                            "--workdir" workdir
                            "--token" "worker-exited"
                            "--exit-code" "0")
    (runtime.protocol/cancel-run! adapter {} run {:reason :test/cancel})
    (worker-api/run-helper! "artifact-ready"
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
