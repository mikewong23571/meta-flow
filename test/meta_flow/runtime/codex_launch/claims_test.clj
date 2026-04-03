(ns meta-flow.runtime.codex-launch.claims-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.runtime.codex :as codex]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.process.launch :as codex.launch]
            [meta-flow.runtime.codex-launch.test-support :as launch.support]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest launch-pending-is-claimed-before-start-and-only-one-poller-launches
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (launch.support/repository-with-temp-codex-home
                    (defs.loader/filesystem-definition-repository)
                    codex-home-dir)
        adapter (codex/codex-runtime)
        store (store.sqlite/sqlite-state-store db-path)
        observed-state (atom nil)
        launch-count (atom 0)]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn ([] repository)
                                                                   ([_] repository))]
        (let [_ (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-LAUNCH-CLAIM"})
              first-step (scheduler/run-scheduler-step db-path)
              run-id (get-in first-step [:created-runs 0 :run :run/id])
              run (store.protocol/find-run store run-id)
              process-path (codex.fs/process-path run-id)]
          (with-redefs [codex.launch/build-process-builder
                        (fn [& _]
                          (swap! launch-count inc)
                          (reset! observed-state (codex.fs/read-json-file process-path))
                          (doto (ProcessBuilder. ["bash" "-lc" "sleep 0.2"])
                            (.directory (io/file "."))))]
            @(future (runtime.protocol/poll-run! adapter
                                                 {:store store
                                                  :repository repository
                                                  :db-path db-path}
                                                 run
                                                 "2026-04-03T00:00:01Z"))
            @(future (runtime.protocol/poll-run! adapter
                                                 {:store store
                                                  :repository repository
                                                  :db-path db-path}
                                                 run
                                                 "2026-04-03T00:00:01Z")))
          (testing "the launcher claims the process handle before building the worker command"
            (is (= "launching" (:status @observed-state)))
            (is (= "2026-04-03T00:00:01Z" (:launchClaimedAt @observed-state)))
            (is (string? (:launchClaimToken @observed-state))))
          (testing "overlapping polls only launch one external worker"
            (is (= 1 @launch-count))
            (is (contains? #{"dispatched" "exited" "completed"}
                           (:status (codex.fs/read-json-file process-path))))))))))

(deftest stale-launching-claims-are-recovered-and-relaunched
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (launch.support/repository-with-temp-codex-home
                    (defs.loader/filesystem-definition-repository)
                    codex-home-dir)
        adapter (codex/codex-runtime)
        store (store.sqlite/sqlite-state-store db-path)
        observed-state (atom nil)
        launch-count (atom 0)]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn ([] repository)
                                                                   ([_] repository))]
        (let [_ (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-STALE-LAUNCH"})
              first-step (scheduler/run-scheduler-step db-path)
              run-id (get-in first-step [:created-runs 0 :run :run/id])
              run (store.protocol/find-run store run-id)
              process-path (codex.fs/process-path run-id)]
          (codex.fs/write-json-file! process-path
                                     (assoc (codex.fs/read-json-file process-path)
                                            :status "launching"
                                            :launchClaimedAt "2026-04-03T01:14:00Z"
                                            :launchClaimToken "stale-claim"))
          (with-redefs [codex.launch/build-process-builder
                        (fn [& _]
                          (swap! launch-count inc)
                          (reset! observed-state (codex.fs/read-json-file process-path))
                          (doto (ProcessBuilder. ["bash" "-lc" "sleep 0.2"])
                            (.directory (io/file "."))))]
            (is (= []
                   (runtime.protocol/poll-run! adapter
                                               {:store store
                                                :repository repository
                                                :db-path db-path}
                                               run
                                               "2026-04-03T01:15:00Z"))))
          (testing "stale launching claims are reclaimed before relaunch"
            (is (= 1 @launch-count))
            (is (= "launching" (:status @observed-state)))
            (is (= "2026-04-03T01:15:00Z" (:launchClaimedAt @observed-state)))
            (is (not= "stale-claim" (:launchClaimToken @observed-state))))
          (testing "the recovered run leaves launch limbo after the relaunch"
            (is (contains? #{"dispatched" "exited" "completed"}
                           (:status (codex.fs/read-json-file process-path))))))))))
