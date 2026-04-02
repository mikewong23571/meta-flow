(ns meta-flow.store.sqlite.artifact.assessments
  (:require [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.shared :as shared]))

(defn find-assessment-row
  [connection run-id assessment-key]
  (sql/query-one connection
                 "SELECT assessment_edn FROM assessments WHERE run_id = ? AND assessment_key = ?"
                 [run-id assessment-key]))

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
