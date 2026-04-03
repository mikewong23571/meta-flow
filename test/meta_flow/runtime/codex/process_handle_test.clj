(ns meta-flow.runtime.codex.process-handle-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.runtime.codex :as codex]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.process.launch :as codex.launch]
            [meta-flow.runtime.codex.test-support :as codex.support]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

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
        repository (codex.support/repository-with-temp-codex-home
                    (defs.loader/filesystem-definition-repository)
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
              poll-result (with-redefs [codex.launch/build-process-builder
                                        (fn [& _]
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
