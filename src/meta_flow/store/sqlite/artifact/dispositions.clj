(ns meta-flow.store.sqlite.artifact.dispositions
  (:require [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.shared :as shared]))

(defn find-disposition-row
  [connection run-id disposition-key]
  (sql/query-one connection
                 "SELECT disposition_edn FROM dispositions WHERE run_id = ? AND disposition_key = ?"
                 [run-id disposition-key]))

(defn record-disposition-via-connection!
  [connection disposition]
  (let [disposition (shared/normalize-disposition disposition)
        run-id (shared/require-key! disposition :disposition/run-id)
        disposition-key (shared/require-key! disposition :disposition/key)]
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
        disposition)))

(defn record-disposition!
  [db-path disposition]
  (sql/with-transaction db-path
    (fn [connection]
      (record-disposition-via-connection! connection disposition))))

(defn find-disposition-by-key
  [db-path run-id disposition-key]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (find-disposition-row connection run-id disposition-key)
              (shared/parse-edn-column :disposition_edn)))))
