(ns meta-flow.store.sqlite.shared
  (:require [meta-flow.db :as db]
            [meta-flow.sql :as sql]))

(defn require-key!
  [entity key-name]
  (or (get entity key-name)
      (throw (ex-info (str "Missing required entity key " key-name)
                      {:entity entity
                       :key key-name}))))

(defn require-definition-ref!
  [entity ref-key]
  (or (get entity ref-key)
      (throw (ex-info (str "Missing required definition ref " ref-key)
                      {:entity entity
                       :ref-key ref-key}))))

(defn require-matching-value!
  [entity key-name expected]
  (when-let [actual (get entity key-name)]
    (when (not= actual expected)
      (throw (ex-info (str "Mismatched entity key " key-name)
                      {:entity entity
                       :key key-name
                       :expected expected
                       :actual actual}))))
  expected)

(defn normalize-task
  [task]
  (let [created-at (or (:task/created-at task) (sql/utc-now))
        updated-at (or (:task/updated-at task) created-at)]
    (sql/canonicalize-edn
     (assoc task
            :task/created-at created-at
            :task/updated-at updated-at))))

(defn normalize-run
  [run lease-id]
  (let [created-at (or (:run/created-at run) (sql/utc-now))
        updated-at (or (:run/updated-at run) created-at)]
    (sql/canonicalize-edn
     (assoc run
            :run/created-at created-at
            :run/updated-at updated-at
            :run/lease-id (or (:run/lease-id run) lease-id)))))

(defn normalize-lease
  [lease]
  (let [created-at (or (:lease/created-at lease) (sql/utc-now))
        updated-at (or (:lease/updated-at lease) created-at)]
    (sql/canonicalize-edn
     (assoc lease
            :lease/created-at created-at
            :lease/updated-at updated-at))))

(defn normalize-collection-state
  ([collection-state]
   (normalize-collection-state collection-state nil))
  ([collection-state existing-created-at]
   (let [created-at (or existing-created-at
                        (:collection/created-at collection-state)
                        (sql/utc-now))
         updated-at (or (:collection/updated-at collection-state) created-at)]
     (sql/canonicalize-edn
      (assoc collection-state
             :collection/created-at created-at
             :collection/updated-at updated-at)))))

(defn normalize-artifact
  [artifact]
  (let [created-at (or (:artifact/created-at artifact) (sql/utc-now))]
    (sql/canonicalize-edn
     (assoc artifact :artifact/created-at created-at))))

(defn normalize-assessment
  [assessment]
  (let [checked-at (or (:assessment/checked-at assessment)
                       (:assessment/created-at assessment)
                       (sql/utc-now))]
    (sql/canonicalize-edn
     (assoc assessment :assessment/checked-at checked-at))))

(defn normalize-disposition
  [disposition]
  (let [decided-at (or (:disposition/decided-at disposition)
                       (:disposition/created-at disposition)
                       (sql/utc-now))]
    (sql/canonicalize-edn
     (assoc disposition :disposition/decided-at decided-at))))

(defn normalize-event
  [event seq-value]
  (let [emitted-at (or (:event/emitted-at event) (sql/utc-now))]
    (sql/canonicalize-edn
     (assoc event
            :event/seq seq-value
            :event/emitted-at emitted-at))))

(def ^:private required-event-intent-keys
  [:event/run-id
   :event/type
   :event/payload
   :event/caused-by
   :event/idempotency-key])

(defn require-event-intent-key!
  [event-intent key-name]
  (let [value (get event-intent key-name ::missing)]
    (when (or (= ::missing value) (nil? value))
      (throw (ex-info (str "Missing required event key " key-name)
                      {:event-intent event-intent
                       :key key-name})))))

(defn validate-event-intent!
  [event-intent]
  (doseq [key-name required-event-intent-keys]
    (require-event-intent-key! event-intent key-name))
  event-intent)

(defn ref-id
  [entity ref-key]
  (-> entity
      (require-definition-ref! ref-key)
      :definition/id
      db/keyword-text))

(defn ref-version
  [entity ref-key]
  (-> entity
      (require-definition-ref! ref-key)
      :definition/version))

(defn parse-edn-column
  [row column]
  (some-> row column sql/text->edn))

(defn run-row->entity
  [row]
  (cond-> (parse-edn-column row :run_edn)
    (:task_id row) (assoc :run/task-id (:task_id row))
    (contains? row :attempt) (assoc :run/attempt (:attempt row))
    (:state row) (assoc :run/state (sql/text->edn (:state row)))
    true (assoc :run/lease-id (:lease_id row))
    true (assoc :run/artifact-id (:artifact_id row))
    (:created_at row) (assoc :run/created-at (:created_at row))
    (:updated_at row) (assoc :run/updated-at (:updated_at row))))

(defn collection-state-row->entity
  [row]
  (cond-> (parse-edn-column row :state_edn)
    (:created_at row) (assoc :collection/created-at (:created_at row))
    (:updated_at row) (assoc :collection/updated-at (:updated_at row))))

(defn build-transitioned-entity
  [existing entity-key state-key to-state now transition]
  (sql/canonicalize-edn
   (or (:entity transition)
       (get transition entity-key)
       (cond-> existing
         true (assoc state-key to-state)
         now (assoc (keyword (namespace state-key) "updated-at") now)
         (:changes transition) (merge (:changes transition))))))
