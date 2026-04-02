(ns meta-flow.control.fsm-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.control.fsm :as fsm]))

(def task-fsm
  {:task-fsm/transitions [{:transition/from :task.state/queued
                           :transition/event :event/task-started
                           :transition/to :task.state/running}
                          {:transition/from :task.state/running
                           :transition/event :event/task-finished
                           :transition/to :task.state/completed}]})

(def run-fsm
  {:run-fsm/transitions [{:transition/from :run.state/created
                          :transition/event :event/run-dispatched
                          :transition/to :run.state/dispatched}]})

(deftest transition-lookup-works-for-task-and-run-fsms
  (is (= :task.state/running
         (fsm/transition-to task-fsm :task.state/queued :event/task-started)))
  (is (= :run.state/dispatched
         (fsm/transition-to run-fsm :run.state/created :event/run-dispatched)))
  (is (nil? (fsm/transition-to task-fsm :task.state/queued :event/unknown))))

(deftest event-allowed-and-ensure-transition-cover-valid-and-invalid-paths
  (is (true? (fsm/event-allowed? task-fsm :task.state/queued :event/task-started)))
  (is (false? (fsm/event-allowed? task-fsm :task.state/queued :event/task-finished)))
  (is (= :task.state/running
         (fsm/ensure-transition! task-fsm :task "task-1" :task.state/queued :event/task-started)))
  (let [error (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                    #"Invalid task transition"
                                    (fsm/ensure-transition! task-fsm
                                                            :task
                                                            "task-1"
                                                            :task.state/queued
                                                            :event/task-finished)))]
    (is (= {:entity-kind :task
            :entity-id "task-1"
            :state :task.state/queued
            :event :event/task-finished}
           (select-keys (ex-data error) [:entity-kind :entity-id :state :event])))))

(deftest apply-task-and-run-event-return-transition-maps-only-when-allowed
  (testing "task transitions are derived from :task/state"
    (is (= {:transition/from :task.state/queued
            :transition/to :task.state/running}
           (fsm/apply-task-event task-fsm
                                 {:task/id "task-1"
                                  :task/state :task.state/queued}
                                 :event/task-started)))
    (is (nil? (fsm/apply-task-event task-fsm
                                    {:task/id "task-1"
                                     :task/state :task.state/completed}
                                    :event/task-started))))
  (testing "run transitions are derived from :run/state"
    (is (= {:transition/from :run.state/created
            :transition/to :run.state/dispatched}
           (fsm/apply-run-event run-fsm
                                {:run/id "run-1"
                                 :run/state :run.state/created}
                                :event/run-dispatched)))
    (is (nil? (fsm/apply-run-event run-fsm
                                   {:run/id "run-1"
                                    :run/state :run.state/finalized}
                                   :event/run-dispatched)))))
