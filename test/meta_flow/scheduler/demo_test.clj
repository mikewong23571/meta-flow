(ns meta-flow.scheduler.demo-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.runtime.registry :as runtime.registry]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.dev :as scheduler.dev]
            [meta-flow.scheduler.support.test-support :as support]))

(deftest demo-happy-path-completes-and-persists-structured-control-data
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [{:keys [task run artifact-root scheduler-steps]} (scheduler/demo-happy-path! db-path)
            task-id (:task/id task)
            run-id (:run/id run)
            task-view (scheduler/inspect-task! db-path task-id)
            run-view (scheduler/inspect-run! db-path run-id)
            collection-view (scheduler/inspect-collection! db-path)]
        (testing "the demo converges to the Phase 1 happy path terminal states"
          (is (= 5 scheduler-steps))
          (is (= :task.state/completed (:task/state task)))
          (is (= :run.state/finalized (:run/state run)))
          (is (= 9 (:run/event-count run-view)))
          (is (string? (:run/last-heartbeat run-view))))
        (testing "artifact files are written under the run-scoped artifact root"
          (is (= artifact-root (:run/artifact-root run-view)))
          (is (.exists (io/file artifact-root)))
          (is (.exists (io/file artifact-root "manifest.json")))
          (is (.exists (io/file artifact-root "notes.md")))
          (is (.exists (io/file artifact-root "run.log"))))
        (testing "inspect returns the structured definition refs needed for control-plane visibility"
          (is (= {:definition/id :task-type/cve-investigation
                  :definition/version 1}
                 (:task/task-type-ref task-view)))
          (is (= {:definition/id :run-fsm/cve-worker
                  :definition/version 2}
                 (:task/run-fsm-ref task-view)))
          (is (= {:definition/id :runtime-profile/mock-worker
                  :definition/version 1}
                 (:task/runtime-profile-ref task-view)))
          (is (= {:definition/id :runtime-profile/mock-worker
                  :definition/version 1}
                 (:run/runtime-profile-ref run-view)))
          (is (= {:definition/id :resource-policy/default
                  :definition/version 3}
                 (:collection/resource-policy-ref collection-view))))
        (testing "dispatch persists a durable execution handle on the run"
          (is (= "polling"
                 (get-in run-view [:run/execution-handle :runtime-run/dispatch])))
          (is (str/starts-with? (get-in run-view [:run/execution-handle :runtime-run/workdir])
                                (str runs-dir "/"))))
        (testing "the demo bootstraps persisted collection state"
          (is (= {:item_count 1}
                 (select-keys (support/query-one db-path
                                                 "SELECT COUNT(*) AS item_count FROM collection_state"
                                                 [])
                              [:item_count]))))
        (testing "structured control columns are persisted outside the EDN blobs"
          (is (= {:task_type_id ":task-type/cve-investigation"
                  :task_type_version 1}
                 (select-keys (support/query-one db-path
                                                 "SELECT task_type_id, task_type_version FROM tasks WHERE task_id = ?"
                                                 [task-id])
                              [:task_type_id :task_type_version])))
          (is (= {:run_fsm_id ":run-fsm/cve-worker"
                  :run_fsm_version 2
                  :runtime_profile_id ":runtime-profile/mock-worker"
                  :runtime_profile_version 1}
                 (select-keys (support/query-one db-path
                                                 (str "SELECT run_fsm_id, run_fsm_version, runtime_profile_id, runtime_profile_version "
                                                      "FROM runs WHERE run_id = ?")
                                                 [run-id])
                              [:run_fsm_id :run_fsm_version :runtime_profile_id :runtime_profile_version])))
          (is (= {:artifact_contract_id ":artifact-contract/cve-investigation"
                  :artifact_contract_version 1}
                 (select-keys (support/query-one db-path
                                                 "SELECT artifact_contract_id, artifact_contract_version FROM artifacts WHERE run_id = ?"
                                                 [run-id])
                              [:artifact_contract_id :artifact_contract_version])))
          (is (= {:validator_id ":validator/cve-bundle"
                  :validator_version 1
                  :status ":assessment/accepted"}
                 (select-keys (support/query-one db-path
                                                 "SELECT validator_id, validator_version, status FROM assessments WHERE run_id = ?"
                                                 [run-id])
                              [:validator_id :validator_version :status])))
          (is (= {:disposition_type ":disposition/accepted"}
                 (select-keys (support/query-one db-path
                                                 "SELECT disposition_type FROM dispositions WHERE run_id = ?"
                                                 [run-id])
                              [:disposition_type]))))
        (testing "re-running scheduler once leaves the converged happy path unchanged"
          (let [step-result (scheduler/run-scheduler-step db-path)
                task-after (scheduler/inspect-task! db-path task-id)
                run-after (scheduler/inspect-run! db-path run-id)]
            (is (empty? (:created-runs step-result)))
            (is (= :task.state/completed (:task/state task-after)))
            (is (= :run.state/finalized (:run/state run-after)))
            (is (= 9 (:run/event-count run-after)))
            (is (= (:run/last-heartbeat run-view)
                   (:run/last-heartbeat run-after)))))))))

