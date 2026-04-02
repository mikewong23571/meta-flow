(ns meta-flow.store.sqlite.run.lifecycle
  (:require [meta-flow.db :as db]
            [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.lease.core :as leases]
            [meta-flow.store.sqlite.run.rows :as run-rows]
            [meta-flow.store.sqlite.shared :as shared]
            [meta-flow.store.sqlite.tasks :as tasks]))

(defn create-run!
  [db-path task run lease]
  (let [task-id (shared/require-key! task :task/id)
        run-id (shared/require-key! run :run/id)
        lease-id (shared/require-key! lease :lease/id)
        _ (shared/require-matching-value! lease :lease/run-id run-id)
        _ (shared/require-matching-value! run :run/lease-id lease-id)
        lease (shared/normalize-lease (assoc lease :lease/run-id run-id))
        run (shared/normalize-run (assoc run
                                         :run/task-id task-id
                                         :run/lease-id lease-id)
                                  lease-id)]
    (sql/with-transaction db-path
      (fn [connection]
        (run-rows/insert-run! connection run)
        (leases/insert-lease! connection lease)
        {:run run
         :lease lease}))))

(defn claim-task-for-run!
  [db-path task run lease task-transition run-transition now]
  (let [task-id (shared/require-key! task :task/id)
        run-id (shared/require-key! run :run/id)
        lease-id (shared/require-key! lease :lease/id)
        _ (shared/require-matching-value! lease :lease/run-id run-id)]
    (sql/with-transaction db-path
      (fn [connection]
        (when-let [task-row (tasks/find-task-row connection task-id)]
          (when (= (:state task-row) (db/keyword-text (:transition/from task-transition)))
            (let [attempt (run-rows/next-run-attempt connection task-id)
                  lease (shared/normalize-lease (assoc lease :lease/run-id run-id))
                  run (shared/normalize-run (assoc run
                                                   :run/task-id task-id
                                                   :run/attempt attempt
                                                   :run/lease-id lease-id)
                                            lease-id)
                  _ (run-rows/insert-run! connection run)
                  _ (leases/insert-lease! connection lease)
                  claimed-run (or (run-rows/update-run-transition! connection
                                                                   run-id
                                                                   (:transition/from run-transition)
                                                                   (:transition/to run-transition)
                                                                   now
                                                                   run-transition)
                                  (throw (ex-info "Failed to transition claimed run"
                                                  {:task-id task-id
                                                   :run-id run-id})))
                  claimed-task (or (tasks/update-task-transition! connection
                                                                  task-id
                                                                  (:transition/from task-transition)
                                                                  (:transition/to task-transition)
                                                                  now
                                                                  task-transition)
                                   (throw (ex-info "Failed to transition claimed task"
                                                   {:task-id task-id
                                                    :run-id run-id})))]
              {:task claimed-task
               :run claimed-run
               :lease lease})))))))

(defn recover-run-startup-failure!
  [db-path task run now]
  (sql/with-transaction db-path
    (fn [connection]
      (let [run-row (run-rows/find-run-row connection (:run/id run))
            task-row (tasks/find-task-row connection (:task/id task))
            event-count (run-rows/run-event-count connection (:run/id run))
            current-run (some-> run-row shared/run-row->entity)
            current-task (some-> task-row (shared/parse-edn-column :task_edn))]
        (if (and current-run
                 current-task
                 (zero? event-count)
                 (= :run.state/leased (:run/state current-run))
                 (= :task.state/leased (:task/state current-task))
                 (= (:run/lease-id current-run) (:run/lease-id run)))
          (let [recovered-run (or (run-rows/transition-run-via-connection! connection
                                                                           (:run/id run)
                                                                           {:transition/from :run.state/leased
                                                                            :transition/to :run.state/finalized}
                                                                           now)
                                  current-run)
                _ (leases/release-lease-via-connection! connection (:run/lease-id run) now)
                recovered-task (or (tasks/transition-task-via-connection! connection
                                                                          (:task/id task)
                                                                          {:transition/from :task.state/leased
                                                                           :transition/to :task.state/queued}
                                                                          now)
                                   current-task)]
            {:recovery/status :recovered
             :run recovered-run
             :task recovered-task
             :event-count event-count})
          {:recovery/status :skipped
           :run current-run
           :task current-task
           :event-count event-count})))))

(defn find-run
  [db-path run-id]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (run-rows/find-run-row connection run-id)
              shared/run-row->entity))))

(defn find-latest-run-for-task
  [db-path task-id]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (run-rows/find-latest-run-row-for-task connection task-id)
              shared/run-row->entity))))

(defn transition-run!
  [db-path run-id transition now]
  (sql/with-transaction db-path
    (fn [connection]
      (run-rows/transition-run-via-connection! connection run-id transition now))))
