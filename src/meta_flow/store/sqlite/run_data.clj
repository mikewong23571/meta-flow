(ns meta-flow.store.sqlite.run-data
  (:require [clojure.string :as str]
            [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.runs :as runs]
            [meta-flow.store.sqlite.shared :as shared]))

(def ^:private max-event-ingest-attempts 5)

(defn find-run-event-row
  [connection run-id idempotency-key]
  (sql/query-one connection
                 "SELECT run_id, event_seq, event_payload_edn FROM run_events WHERE run_id = ? AND event_idempotency_key = ?"
                 [run-id idempotency-key]))

(defn find-assessment-row
  [connection run-id assessment-key]
  (sql/query-one connection
                 "SELECT assessment_edn FROM assessments WHERE run_id = ? AND assessment_key = ?"
                 [run-id assessment-key]))

(defn find-disposition-row
  [connection run-id disposition-key]
  (sql/query-one connection
                 "SELECT disposition_edn FROM dispositions WHERE run_id = ? AND disposition_key = ?"
                 [run-id disposition-key]))

(defn next-event-seq
  [connection run-id]
  (let [row (sql/query-one connection
                           "SELECT COALESCE(MAX(event_seq), 0) AS max_seq FROM run_events WHERE run_id = ?"
                           [run-id])]
    (inc (long (or (:max_seq row) 0)))))

(defn update-run-summary-from-event!
  [connection event]
  (let [run-id (:event/run-id event)
        emitted-at (:event/emitted-at event)
        artifact-id (or (get-in event [:event/payload :artifact/id])
                        (get-in event [:event/payload :artifact-id])
                        (:event/artifact-id event))]
    (runs/update-run-row! connection run-id
                          (fn [run _]
                            (cond-> run
                              emitted-at (assoc :run/updated-at
                                                (let [updated-at (:run/updated-at run)]
                                                  (if (and updated-at
                                                           (not (.isAfter (java.time.Instant/parse emitted-at)
                                                                          (java.time.Instant/parse updated-at))))
                                                    updated-at
                                                    emitted-at)))
                              artifact-id (assoc :run/artifact-id artifact-id))))))

(defn load-existing-run-event
  [db-path run-id idempotency-key]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (find-run-event-row connection run-id idempotency-key)
              (shared/parse-edn-column :event_payload_edn)))))

(defn retryable-event-ingest-exception?
  [throwable]
  (let [message (some-> throwable .getMessage str/lower-case)]
    (boolean (or (and message (str/includes? message "database is locked"))
                 (and message (str/includes? message "busy"))
                 (and message (str/includes? message "run_events.run_id, run_events.event_seq"))))))

(defn ingest-run-event-via-connection!
  [connection event-intent]
  (when (contains? event-intent :event/seq)
    (throw (ex-info "Event producers must not supply :event/seq"
                    {:event-intent event-intent})))
  (shared/validate-event-intent! event-intent)
  (if-let [existing-row (find-run-event-row connection
                                            (:event/run-id event-intent)
                                            (:event/idempotency-key event-intent))]
    (shared/parse-edn-column existing-row :event_payload_edn)
    (let [event (shared/normalize-event event-intent (next-event-seq connection (:event/run-id event-intent)))]
      (sql/execute-update! connection
                           (str "INSERT INTO run_events "
                                "(run_id, event_seq, event_type, event_idempotency_key, event_payload_edn, created_at) "
                                "VALUES (?, ?, ?, ?, ?, ?)")
                           [(:event/run-id event)
                            (:event/seq event)
                            (:event/type event)
                            (:event/idempotency-key event)
                            (sql/edn->text event)
                            (:event/emitted-at event)])
      (update-run-summary-from-event! connection event)
      event)))

(defn ingest-run-event-once!
  [db-path event-intent]
  (sql/with-transaction db-path
    (fn [connection]
      (ingest-run-event-via-connection! connection event-intent))))

(defn ingest-run-event-with-retry!
  [db-path event-intent]
  (loop [attempt 1]
    (let [result (try
                   {:status :ok
                    :value (ingest-run-event-once! db-path event-intent)}
                   (catch java.sql.SQLException throwable
                     {:status :error
                      :throwable throwable}))]
      (if (= :ok (:status result))
        (:value result)
        (let [throwable (:throwable result)
              existing-event (load-existing-run-event db-path
                                                      (:event/run-id event-intent)
                                                      (:event/idempotency-key event-intent))]
          (cond
            existing-event existing-event
            (and (< attempt max-event-ingest-attempts)
                 (retryable-event-ingest-exception? throwable))
            (recur (inc attempt))
            :else
            (throw throwable)))))))

