(ns meta-flow.runtime.codex-worker-api.recovery-poller-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.control.events :as events]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.helper :as codex.helper]
            [meta-flow.runtime.codex-worker-api.test-support :as worker-api]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler.state :as scheduler.state]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]))

(defn- missing-process-pid
  []
  (let [start (+ 100000 (.pid (java.lang.ProcessHandle/current)))
        max-attempts 10000]
    (or (some (fn [candidate]
                (when-not (.isPresent (java.lang.ProcessHandle/of candidate))
                  candidate))
              (range start (+ start max-attempts)))
        (throw (ex-info "Unable to find an unused process pid for recovery test"
                        {:start start
                         :max-attempts max-attempts})))))

(deftest poller-recovers-artifact-ready-after-helper-partial-failure
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter repository store task run workdir artifact-root]}
        (worker-api/prepare-dispatched-run! db-path artifacts-dir runs-dir codex-home-dir)]
    (spit (str artifact-root "/manifest.json") "{\"status\":\"ok\"}\n")
    (spit (str artifact-root "/notes.md") "notes\n")
    (spit (str artifact-root "/run.log") "log\n")
    (store.protocol/ingest-run-event! store {:event/run-id (:run/id run)
                                             :event/type :task.event/worker-started
                                             :event/idempotency-key "test:task-worker-started"
                                             :event/payload {}
                                             :event/caused-by {:actor/type :test
                                                               :actor/id "partial-helper"}
                                             :event/emitted-at "2026-04-03T00:00:01Z"})
    (store.protocol/ingest-run-event! store {:event/run-id (:run/id run)
                                             :event/type :run.event/worker-started
                                             :event/idempotency-key "test:run-worker-started"
                                             :event/payload {}
                                             :event/caused-by {:actor/type :test
                                                               :actor/id "partial-helper"}
                                             :event/emitted-at "2026-04-03T00:00:01Z"})
    (store.protocol/ingest-run-event! store {:event/run-id (:run/id run)
                                             :event/type :run.event/worker-exited
                                             :event/idempotency-key "test:run-worker-exited"
                                             :event/payload {:worker/exit-code 0}
                                             :event/caused-by {:actor/type :test
                                                               :actor/id "partial-helper"}
                                             :event/emitted-at "2026-04-03T00:00:02Z"})
    (scheduler.state/apply-event-stream! store
                                         repository
                                         (store.protocol/find-run store (:run/id run))
                                         (store.protocol/find-task store (:task/id task))
                                         "2026-04-03T00:00:03Z")
    (codex.helper/update-process-state! workdir
                                        #(assoc % :status "exited"
                                                :exitCode 0
                                                :artifactId "artifact-run-codex-helper"
                                                :artifactRoot artifact-root
                                                :artifactReadyStatus {:state "in-flight"
                                                                      :updated-at "2026-04-03T00:00:00Z"}))
    (let [poll-events (runtime.protocol/poll-run! adapter
                                                  {:store store}
                                                  (store.protocol/find-run store (:run/id run))
                                                  "2026-04-03T00:00:07Z")]
      (doseq [event-intent poll-events]
        (store.protocol/ingest-run-event! store event-intent))
      (let [applied (scheduler.state/apply-event-stream! store
                                                         repository
                                                         (store.protocol/find-run store (:run/id run))
                                                         (store.protocol/find-task store (:task/id task))
                                                         "2026-04-03T00:00:08Z")]
        (testing "stale in-flight helper state does not suppress artifact recovery forever"
          (is (= [:run.event/artifact-ready
                  :task.event/artifact-ready]
                 (mapv :event/type poll-events)))
          (is (= :task.state/awaiting-validation
                 (:task/state (:task applied))))
          (is (= :run.state/awaiting-validation
                 (:run/state (:run applied)))))))))

