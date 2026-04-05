(ns meta-flow.scheduler.runtime.run-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.scheduler.runtime.run :as scheduler.run]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn- scripted-return
  [values]
  (let [remaining (atom (vec values))]
    (fn [& _]
      (let [value (first @remaining)]
        (swap! remaining #(if (> (count %) 1)
                            (subvec % 1)
                            %))
        value))))

(deftest max-run-steps-respects-the-scheduler-run-poll-interval
  (is (= 915
         (scheduler.run/max-run-steps
          {:runtime-profile/worker-timeout-seconds 1800})))
  (is (= 1815
         (scheduler.run/max-run-steps
          {:runtime-profile/worker-timeout-seconds 3600}))))

(deftest run-task-until-complete-throws-when-task-is-missing
  (with-redefs [store.sqlite/sqlite-state-store (fn [_] :store)
                defs.loader/filesystem-definition-repository (fn [] :defs-repo)
                store.protocol/find-task (fn [_ _] nil)]
    (let [exception (try
                      (scheduler.run/run-task-until-complete! "runtime.sqlite3" "task-missing" (fn [_]))
                      (catch clojure.lang.ExceptionInfo throwable
                        throwable))]
      (is (= "Task not found: task-missing" (.getMessage exception)))
      (is (= {:task-id "task-missing"} (ex-data exception))))))

(deftest run-task-until-complete-returns-immediately-for-terminal-tasks
  (let [task {:task/id "task-complete"
              :task/state :task.state/completed
              :task/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                         :definition/version 1}}
        run {:run/id "run-complete"
             :run/state :run.state/finalized}]
    (with-redefs [store.sqlite/sqlite-state-store (fn [_] :store)
                  defs.loader/filesystem-definition-repository (fn [] :defs-repo)
                  store.protocol/find-task (fn [_ _] task)
                  defs.protocol/find-runtime-profile (fn [& _]
                                                       {:runtime-profile/worker-timeout-seconds 10})
                  scheduler.run/max-run-steps (constantly 5)
                  shared/latest-run (fn [_ _] run)
                  shared/run-artifact-root (fn [_ _] "/tmp/artifacts/run-complete")]
      (binding [*out* (java.io.StringWriter.)]
        (is (= {:task task
                :run run
                :artifact-root "/tmp/artifacts/run-complete"
                :scheduler-steps 0}
               (scheduler.run/run-task-until-complete!
                "runtime.sqlite3"
                "task-complete"
                (fn [_] (throw (ex-info "should not run scheduler" {}))))))))))

(deftest run-task-until-complete-advances-until-task-reaches-terminal-state
  (let [task-queued {:task/id "task-1"
                     :task/state :task.state/queued
                     :task/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                                :definition/version 1}}
        task-completed (assoc task-queued :task/state :task.state/completed)
        task-seq (scripted-return [task-queued task-queued task-completed])
        run-seq (scripted-return [{:run/id "run-1"
                                   :run/state :run.state/running
                                   :run/artifact-id "artifact-1"}
                                  {:run/id "run-1"
                                   :run/state :run.state/finalized
                                   :run/artifact-id "artifact-1"}])
        scheduler-calls (atom [])]
    (with-redefs [store.sqlite/sqlite-state-store (fn [_] :store)
                  defs.loader/filesystem-definition-repository (fn [] :defs-repo)
                  store.protocol/find-task task-seq
                  defs.protocol/find-runtime-profile (fn [& _]
                                                       {:runtime-profile/worker-timeout-seconds 10})
                  scheduler.run/max-run-steps (constantly 2)
                  scheduler.run/poll-ms 0
                  shared/latest-run run-seq
                  shared/run-artifact-root (fn [_ run]
                                             (str "/tmp/artifacts/" (:run/id run)))]
      (binding [*out* (java.io.StringWriter.)]
        (is (= {:task task-completed
                :run {:run/id "run-1"
                      :run/state :run.state/finalized
                      :run/artifact-id "artifact-1"}
                :artifact-root "/tmp/artifacts/run-1"
                :scheduler-steps 1}
               (scheduler.run/run-task-until-complete!
                "runtime.sqlite3"
                "task-1"
                (fn [db-path]
                  (swap! scheduler-calls conj db-path)))))))
    (is (= ["runtime.sqlite3"] @scheduler-calls))))

(deftest run-task-until-complete-fails-when-max-steps-is-exhausted
  (let [task-queued {:task/id "task-stuck"
                     :task/state :task.state/queued
                     :task/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                                :definition/version 1}}
        task-seq (scripted-return [task-queued task-queued])
        run-seq (scripted-return [{:run/id "run-stuck"
                                   :run/state :run.state/running}
                                  {:run/id "run-stuck"
                                   :run/state :run.state/running}])
        scheduler-calls (atom [])]
    (with-redefs [store.sqlite/sqlite-state-store (fn [_] :store)
                  defs.loader/filesystem-definition-repository (fn [] :defs-repo)
                  store.protocol/find-task task-seq
                  defs.protocol/find-runtime-profile (fn [& _]
                                                       {:runtime-profile/worker-timeout-seconds 10})
                  scheduler.run/max-run-steps (constantly 1)
                  scheduler.run/poll-ms 0
                  shared/latest-run run-seq]
      (binding [*out* (java.io.StringWriter.)]
        (let [exception (try
                          (scheduler.run/run-task-until-complete!
                           "runtime.sqlite3"
                           "task-stuck"
                           (fn [db-path]
                             (swap! scheduler-calls conj db-path)))
                          (catch clojure.lang.ExceptionInfo throwable
                            throwable))]
          (testing "the thrown ex-info retains the last observed state"
            (is (= "Task did not reach terminal state within the expected scheduler steps"
                   (.getMessage exception)))
            (is (= 1 (:scheduler-steps (ex-data exception))))
            (is (= 1 (:max-steps (ex-data exception))))
            (is (= task-queued (:task (ex-data exception))))
            (is (= {:run/id "run-stuck"
                    :run/state :run.state/running}
                   (:run (ex-data exception))))))))
    (is (= ["runtime.sqlite3"] @scheduler-calls))))
