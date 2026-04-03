(ns meta-flow.runtime.codex-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.control.events :as events]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.runtime.codex :as codex]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.home :as codex.home]
            [meta-flow.runtime.codex.process :as codex.process]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]
            [meta-flow.scheduler.support.test-support :as support]))

(defn- codex-profile-with-temp-home
  [repository codex-home-dir]
  (assoc (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1)
         :runtime-profile/codex-home-root codex-home-dir))

(defn- repository-with-temp-codex-home
  [repository codex-home-dir]
  (reify defs.protocol/DefinitionRepository
    (load-workflow-defs [_]
      (defs.protocol/load-workflow-defs repository))
    (find-task-type-def [_ task-type-id version]
      (defs.protocol/find-task-type-def repository task-type-id version))
    (find-run-fsm-def [_ run-fsm-id version]
      (defs.protocol/find-run-fsm-def repository run-fsm-id version))
    (find-task-fsm-def [_ task-fsm-id version]
      (defs.protocol/find-task-fsm-def repository task-fsm-id version))
    (find-artifact-contract [_ contract-id version]
      (defs.protocol/find-artifact-contract repository contract-id version))
    (find-validator-def [_ validator-id version]
      (defs.protocol/find-validator-def repository validator-id version))
    (find-runtime-profile [_ runtime-profile-id version]
      (cond-> (defs.protocol/find-runtime-profile repository runtime-profile-id version)
        (= runtime-profile-id :runtime-profile/codex-worker)
        (assoc :runtime-profile/codex-home-root codex-home-dir)))
    (find-resource-policy [_ resource-policy-id version]
      (defs.protocol/find-resource-policy repository resource-policy-id version))))

(def ^:private max-terminal-codex-run-attempts
  300)

(defn- wait-for-terminal-codex-run!
  [db-path task-id]
  (loop [attempt 0]
    (let [result (try
                   {:status :ok
                    :task (scheduler/inspect-task! db-path task-id)
                    :run (some->> (:run_id (support/query-one db-path
                                                              (str "SELECT run_id FROM runs "
                                                                   "WHERE task_id = ? ORDER BY attempt DESC LIMIT 1")
                                                              [task-id]))
                                  (scheduler/inspect-run! db-path))}
                   (catch org.sqlite.SQLiteException ex
                     (if (re-find #"database is locked" (.getMessage ex))
                       {:status :busy}
                       (throw ex))))]
      (cond
        (= :busy (:status result))
        (do
          (Thread/sleep 100)
          (recur attempt))

        (and (:run result)
             (= :task.state/completed (get-in result [:task :task/state]))
             (= :run.state/finalized (get-in result [:run :run/state])))
        {:task (:task result)
         :run (:run result)}

        (>= attempt max-terminal-codex-run-attempts)
        (throw (ex-info "Codex managed worker did not converge"
                        {:task (:task result)
                         :run (:run result)
                         :attempt attempt}))

        :else
        (do
          (Thread/sleep 100)
          (scheduler/run-scheduler-step db-path)
          (recur (inc attempt)))))))

(deftest codex-home-install-is-idempotent-and-preserves-existing-files
  (let [{:keys [codex-home-dir]} (support/temp-system)
        repository (defs.loader/filesystem-definition-repository)
        runtime-profile (codex-profile-with-temp-home repository codex-home-dir)
        first-install (codex.home/install-home! runtime-profile)
        readme-path (str codex-home-dir "/README.md")
        _ (spit readme-path "user customized README\n")
        second-install (codex.home/install-home! runtime-profile)]
    (testing "first install materializes bundled templates"
      (is (= (.getCanonicalPath (io/file codex-home-dir))
             (:codex-home/root first-install)))
      (is (= 2 (count (:codex-home/installed-paths first-install))))
      (is (.exists (io/file codex-home-dir "README.md")))
      (is (.exists (io/file codex-home-dir "config.edn"))))
    (testing "reinstall preserves existing files instead of overwriting them"
      (is (= "user customized README\n"
             (slurp readme-path)))
      (is (empty? (:codex-home/installed-paths second-install)))
      (is (= 2 (count (:codex-home/skipped-paths second-install)))))))

(deftest prepare-run-writes-concrete-codex-worker-snapshot
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (repository-with-temp-codex-home (defs.loader/filesystem-definition-repository)
                                                    codex-home-dir)
        adapter (codex/codex-runtime)
        task (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-SNAPSHOT"})
        run {:run/id "run-codex-snapshot"
             :run/task-id (:task/id task)
             :run/attempt 1
             :run/run-fsm-ref (:task/run-fsm-ref task)
             :run/runtime-profile-ref (:task/runtime-profile-ref task)
             :run/state :run.state/leased
             :run/created-at "2026-04-03T00:00:00Z"
             :run/updated-at "2026-04-03T00:00:00Z"}]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn
                                                                   ([] repository)
                                                                   ([_] repository))]
        (let [ctx {:db-path db-path
                   :store (store.sqlite/sqlite-state-store db-path)
                   :repository repository
                   :now "2026-04-03T00:00:00Z"}
              _ (runtime.protocol/prepare-run! adapter ctx task run)
              workdir (str runs-dir "/" (:run/id run))
              definitions (edn/read-string (slurp (str workdir "/definitions.edn")))
              runtime-profile (edn/read-string (slurp (str workdir "/runtime-profile.edn")))
              artifact-contract (edn/read-string (slurp (str workdir "/artifact-contract.edn")))
              worker-prompt (slurp (str workdir "/worker-prompt.md"))]
          (testing "prepare-run writes concrete, pinned worker inputs"
            (is (= :task-type/cve-investigation
                   (get-in definitions [:task-type :task-type/id])))
            (is (= :artifact-contract/cve-investigation
                   (get-in artifact-contract [:artifact-contract/id])))
            (is (= (.getCanonicalPath (io/file codex-home-dir))
                   (:runtime-profile/codex-home-root runtime-profile)))
            (is (= (.getCanonicalPath (io/file "script/worker_api.bb"))
                   (:runtime-profile/helper-script-path runtime-profile)))
            (is (= (.getCanonicalPath (io/file workdir "worker-prompt.md"))
                   (:runtime-profile/worker-prompt-path runtime-profile)))
            (is (.exists (io/file workdir "task.edn")))
            (is (.exists (io/file workdir "run.edn")))
            (is (.exists (io/file workdir "worker-prompt.md")))
            (is (.contains worker-prompt (str "Artifact root: `" (.getCanonicalPath (io/file artifacts-dir
                                                                                             (:task/id task)
                                                                                             (:run/id run)))
                                              "`")))
            (is (.contains worker-prompt (str "Run log path: `"
                                              (.getCanonicalPath (io/file artifacts-dir
                                                                          (:task/id task)
                                                                          (:run/id run)
                                                                          "run.log"))
                                              "`")))))))))

