(ns meta-flow.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [meta-flow.cli :as cli]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.scheduler :as scheduler]))

(deftest inspect-commands-do-not-bootstrap-the-system
  (let [bootstrap-calls (atom [])]
    (with-redefs [db/default-db-path "var/test.sqlite3"
                  defs.loader/filesystem-definition-repository (fn []
                                                                 (swap! bootstrap-calls conj :defs)
                                                                 (throw (ex-info "definitions should not load during inspect" {})))
                  db/initialize-database! (fn
                                            ([] (swap! bootstrap-calls conj :db-init)
                                                (throw (ex-info "db init should not run during inspect" {})))
                                            ([_] (swap! bootstrap-calls conj :db-init)
                                                 (throw (ex-info "db init should not run during inspect" {}))))
                  db/ensure-runtime-directories! (fn []
                                                   (swap! bootstrap-calls conj :runtime-dirs)
                                                   (throw (ex-info "runtime dirs should not be created during inspect" {})))
                  scheduler/inspect-task! (fn [_ task-id]
                                            {:task/id task-id
                                             :task/state :task.state/completed})
                  scheduler/inspect-run! (fn [_ run-id]
                                           {:run/id run-id
                                            :run/state :run.state/finalized})
                  scheduler/inspect-collection! (fn [_]
                                                  {:collection/dispatch {:dispatch/paused? false}
                                                   :collection/resource-policy-ref {:definition/id :resource-policy/default
                                                                                    :definition/version 1}})]
      (let [task-output (with-out-str
                          (cli/dispatch-command! ["inspect" "task" "--task-id" "task-123"]))
            run-output (with-out-str
                         (cli/dispatch-command! ["inspect" "run" "--run-id" "run-123"]))
            collection-output (with-out-str
                                (cli/dispatch-command! ["inspect" "collection"]))]
        (is (empty? @bootstrap-calls))
        (is (str/includes? task-output "task-123"))
        (is (str/includes? run-output "run-123"))
        (is (str/includes? collection-output ":resource-policy/default"))))))
