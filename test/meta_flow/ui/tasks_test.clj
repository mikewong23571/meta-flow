(ns meta-flow.ui.tasks-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.shared :as shared]
            [meta-flow.ui.tasks :as ui.tasks]))

(deftest list-tasks-uses-default-db-path-and-limit
  (let [calls (atom [])]
    (with-redefs [db/default-db-path "default.sqlite3"
                  defs.loader/filesystem-definition-repository (fn [] :defs-repo)
                  sql/with-connection (fn [db-path f]
                                        (swap! calls conj [:db-path db-path])
                                        (f :connection))
                  sql/query-rows (fn [_ _ params]
                                   (swap! calls conj [:limit (first params)])
                                   [])]
      (is (= [] (ui.tasks/list-tasks)))
      (is (= [] (ui.tasks/list-tasks "custom.sqlite3"))))
    (is (= [[:db-path "default.sqlite3"]
            [:limit 200]
            [:db-path "custom.sqlite3"]
            [:limit 200]]
           @calls))))

(deftest list-tasks-builds-summaries-and-latest-run-metadata
  (let [task-rows [{:task_edn :repo-task
                    :run_edn :repo-run}
                   {:task_edn :fallback-task
                    :run_edn nil}]]
    (with-redefs [defs.loader/filesystem-definition-repository (fn [] :defs-repo)
                  sql/with-connection (fn [db-path f]
                                        (is (= "tasks.sqlite3" db-path))
                                        (f :connection))
                  sql/query-rows (fn [_ _ params]
                                   (is (= [5] params))
                                   task-rows)
                  shared/parse-edn-column (fn [row column]
                                            (is (= :task_edn column))
                                            (case (:task_edn row)
                                              :repo-task {:task/id "task-repo"
                                                          :task/state :task.state/running
                                                          :task/work-key "repo-arch-key"
                                                          :task/task-type-ref {:definition/id :task-type/repo-arch-investigation
                                                                               :definition/version 1}
                                                          :task/input {:input/repo-url "github.com/acme/project"
                                                                       :input/notify-email "ops@example.com"}
                                                          :task/created-at "2026-04-01T00:00:00Z"
                                                          :task/updated-at "2026-04-01T00:03:00Z"}
                                              :fallback-task {:task/id "task-fallback"
                                                              :task/state :task.state/queued
                                                              :task/work-key "fallback-key"
                                                              :task/task-type-ref {:definition/id :task-type/unknown
                                                                                   :definition/version 9}
                                                              :task/created-at "2026-04-01T00:01:00Z"
                                                              :task/updated-at "2026-04-01T00:02:00Z"}))
                  defs.protocol/find-task-type-def (fn [_ task-type-id version]
                                                     (case [task-type-id version]
                                                       [:task-type/repo-arch-investigation 1]
                                                       {:task-type/name "Repo architecture investigation"}
                                                       nil))
                  sql/text->edn (fn [run-token]
                                  (is (= :repo-run run-token))
                                  {:run/id "run-repo-1"
                                   :run/state :run.state/running
                                   :run/attempt 2
                                   :run/updated-at "2026-04-01T00:04:00Z"})]
      (let [items (ui.tasks/list-tasks "tasks.sqlite3" 5)
            repo-item (first items)
            fallback-item (second items)]
        (testing "repo architecture tasks use task-specific summary fields"
          (is (= {:task/id "task-repo"
                  :task/state :task.state/running
                  :task/work-key "repo-arch-key"
                  :task/type-ref {:definition/id :task-type/repo-arch-investigation
                                  :definition/version 1}
                  :task/type-id :task-type/repo-arch-investigation
                  :task/type-name "Repo architecture investigation"
                  :task/summary {:primary "github.com/acme/project"
                                 :secondary "ops@example.com"}
                  :task/created-at "2026-04-01T00:00:00Z"
                  :task/updated-at "2026-04-01T00:03:00Z"
                  :latest-run {:run/id "run-repo-1"
                               :run/state :run.state/running
                               :run/attempt 2
                               :run/updated-at "2026-04-01T00:04:00Z"}}
                 repo-item)))
        (testing "fallback type names and summaries degrade gracefully"
          (is (= {:task/id "task-fallback"
                  :task/state :task.state/queued
                  :task/work-key "fallback-key"
                  :task/type-ref {:definition/id :task-type/unknown
                                  :definition/version 9}
                  :task/type-id :task-type/unknown
                  :task/type-name ":task-type/unknown"
                  :task/summary {:primary "fallback-key"
                                 :secondary ":task-type/unknown"}
                  :task/created-at "2026-04-01T00:01:00Z"
                  :task/updated-at "2026-04-01T00:02:00Z"
                  :latest-run nil}
                 fallback-item)))))))
