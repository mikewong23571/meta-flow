(ns meta-flow.runtime.codex-worker-api.helper-callbacks-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.runtime.codex-worker-api.test-support :as worker-api]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler.state :as scheduler.state]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]))

(deftest helper-script-writes-idempotent-events-through-the-shared-ingestion-path
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter store repository task run workdir artifact-root]}
        (worker-api/prepare-dispatched-run! db-path artifacts-dir runs-dir codex-home-dir)]
    (spit (str artifact-root "/manifest.json") "{\"status\":\"ok\"}\n")
    (spit (str artifact-root "/notes.md") "notes\n")
    (spit (str artifact-root "/run.log") "log\n")
    (worker-api/run-helper! "worker-started"
                            "--db-path" db-path
                            "--workdir" workdir
                            "--token" "worker-started")
    (worker-api/run-helper! "heartbeat"
                            "--db-path" db-path
                            "--workdir" workdir
                            "--token" "heartbeat-1"
                            "--status" ":worker.status/running"
                            "--stage" ":worker.stage/research")
    (worker-api/run-helper! "worker-exit"
                            "--db-path" db-path
                            "--workdir" workdir
                            "--token" "worker-exited"
                            "--exit-code" "0")
    (worker-api/run-helper! "artifact-ready"
                            "--db-path" db-path
                            "--workdir" workdir
                            "--token" "artifact-ready"
                            "--artifact-id" "artifact-run-codex-helper"
                            "--artifact-root" artifact-root)
    (worker-api/run-helper! "artifact-ready"
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