(defn list-run-events
  [db-path run-id]
  (sql/with-connection db-path
    (fn [connection]
      (mapv #(shared/parse-edn-column % :event_payload_edn)
            (sql/query-rows connection
                            "SELECT event_payload_edn FROM run_events WHERE run_id = ? ORDER BY event_seq ASC"
                            [run-id])))))

(defn list-run-events-after
  [db-path run-id event-seq]
  (sql/with-connection db-path
    (fn [connection]
      (mapv #(shared/parse-edn-column % :event_payload_edn)
            (sql/query-rows connection
                            (str "SELECT event_payload_edn FROM run_events "
                                 "WHERE run_id = ? AND event_seq > ? ORDER BY event_seq ASC")
                            [run-id event-seq])))))

(defn attach-artifact!
  [db-path run-id artifact]
  (let [_ (shared/require-matching-value! artifact :artifact/run-id run-id)
        artifact (shared/normalize-artifact (assoc artifact :artifact/run-id run-id))
        root-path (or (:artifact/root-path artifact) (:artifact/location artifact))]
    (sql/with-transaction db-path
      (fn [connection]
        (let [task-id (runs/require-run-task-id! connection run-id)
              _ (shared/require-matching-value! artifact :artifact/task-id task-id)]
          (sql/execute-update! connection
                               (str "INSERT INTO artifacts "
                                    "(artifact_id, run_id, task_id, artifact_contract_id, artifact_contract_version, root_path, artifact_edn, created_at) "
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                               [(shared/require-key! artifact :artifact/id)
                                (shared/require-key! artifact :artifact/run-id)
                                (shared/require-key! artifact :artifact/task-id)
                                (shared/ref-id artifact :artifact/contract-ref)
                                (shared/ref-version artifact :artifact/contract-ref)
                                root-path
                                (sql/edn->text (assoc artifact :artifact/root-path root-path))
                                (:artifact/created-at artifact)])
          (runs/update-run-artifact! connection run-id (:artifact/id artifact))
          (assoc artifact :artifact/root-path root-path))))))

(defn find-artifact
  [db-path artifact-id]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (sql/query-one connection
                             "SELECT artifact_edn FROM artifacts WHERE artifact_id = ?"
                             [artifact-id])
              (shared/parse-edn-column :artifact_edn)))))

(defn record-assessment!
  [db-path assessment]
  (let [assessment (shared/normalize-assessment assessment)
        run-id (shared/require-key! assessment :assessment/run-id)
        assessment-key (shared/require-key! assessment :assessment/key)]
    (sql/with-transaction db-path
      (fn [connection]
        (sql/execute-update! connection
                             (str "INSERT OR IGNORE INTO assessments "
                                  "(assessment_id, run_id, assessment_key, validator_id, validator_version, status, assessment_edn, created_at) "
                                  "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                             [(shared/require-key! assessment :assessment/id)
                              run-id
                              assessment-key
                              (shared/ref-id assessment :assessment/validator-ref)
                              (shared/ref-version assessment :assessment/validator-ref)
                              (shared/require-key! assessment :assessment/outcome)
                              (sql/edn->text assessment)
                              (:assessment/checked-at assessment)])
        (or (some-> (find-assessment-row connection run-id assessment-key)
                    (shared/parse-edn-column :assessment_edn))
            assessment)))))

(defn find-assessment-by-key
  [db-path run-id assessment-key]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (find-assessment-row connection run-id assessment-key)
              (shared/parse-edn-column :assessment_edn)))))

(defn record-disposition!
  [db-path disposition]
  (let [disposition (shared/normalize-disposition disposition)
        run-id (shared/require-key! disposition :disposition/run-id)
        disposition-key (shared/require-key! disposition :disposition/key)]
    (sql/with-transaction db-path
      (fn [connection]
        (sql/execute-update! connection
                             (str "INSERT OR IGNORE INTO dispositions "
                                  "(disposition_id, run_id, disposition_key, disposition_type, disposition_edn, created_at) "
                                  "VALUES (?, ?, ?, ?, ?, ?)")
                             [(shared/require-key! disposition :disposition/id)
                              run-id
                              disposition-key
                              (shared/require-key! disposition :disposition/action)
                              (sql/edn->text disposition)
                              (:disposition/decided-at disposition)])
        (or (some-> (find-disposition-row connection run-id disposition-key)
                    (shared/parse-edn-column :disposition_edn))
            disposition)))))

(defn find-disposition-by-key
  [db-path run-id disposition-key]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (find-disposition-row connection run-id disposition-key)
              (shared/parse-edn-column :disposition_edn)))))
