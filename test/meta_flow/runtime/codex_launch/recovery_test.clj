(ns meta-flow.runtime.codex-launch.recovery-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.control.events :as events]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex-worker-api.test-support :as worker-api]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]))

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
