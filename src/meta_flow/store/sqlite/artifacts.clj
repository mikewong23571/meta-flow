(ns meta-flow.store.sqlite.artifacts
  (:require [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.run-rows :as run-rows]
            [meta-flow.store.sqlite.shared :as shared]))

(defn attach-artifact!
  [db-path run-id artifact]
  (let [_ (shared/require-matching-value! artifact :artifact/run-id run-id)
        artifact (shared/normalize-artifact (assoc artifact :artifact/run-id run-id))
        root-path (or (:artifact/root-path artifact) (:artifact/location artifact))]
    (sql/with-transaction db-path
      (fn [connection]
        (let [task-id (run-rows/require-run-task-id! connection run-id)
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
          (run-rows/update-run-artifact! connection run-id (:artifact/id artifact))
          (assoc artifact :artifact/root-path root-path))))))

(defn find-artifact
  [db-path artifact-id]
  (sql/with-connection db-path
    (fn [connection]
      (some-> (sql/query-one connection
                             "SELECT artifact_edn FROM artifacts WHERE artifact_id = ?"
                             [artifact-id])
              (shared/parse-edn-column :artifact_edn)))))
