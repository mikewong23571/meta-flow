(ns meta-flow.ui.tasks
  (:require [clojure.string :as str]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]
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

(defn- derive-work-key
  [work-key-expr input]
  (case (:work-key/type work-key-expr)
    :work-key.type/direct
    (get input (:work-key/field work-key-expr))
    :work-key.type/tuple
    (pr-str (into [(:work-key/tag work-key-expr)]
                  (map #(get input %) (:work-key/fields work-key-expr))))))

(defn- task-input-fields
  [input]
  (not-empty (reduce-kv (fn [m k v] (if (namespace k) (assoc m k v) m)) {} input)))

(defn- blank-input-value?
  [value]
  (or (nil? value)
      (and (string? value)
           (str/blank? value))))

(defn- missing-required-input-fields
  [task-type input]
  (->> (:task-type/input-schema task-type)
       (filter :field/required?)
       (filter #(blank-input-value? (get input (:field/id %))))
       (mapv (fn [{:keys [field/id field/label]}]
               {:field/id id
                :field/label label}))))

(defn- validate-required-inputs!
  [task-type task-type-id input]
  (let [missing-fields (missing-required-input-fields task-type input)]
    (when (seq missing-fields)
      (throw (ex-info (str "Required task input fields cannot be blank: "
                           (str/join ", " (map (comp str :field/id) missing-fields)))
                      {:task-type-id task-type-id
                       :missing-fields missing-fields
                       :input input})))))

(defn create-task!
  ([task-type-id task-type-version input]
   (create-task! db/default-db-path task-type-id task-type-version input))
  ([db-path task-type-id task-type-version input]
   (let [defs-repo (defs.loader/filesystem-definition-repository)
         task-type (defs.protocol/find-task-type-def defs-repo task-type-id task-type-version)
         _ (when-not task-type
             (throw (ex-info (str "Task type not found: " task-type-id)
                             {:task-type-id task-type-id :task-type-version task-type-version})))
         work-key-expr (:task-type/work-key-expr task-type)
         _ (when-not work-key-expr
             (throw (ex-info (str "Task type missing work-key-expr: " task-type-id)
                             {:task-type-id task-type-id})))
         _ (validate-required-inputs! task-type task-type-id input)
         work-key (derive-work-key work-key-expr input)
         _ (when (str/blank? work-key)
             (throw (ex-info "Work key cannot be blank"
                             {:task-type-id task-type-id :input input})))
         input-fields (task-input-fields input)
         now-str (sql/utc-now)
         task (cond-> {:task/id (str "task-" (java.util.UUID/randomUUID))
                       :task/work-key work-key
                       :task/task-type-ref {:definition/id (:task-type/id task-type)
                                            :definition/version (:task-type/version task-type)}
                       :task/task-fsm-ref (:task-type/task-fsm-ref task-type)
                       :task/run-fsm-ref (:task-type/run-fsm-ref task-type)
                       :task/runtime-profile-ref (:task-type/runtime-profile-ref task-type)
                       :task/artifact-contract-ref (:task-type/artifact-contract-ref task-type)
                       :task/validator-ref (:task-type/validator-ref task-type)
                       :task/resource-policy-ref (:task-type/resource-policy-ref task-type)
                       :task/state :task.state/queued
                       :task/created-at now-str
                       :task/updated-at now-str}
                input-fields (assoc :task/input input-fields))
         store (store.sqlite/sqlite-state-store db-path)
         result (store.protocol/enqueue-task! store task)]
     {:task/id (:task/id result)
      :task/work-key (:task/work-key result)
      :task/state (:task/state result)})))

(defn list-tasks
  ([] (list-tasks db/default-db-path default-task-list-limit))
  ([db-path] (list-tasks db-path default-task-list-limit))
  ([db-path limit]
   (let [defs-repo (defs.loader/filesystem-definition-repository)]
     (sql/with-connection db-path
       (fn [connection]
         (mapv #(task-row->item defs-repo %)
               (latest-run-rows-query connection limit)))))))
