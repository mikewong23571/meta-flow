(ns meta-flow.store.sqlite.test-support
  (:require [meta-flow.db :as db]
            [meta-flow.control.projection :as projection]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn temp-db-path
  []
  (let [temp-dir (java.nio.file.Files/createTempDirectory "meta-flow-store-test"
                                                          (make-array java.nio.file.attribute.FileAttribute 0))]
    (str (.toFile temp-dir) "/meta-flow.sqlite3")))

(defn query-single-value
  [db-path sql-text]
  (with-open [connection (db/open-connection db-path)
              statement (.createStatement connection)
              result-set (.executeQuery statement sql-text)]
    (when (.next result-set)
      (.getObject result-set 1))))

(defn execute-sql!
  [db-path sql-text]
  (with-open [connection (db/open-connection db-path)
              statement (.createStatement connection)]
    (.execute statement sql-text)))

(defn test-system
  []
  (let [db-path (temp-db-path)]
    (db/initialize-database! db-path)
    {:db-path db-path
     :store (store.sqlite/sqlite-state-store db-path)
     :reader (projection/sqlite-projection-reader db-path)}))

(defn task
  [task-id work-key now]
  {:task/id task-id
   :task/work-key work-key
   :task/task-type-ref {:definition/id :task-type/default
                        :definition/version 1}
   :task/task-fsm-ref {:definition/id :task-fsm/default
                       :definition/version 3}
   :task/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                              :definition/version 1}
   :task/artifact-contract-ref {:definition/id :artifact-contract/default
                                :definition/version 1}
   :task/validator-ref {:definition/id :validator/required-paths
                        :definition/version 1}
   :task/resource-policy-ref {:definition/id :resource-policy/default
                              :definition/version 3}
   :task/state :task.state/queued
   :task/created-at now
   :task/updated-at now})

(defn run
  [run-id attempt now]
  {:run/id run-id
   :run/attempt attempt
   :run/run-fsm-ref {:definition/id :run-fsm/default
                     :definition/version 2}
   :run/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                             :definition/version 1}
   :run/state :run.state/leased
   :run/created-at now
   :run/updated-at now})

(defn lease
  [lease-id run-id expires-at now]
  {:lease/id lease-id
   :lease/run-id run-id
   :lease/token (str lease-id "-token")
   :lease/state :lease.state/active
   :lease/expires-at expires-at
   :lease/created-at now
   :lease/updated-at now})

(defn collection-state
  ([paused? now]
   (collection-state paused? now {}))
  ([paused? now {:keys [cooldown-until resource-policy-ref]}]
   {:collection/id :collection/default
    :collection/dispatch {:dispatch/paused? paused?
                          :dispatch/cooldown-until cooldown-until}
    :collection/resource-policy-ref (or resource-policy-ref
                                        {:definition/id :resource-policy/default
                                         :definition/version 3})
    :collection/created-at now
    :collection/updated-at now}))
