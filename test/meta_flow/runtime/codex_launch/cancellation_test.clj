(ns meta-flow.runtime.codex-launch.cancellation-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.control.events :as events]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.runtime.codex :as codex]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.process.launch :as codex.launch]
            [meta-flow.runtime.codex-worker-api.test-support :as worker-api]
            [meta-flow.runtime.codex-launch.test-support :as launch.support]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

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
            exit-event (some #(when (= events/run-worker-exited (:event/type %))
                                %)
                             dead-poll-events)]
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
        repository (launch.support/repository-with-temp-codex-home
                    (defs.loader/filesystem-definition-repository)
                    codex-home-dir)
        adapter (codex/codex-runtime)
        store (store.sqlite/sqlite-state-store db-path)]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn ([] repository)
                                                                   ([_] repository))]
        (let [_ (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-CANCELLED-LAUNCH-FAIL"})
              first-step (scheduler/run-scheduler-step db-path)
              run-id (get-in first-step [:created-runs 0 :run :run/id])
              run (store.protocol/find-run store run-id)
              process-path (codex.fs/process-path run-id)]
          (with-redefs [codex.launch/build-process-builder
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
        repository (launch.support/repository-with-temp-codex-home
                    (defs.loader/filesystem-definition-repository)
                    codex-home-dir)
        adapter (codex/codex-runtime)
        store (store.sqlite/sqlite-state-store db-path)]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn ([] repository)
                                                                   ([_] repository))]
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
