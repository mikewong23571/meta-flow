(ns meta-flow.runtime.codex-worker-api.recovery-completed-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex-worker-api.test-support :as worker-api]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler.state :as scheduler.state]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]))

(deftest poller-recovers-a-finished-process-when-helper-events-are-missing
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter repository store task run artifact-root]}
        (worker-api/prepare-dispatched-run! db-path
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
          (is (= []
                 (runtime.protocol/poll-run! adapter
                                             {:store store}
                                             (store.protocol/find-run store (:run/id run))
                                             "2026-04-03T00:22:00Z"))))
        (testing "the recovered events move task and run back onto the validation path"
          (is (= :task.state/awaiting-validation
                 (:task/state (:task applied))))
          (is (= :run.state/awaiting-validation
                 (:run/state (:run applied)))))))))
