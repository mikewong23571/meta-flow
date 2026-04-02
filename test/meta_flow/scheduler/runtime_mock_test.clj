(ns meta-flow.scheduler.runtime-mock-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.control.events :as events]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.runtime.mock :as runtime.mock]
            [meta-flow.runtime.mock.fs :as runtime.mock.fs]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.scheduler.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest mock-runtime-prepares-each-run-once
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        _ (support/enqueue-demo-task! db-path)
        metadata-writes (atom [])
        original-write-edn! @#'meta-flow.runtime.mock.fs/write-edn-file!]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (with-redefs-fn {#'meta-flow.runtime.mock.fs/write-edn-file!
                       (fn [path value]
                         (swap! metadata-writes conj path)
                         (original-write-edn! path value))}
        (fn []
          (let [step-result (scheduler/run-scheduler-step db-path)
                expected-prefix (str runs-dir "/")]
            (is (= 1 (count (:created-runs step-result))))
            (is (empty? (:task-errors step-result)))
            (is (= 6
                   (count (filter #(str/starts-with? % expected-prefix)
                                  @metadata-writes))))
            (is (= #{"definitions.edn" "task.edn" "run.edn" "runtime-profile.edn" "runtime-state.edn"}
                   (set (map #(last (str/split % #"/"))
                             (filter #(str/starts-with? % expected-prefix)
                                     @metadata-writes)))))))))))

(deftest mock-runtime-polls-progressively-and-can-be-cancelled
  (let [{:keys [db-path artifacts-dir runs-dir]} (support/temp-system)
        store (store.sqlite/sqlite-state-store db-path)
        adapter (runtime.mock/mock-runtime)
        task (support/enqueue-demo-task! db-path)
        now "2026-04-01T00:00:00Z"
        {:keys [run]} (store.protocol/create-run! store
                                                  task
                                                  {:run/id "run-runtime-test"
                                                   :run/attempt 1
                                                   :run/run-fsm-ref (:task/run-fsm-ref task)
                                                   :run/runtime-profile-ref (:task/runtime-profile-ref task)
                                                   :run/state :run.state/leased
                                                   :run/created-at now
                                                   :run/updated-at now}
                                                  {:lease/id "lease-runtime-test"
                                                   :lease/run-id "run-runtime-test"
                                                   :lease/token "lease-runtime-test-token"
                                                   :lease/state :lease.state/active
                                                   :lease/expires-at "2026-04-01T00:30:00Z"
                                                   :lease/created-at now
                                                   :lease/updated-at now})
        ctx {:db-path db-path
             :store store
             :repository (defs.loader/filesystem-definition-repository)
             :now now}]
    (binding [runtime.mock.fs/*artifact-root-dir* artifacts-dir
              runtime.mock.fs/*run-root-dir* runs-dir]
      (runtime.protocol/prepare-run! adapter ctx task run)
      (runtime.protocol/dispatch-run! adapter ctx task run)
      (testing "poll advances the mock run in phases"
        (is (= [events/run-dispatched]
               (mapv :event/type (store.protocol/list-run-events store (:run/id run)))))
        (is (= [events/task-worker-started
                events/run-worker-started]
               (mapv :event/type (runtime.protocol/poll-run! adapter ctx run now))))
        (is (= [events/run-worker-heartbeat]
               (mapv :event/type (runtime.protocol/poll-run! adapter ctx run now))))
        (is (= [events/run-worker-exited]
               (mapv :event/type (runtime.protocol/poll-run! adapter ctx run now))))
        (is (= [events/run-artifact-ready
                events/task-artifact-ready]
               (mapv :event/type (runtime.protocol/poll-run! adapter ctx run now))))
        (is (empty? (runtime.protocol/poll-run! adapter ctx run now))))
      (testing "cancel requests convert the next poll into an exit without artifacts"
        (let [cancel-task (support/enqueue-demo-task! db-path)
              {:keys [run]} (store.protocol/create-run! store
                                                        cancel-task
                                                        {:run/id "run-runtime-cancel"
                                                         :run/attempt 2
                                                         :run/run-fsm-ref (:task/run-fsm-ref cancel-task)
                                                         :run/runtime-profile-ref (:task/runtime-profile-ref cancel-task)
                                                         :run/state :run.state/leased
                                                         :run/created-at "2026-04-01T00:01:00Z"
                                                         :run/updated-at "2026-04-01T00:01:00Z"}
                                                        {:lease/id "lease-runtime-cancel"
                                                         :lease/run-id "run-runtime-cancel"
                                                         :lease/token "lease-runtime-cancel-token"
                                                         :lease/state :lease.state/active
                                                         :lease/expires-at "2026-04-01T00:31:00Z"
                                                         :lease/created-at "2026-04-01T00:01:00Z"
                                                         :lease/updated-at "2026-04-01T00:01:00Z"})
              cancel-ctx (assoc ctx :now "2026-04-01T00:01:00Z")]
          (runtime.protocol/prepare-run! adapter cancel-ctx cancel-task run)
          (runtime.protocol/dispatch-run! adapter cancel-ctx cancel-task run)
          (is (= {:status :cancel-requested}
                 (runtime.protocol/cancel-run! adapter cancel-ctx run {:reason :test/cancel})))
          (is (= [events/run-worker-exited]
                 (mapv :event/type (runtime.protocol/poll-run! adapter cancel-ctx run "2026-04-01T00:02:00Z"))))
          (is (empty? (runtime.protocol/poll-run! adapter cancel-ctx run "2026-04-01T00:03:00Z")))
          (is (= 1
                 (:item_count (support/query-one db-path
                                                 "SELECT COUNT(*) AS item_count FROM artifacts WHERE run_id = ?"
                                                 ["run-runtime-test"]))))
          (is (= 0
                 (:item_count (support/query-one db-path
                                                 "SELECT COUNT(*) AS item_count FROM artifacts WHERE run_id = ?"
                                                 ["run-runtime-cancel"])))))))))