(deftest helper-in-flight-windows-outlast-the-sqlite-busy-timeout
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter repository store task run workdir artifact-root]}
        (worker-api/prepare-dispatched-run! db-path artifacts-dir runs-dir codex-home-dir)]
    (testing "worker-started recovery stays suppressed while helper still owns the event"
      (codex.fs/write-json-file! (get-in run [:run/execution-handle :runtime-run/process-path])
                                 {:runId (:run/id run)
                                  :taskId (:task/id task)
                                  :status "dispatched"
                                  :workdir workdir
                                  :startedAt "2026-04-03T00:00:00Z"
                                  :workerStartedStatus {:state "in-flight"
                                                        :updated-at "2026-04-03T00:00:00Z"}})
      (is (= []
             (runtime.protocol/poll-run! adapter
                                         {:store store}
                                         (store.protocol/find-run store (:run/id run))
                                         "2026-04-03T00:00:03Z"))))
    (testing "artifact-ready recovery stays suppressed while helper still owns the event"
      (spit (str artifact-root "/manifest.json") "{\"status\":\"ok\"}\n")
      (spit (str artifact-root "/notes.md") "notes\n")
      (spit (str artifact-root "/run.log") "log\n")
      (store.protocol/ingest-run-event! store {:event/run-id (:run/id run)
                                               :event/type events/task-worker-started
                                               :event/idempotency-key "test:busy-window:task-worker-started"
                                               :event/payload {}
                                               :event/caused-by {:actor/type :test
                                                                 :actor/id "busy-window"}
                                               :event/emitted-at "2026-04-03T00:00:01Z"})
      (store.protocol/ingest-run-event! store {:event/run-id (:run/id run)
                                               :event/type events/run-worker-started
                                               :event/idempotency-key "test:busy-window:run-worker-started"
                                               :event/payload {}
                                               :event/caused-by {:actor/type :test
                                                                 :actor/id "busy-window"}
                                               :event/emitted-at "2026-04-03T00:00:01Z"})
      (store.protocol/ingest-run-event! store {:event/run-id (:run/id run)
                                               :event/type events/run-worker-exited
                                               :event/idempotency-key "test:busy-window:run-worker-exited"
                                               :event/payload {:worker/exit-code 0}
                                               :event/caused-by {:actor/type :test
                                                                 :actor/id "busy-window"}
                                               :event/emitted-at "2026-04-03T00:00:02Z"})
      (scheduler.state/apply-event-stream! store
                                           repository
                                           (store.protocol/find-run store (:run/id run))
                                           (store.protocol/find-task store (:task/id task))
                                           "2026-04-03T00:00:02Z")
      (codex.fs/write-json-file! (get-in run [:run/execution-handle :runtime-run/process-path])
                                 {:runId (:run/id run)
                                  :taskId (:task/id task)
                                  :status "exited"
                                  :workdir workdir
                                  :exitCode 0
                                  :exitedAt "2026-04-03T00:00:02Z"
                                  :artifactId "artifact-run-codex-helper"
                                  :artifactRoot artifact-root
                                  :artifactReadyStatus {:state "in-flight"
                                                        :updated-at "2026-04-03T00:00:00Z"}})
      (is (= []
             (runtime.protocol/poll-run! adapter
                                         {:store store}
                                         (store.protocol/find-run store (:run/id run))
                                         "2026-04-03T00:00:03Z"))))
    (testing "artifact-ready recovery waits until the worker is exited"
      (spit (str artifact-root "/manifest.json") "{\"status\":\"ok\"}\n")
      (spit (str artifact-root "/notes.md") "notes\n")
      (spit (str artifact-root "/run.log") "log\n")
      (store.protocol/ingest-run-event! store {:event/run-id (:run/id run)
                                               :event/type events/task-worker-started
                                               :event/idempotency-key "test:exit-order:task-worker-started"
                                               :event/payload {}
                                               :event/caused-by {:actor/type :test
                                                                 :actor/id "exit-order"}
                                               :event/emitted-at "2026-04-03T00:00:01Z"})
      (store.protocol/ingest-run-event! store {:event/run-id (:run/id run)
                                               :event/type events/run-worker-started
                                               :event/idempotency-key "test:exit-order:run-worker-started"
                                               :event/payload {}
                                               :event/caused-by {:actor/type :test
                                                                 :actor/id "exit-order"}
                                               :event/emitted-at "2026-04-03T00:00:01Z"})
      (codex.fs/write-json-file! (get-in run [:run/execution-handle :runtime-run/process-path])
                                 {:runId (:run/id run)
                                  :taskId (:task/id task)
                                  :status "running"
                                  :startedAt "2026-04-03T00:00:01Z"
                                  :workdir workdir
                                  :artifactId "artifact-run-codex-helper"
                                  :artifactRoot artifact-root})
      (is (= []
             (runtime.protocol/poll-run! adapter
                                         {:store store}
                                         (store.protocol/find-run store (:run/id run))
                                         "2026-04-03T00:00:02Z"))))))

(deftest poller-recovers-a-vanished-worker-pid-as-an-exit
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [adapter repository store task run workdir artifact-root]}
        (worker-api/prepare-dispatched-run! db-path artifacts-dir runs-dir codex-home-dir)]
    (spit (str artifact-root "/manifest.json") "{\"status\":\"ok\"}\n")
    (spit (str artifact-root "/notes.md") "notes\n")
    (spit (str artifact-root "/run.log") "log\n")
    (codex.fs/write-json-file! (get-in run [:run/execution-handle :runtime-run/process-path])
                               {:runId (:run/id run)
                                :taskId (:task/id task)
                                :status "dispatched"
                                :pid (missing-process-pid)
                                :workdir workdir
                                :artifactId "artifact-run-codex-helper"
                                :artifactRoot artifact-root})
    (let [poll-events (runtime.protocol/poll-run! adapter
                                                  {:store store}
                                                  (store.protocol/find-run store (:run/id run))
                                                  "2026-04-03T00:40:00Z")]
      (doseq [event-intent poll-events]
        (store.protocol/ingest-run-event! store event-intent))
      (let [applied (scheduler.state/apply-event-stream! store
                                                         repository
                                                         (store.protocol/find-run store (:run/id run))
                                                         (store.protocol/find-task store (:task/id task))
                                                         "2026-04-03T00:40:01Z")]
        (testing "missing process handles are treated like terminated workers"
          (is (= [events/task-worker-started
                  events/run-worker-started
                  events/run-worker-exited]
                 (mapv :event/type poll-events)))
          (is (= :run.state/exited
                 (:run/state (:run applied))))
          (is (= :task.state/running
                 (:task/state (:task applied)))))))))