(deftest scheduler-codex-managed-worker-completes-through-the-existing-control-plane
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (repository-with-temp-codex-home (defs.loader/filesystem-definition-repository)
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
              {task-view :task run-view :run} (wait-for-terminal-codex-run! db-path (:task/id task))
              event-types (mapv :event/type
                                (store.protocol/list-run-events (store.sqlite/sqlite-state-store db-path)
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
            (is (str/starts-with? (:artifactRoot process-state)
                                  (str artifacts-dir "/")))
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

(deftest cancel-run-updates-process-handle-with-consistent-keys
  (let [{:keys [runs-dir]} (support/temp-system)
        adapter (codex/codex-runtime)
        run {:run/id "run-cancel-test"}]
    (binding [codex.fs/*run-root-dir* runs-dir]
      (codex.fs/ensure-directory! (codex.fs/run-workdir (:run/id run)))
      (codex.fs/write-json-file! (codex.fs/process-path (:run/id run))
                                 {:runId (:run/id run)
                                  :status "prepared"})
      (is (= {:status :cancel-requested}
             (runtime.protocol/cancel-run! adapter {} run {:reason :test/cancel})))
      (is (= {:runId (:run/id run)
              :status "cancel-requested"
              :cancelReason "{:reason :test/cancel}"}
             (codex.fs/read-json-file (codex.fs/process-path (:run/id run)))))
      (is (= 1
             (count (re-seq #"\"status\"" (slurp (codex.fs/process-path (:run/id run))))))))))

(deftest poll-run-prefers-the-persisted-process-path-from-the-execution-handle
  (let [{:keys [runs-dir]} (support/temp-system)
        adapter (codex/codex-runtime)
        alt-dir (str runs-dir "/external")
        alt-path (str alt-dir "/process.json")
        run-id "run-persisted-handle-test"
        run {:run/id run-id
             :run/execution-handle {:runtime-run/process-path alt-path}}]
    (binding [codex.fs/*run-root-dir* runs-dir]
      (codex.fs/ensure-directory! (codex.fs/run-workdir run-id))
      (codex.fs/write-text-file! (codex.fs/process-path run-id) "{not-json")
      (codex.fs/ensure-directory! alt-dir)
      (codex.fs/write-json-file! alt-path {:runId run-id
                                           :status "prepared"})
      (is (= []
             (runtime.protocol/poll-run! adapter {} run "2026-04-03T00:00:00Z"))))))

(deftest launch-failures-are-persisted-without-recrashing-later-polls
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (repository-with-temp-codex-home (defs.loader/filesystem-definition-repository)
                                                    codex-home-dir)
        adapter (codex/codex-runtime)
        store (store.sqlite/sqlite-state-store db-path)]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn
                                                                   ([] repository)
                                                                   ([_] repository))]
        (let [task (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-LAUNCH-FAIL"})
              first-step (scheduler/run-scheduler-step db-path)
              run-id (get-in first-step [:created-runs 0 :run :run/id])
              run (store.protocol/find-run store run-id)
              process-path (codex.fs/process-path run-id)
              poll-result (with-redefs [codex.process/build-process-builder (fn [& _]
                                                                              (throw (ex-info "synthetic launch failure"
                                                                                              {:error/type :test/launch-failure
                                                                                               :run-id run-id})))]
                            (runtime.protocol/poll-run! adapter
                                                        {:store store
                                                         :repository repository
                                                         :db-path db-path}
                                                        run
                                                        "2026-04-03T00:00:01Z"))
              failed-state (codex.fs/read-json-file process-path)]
          (testing "launch exceptions are persisted as a failed launch instead of escaping"
            (is (= [] poll-result))
            (is (= "launch-failed" (:status failed-state)))
            (is (= 1 (:exitCode failed-state)))
            (is (= "2026-04-03T00:00:01Z" (:launchFailedAt failed-state)))
            (is (= "synthetic launch failure"
                   (get-in failed-state [:launchError :message]))))
          (testing "later polls and scheduler passes do not retry the same launch crash"
            (is (= []
                   (runtime.protocol/poll-run! adapter
                                               {:store store
                                                :repository repository
                                                :db-path db-path}
                                               run
                                               "2026-04-03T00:00:02Z")))
            (is (empty? (:task-errors (scheduler/run-scheduler-step db-path))))
            (is (= :run.state/dispatched
                   (:run/state (store.protocol/find-run store run-id))))
            (is (= :task.state/leased
                   (:task/state (store.protocol/find-task store (:task/id task)))))))))))
