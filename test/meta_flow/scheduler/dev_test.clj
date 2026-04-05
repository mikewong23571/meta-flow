(ns meta-flow.scheduler.dev-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.scheduler.dev :as scheduler.dev]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest enqueue-repo-arch-reuses-only-when-repo-and-recipient-match
  (let [{:keys [db-path]} (support/temp-system)
        store (store.sqlite/sqlite-state-store db-path)
        first-result (scheduler.dev/enqueue-repo-arch-task! db-path
                                                            {:repo-url "github.com/acme/project"
                                                             :notify-email "first@example.com"})
        same-result (scheduler.dev/enqueue-repo-arch-task! db-path
                                                           {:repo-url "github.com/acme/project"
                                                            :notify-email "first@example.com"})
        different-email-result (scheduler.dev/enqueue-repo-arch-task! db-path
                                                                      {:repo-url "github.com/acme/project"
                                                                       :notify-email "second@example.com"})
        first-task (:task first-result)
        same-task (:task same-result)
        second-task (:task different-email-result)]
    (testing "the exact same request reuses the existing queued task"
      (is (false? (:reused? first-result)))
      (is (true? (:reused? same-result)))
      (is (= (:task/id first-task)
             (:task/id same-task))))
    (testing "a different recipient produces a distinct task with updated input"
      (is (false? (:reused? different-email-result)))
      (is (not= (:task/id first-task)
                (:task/id second-task)))
      (is (= "second@example.com"
             (get-in second-task [:task/input :input/notify-email]))))
    (testing "both tasks remain queryable so follow-up investigations can target both recipients"
      (is (= "first@example.com"
             (get-in (store.protocol/find-task store (:task/id first-task))
                     [:task/input :input/notify-email])))
      (is (= "second@example.com"
             (get-in (store.protocol/find-task store (:task/id second-task))
                     [:task/input :input/notify-email]))))))
