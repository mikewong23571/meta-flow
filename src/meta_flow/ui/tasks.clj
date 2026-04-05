(ns meta-flow.ui.tasks
  (:require [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.shared :as shared]))

(def ^:private default-task-list-limit 200)

(defn- latest-run-rows-query
  [connection limit]
  (sql/query-rows connection
                  (str "SELECT t.task_id, t.task_edn, t.state AS task_state, t.created_at AS task_created_at, "
                       "t.updated_at AS task_updated_at, "
                       "r.run_id, r.attempt AS run_attempt, r.state AS run_state, r.run_edn, "
                       "r.updated_at AS run_updated_at "
                       "FROM tasks t "
                       "LEFT JOIN runs r ON r.run_id = ("
                       "  SELECT r2.run_id FROM runs r2 "
                       "  WHERE r2.task_id = t.task_id "
                       "  ORDER BY r2.attempt DESC, r2.updated_at DESC, r2.run_id DESC "
                       "  LIMIT 1"
                       ") "
                       "ORDER BY t.updated_at DESC, t.task_id DESC "
                       "LIMIT ?")
                  [limit]))

(defn- find-task-type
  [defs-repo task]
  (defs.protocol/find-task-type-def defs-repo
                                    (get-in task [:task/task-type-ref :definition/id])
                                    (get-in task [:task/task-type-ref :definition/version])))

(defn- task-summary
  [task task-type]
  (let [task-type-id (get-in task [:task/task-type-ref :definition/id])]
    (cond
      (= :task-type/repo-arch-investigation task-type-id)
      {:primary (get-in task [:task/input :input/repo-url])
       :secondary (get-in task [:task/input :input/notify-email])}

      :else
      {:primary (:task/work-key task)
       :secondary (or (:task-type/name task-type)
                      (some-> task-type-id str))})))

(defn- task-row->item
  [defs-repo row]
  (let [task (shared/parse-edn-column row :task_edn)
        task-type (find-task-type defs-repo task)
        run (some-> row :run_edn sql/text->edn)
        summary (task-summary task task-type)]
    {:task/id (:task/id task)
     :task/state (:task/state task)
     :task/work-key (:task/work-key task)
     :task/type-ref (:task/task-type-ref task)
     :task/type-id (get-in task [:task/task-type-ref :definition/id])
     :task/type-name (or (:task-type/name task-type)
                         (some-> task :task/task-type-ref :definition/id str))
     :task/summary summary
     :task/created-at (:task/created-at task)
     :task/updated-at (:task/updated-at task)
     :latest-run (when run
                   {:run/id (:run/id run)
                    :run/state (:run/state run)
                    :run/attempt (:run/attempt run)
                    :run/updated-at (:run/updated-at run)})}))

(defn list-tasks
  ([] (list-tasks db/default-db-path default-task-list-limit))
  ([db-path] (list-tasks db-path default-task-list-limit))
  ([db-path limit]
   (let [defs-repo (defs.loader/filesystem-definition-repository)]
     (sql/with-connection db-path
       (fn [connection]
         (mapv #(task-row->item defs-repo %)
               (latest-run-rows-query connection limit)))))))
