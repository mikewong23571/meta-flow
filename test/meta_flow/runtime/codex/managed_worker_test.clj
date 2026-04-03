(ns meta-flow.runtime.codex.managed-worker-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.control.events :as events]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.test-support :as codex.support]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest scheduler-codex-managed-worker-completes-through-the-existing-control-plane
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (codex.support/repository-with-temp-codex-home
                    (defs.loader/filesystem-definition-repository)
                    codex-home-dir)]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn
                                                                   ([] repository)
                                                                   ([_] repository))]
        (let [task (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-MANAGED"})
              first-step (scheduler/run-scheduler-step db-path)
              created-run-id (get-in first-step [:created-runs 0 :run :run/id])
              process-path (some-> created-run-id codex.fs/process-path)
              pending-process-state (codex.fs/read-json-file process-path)
              {task-view :task run-view :run} (codex.support/wait-for-terminal-codex-run!
                                               db-path
                                               (:task/id task))
              event-types (mapv :event/type
                                (store.protocol/list-run-events
                                 (store.sqlite/sqlite-state-store db-path)
                                 (:run/id run-view)))
              process-state (codex.fs/read-json-file process-path)]
          (testing "dispatch persists a durable external execution handle"
            (is (= 1 (count (:created-runs first-step))))
            (is (empty? (:task-errors first-step)))
            (is (= "external-process"
                   (get-in (first (:created-runs first-step))
                           [:run :run/execution-handle :runtime-run/dispatch])))
            (is (string? (get-in run-view [:run/execution-handle :runtime-run/process-path])))
            (is (= "launch-pending" (:status pending-process-state)))
            (is (vector? (get-in run-view [:run/execution-handle :runtime-run/command]))))
          (testing "the managed worker converges through the same event-driven states as mock"
            (is (= :task.state/completed (:task/state task-view)))
            (is (= :run.state/finalized (:run/state run-view)))
            (is (contains? #{"exited" "completed"} (:status process-state)))
            (is (pos-int? (int (:pid process-state))))
            (is (= (.getCanonicalPath (io/file artifacts-dir
                                               (:task/id task)
                                               (:run/id run-view)))
                   (:artifactRoot process-state)))
            (is (= [events/run-dispatched
                    events/task-worker-started
                    events/run-worker-started
                    events/run-worker-heartbeat
                    events/run-worker-heartbeat
                    events/run-worker-exited
                    events/run-artifact-ready
                    events/task-artifact-ready
                    events/run-assessment-accepted
                    events/task-assessment-accepted]
                   event-types)))
          (testing "the managed worker writes a validator-acceptable artifact bundle"
            (is (.exists (io/file (:run/artifact-root run-view) "manifest.json")))
            (is (.exists (io/file (:run/artifact-root run-view) "notes.md")))
            (is (.exists (io/file (:run/artifact-root run-view) "run.log")))))))))
