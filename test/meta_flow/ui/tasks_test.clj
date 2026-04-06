(ns meta-flow.ui.tasks-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]
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
                    :run_edn nil}]
        defs-repo :defs-repo]
    (with-redefs [sql/with-connection (fn [db-path f]
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
                  defs.protocol/find-task-type-def (fn [repo task-type-id version]
                                                     (is (= defs-repo repo))
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
      (let [items (ui.tasks/list-tasks defs-repo "tasks.sqlite3" 5)
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

(deftest create-task-rejects-missing-required-input-fields
  (let [defs-repo :defs-repo
        task-type {:task-type/id :task-type/repo-arch-investigation
                   :task-type/version 1
                   :task-type/task-fsm-ref {:definition/id :task-fsm/default
                                            :definition/version 3}
                   :task-type/run-fsm-ref {:definition/id :run-fsm/default
                                           :definition/version 2}
                   :task-type/runtime-profile-ref {:definition/id :runtime-profile/codex-repo-arch
                                                   :definition/version 1}
                   :task-type/artifact-contract-ref {:definition/id :artifact-contract/repo-arch
                                                     :definition/version 1}
                   :task-type/validator-ref {:definition/id :validator/repo-arch
                                             :definition/version 1}
                   :task-type/resource-policy-ref {:definition/id :resource-policy/serial-repo-arch
                                                   :definition/version 1}
                   :task-type/input-schema [{:field/id :input/repo-url
                                             :field/label "Repository URL"
                                             :field/type :field.type/text
                                             :field/required? true}
                                            {:field/id :input/notify-email
                                             :field/label "Notification Email"
                                             :field/type :field.type/email
                                             :field/required? true}]
                   :task-type/work-key-expr {:work-key/type :work-key.type/tuple
                                             :work-key/tag :repo-arch
                                             :work-key/fields [:input/repo-url :input/notify-email]}}
        bad-inputs [{} {:input/repo-url "" :input/notify-email "   "}]]
    (doseq [input bad-inputs]
      (with-redefs [defs.protocol/find-task-type-def (fn [repo task-type-id version]
                                                       (is (= defs-repo repo))
                                                       (is (= [:task-type/repo-arch-investigation 1]
                                                              [task-type-id version]))
                                                       task-type)
                    store.sqlite/sqlite-state-store (fn [_]
                                                      (throw (ex-info "store should not be reached" {})))
                    store.protocol/enqueue-task! (fn [_ _]
                                                   (throw (ex-info "enqueue should not be reached" {})))]
        (let [exception (try
                          (ui.tasks/create-task! defs-repo
                                                 "tasks.sqlite3"
                                                 :task-type/repo-arch-investigation
                                                 1
                                                 input)
                          nil
                          (catch clojure.lang.ExceptionInfo throwable
                            throwable))]
          (is exception)
          (is (= "Required task input fields cannot be blank: :input/repo-url, :input/notify-email"
                 (ex-message exception)))
          (is (= {:task-type-id :task-type/repo-arch-investigation
                  :missing-fields [{:field/id :input/repo-url
                                    :field/label "Repository URL"}
                                   {:field/id :input/notify-email
                                    :field/label "Notification Email"}]
                  :input input}
                 (ex-data exception))))))))