(deftest demo-retry-path-rejects-the-artifact-and-records-the-current-decision
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [{:keys [task run artifact-root assessment disposition scheduler-steps]}
            (scheduler/demo-retry-path! db-path)
            run-view (scheduler/inspect-run! db-path (:run/id run))]
        (is (= 5 scheduler-steps))
        (is (= :task.state/retryable-failed (:task/state task)))
        (is (= :run.state/retryable-failed (:run/state run)))
        (is (= :assessment/rejected (:assessment/outcome assessment)))
        (is (= :disposition/rejected (:disposition/action disposition)))
        (is (str/includes? (:assessment/notes assessment) "notes.md"))
        (is (= artifact-root
               (:run/artifact-root run-view)))
        (is (= 1
               (:item_count (support/query-one db-path
                                               (str "SELECT COUNT(*) AS item_count "
                                                    "FROM assessments WHERE run_id = ? AND assessment_key = ?")
                                               [(:run/id run) "validation/current"]))))
        (is (= 1
               (:item_count (support/query-one db-path
                                               (str "SELECT COUNT(*) AS item_count "
                                                    "FROM dispositions WHERE run_id = ? AND disposition_key = ?")
                                               [(:run/id run) "decision/current"]))))))))

(deftest demo-commands-converge-even-when-earlier-queued-mock-work-exists
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (support/enqueue-demo-task! db-path)
      (let [{happy-task :task
             happy-run :run
             happy-steps :scheduler-steps} (scheduler/demo-happy-path! db-path)
            _ (support/enqueue-demo-task! db-path)
            {retry-task :task
             retry-run :run
             retry-steps :scheduler-steps} (scheduler/demo-retry-path! db-path)]
        (is (>= happy-steps 5))
        (is (>= retry-steps 5))
        (is (= :task.state/completed (:task/state happy-task)))
        (is (= :run.state/finalized (:run/state happy-run)))
        (is (= :task.state/retryable-failed (:task/state retry-task)))
        (is (= :run.state/retryable-failed (:run/state retry-run)))))))

(deftest demo-happy-path-isolated-from-shared-queue-failures
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (let [original-runtime-adapter runtime.registry/runtime-adapter]
        (support/enqueue-codex-task! db-path)
        (with-redefs [runtime.registry/runtime-adapter (fn [adapter-id]
                                                         (if (= adapter-id :runtime.adapter/codex)
                                                           (throw (ex-info (str "Unsupported runtime adapter " adapter-id)
                                                                           {:adapter-id adapter-id}))
                                                           (original-runtime-adapter adapter-id)))]
          (let [{:keys [task run scheduler-steps]} (scheduler/demo-happy-path! db-path)]
            (is (= 5 scheduler-steps))
            (is (= :task.state/completed (:task/state task)))
            (is (= :run.state/finalized (:run/state run)))))))))

(deftest demo-happy-path-runs-through-public-scheduler-step
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        scheduler-calls (atom 0)
        original-run-scheduler-step @#'scheduler/run-scheduler-step]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (with-redefs [scheduler/run-scheduler-step (fn [db-path*]
                                                   (swap! scheduler-calls inc)
                                                   (original-run-scheduler-step db-path*))]
        (let [{:keys [task run scheduler-steps]} (scheduler/demo-happy-path! db-path)]
          (is (= 5 scheduler-steps))
          (is (= 5 @scheduler-calls))
          (is (= :task.state/completed (:task/state task)))
          (is (= :run.state/finalized (:run/state run))))))))

(deftest codex-smoke-step-budget-comes-from-the-runtime-profile-timeout
  (let [defs-repo (defs.loader/filesystem-definition-repository)
        runtime-profile (defs.protocol/find-runtime-profile defs-repo
                                                            :runtime-profile/codex-worker
                                                            1)]
    (is (> (scheduler.dev/codex-smoke-max-steps runtime-profile)
           300))
    (is (= 18300
           (scheduler.dev/codex-smoke-max-steps runtime-profile)))))
