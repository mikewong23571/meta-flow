(ns meta-flow.db-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.db :as db]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn- query-single-value
  [db-path sql]
  (with-open [connection (db/open-connection db-path)
              statement (.createStatement connection)
              result-set (.executeQuery statement sql)]
    (when (.next result-set)
      (.getObject result-set 1))))

(defn- execute-sql!
  [db-path sql]
  (with-open [connection (db/open-connection db-path)
              statement (.createStatement connection)]
    (.execute statement sql)))

(defn- query-rows
  [db-path sql-text params]
  (sql/with-connection db-path
    (fn [connection]
      (sql/query-rows connection sql-text params))))

(defn- resource-statements
  [resource-path]
  (->> (str/split (slurp (io/resource resource-path))
                  #";\s*(?:\r?\n|$)")
       (map str/trim)
       (remove str/blank?)))

(defn- apply-resource-sql!
  [db-path resource-path]
  (with-open [connection (db/open-connection db-path)]
    (doseq [statement (resource-statements resource-path)]
      (with-open [jdbc-statement (.createStatement connection)]
        (.execute jdbc-statement statement)))))

(deftest initialize-database-creates-bootstrap-schema
  (let [temp-dir (java.nio.file.Files/createTempDirectory "meta-flow-db-test" (make-array java.nio.file.attribute.FileAttribute 0))
        db-path (str (.toFile temp-dir) "/meta-flow.sqlite3")]
    (db/initialize-database! db-path)
    (testing "the SQLite file is created"
      (is (.exists (io/file db-path))))
    (testing "the bootstrap migration is recorded"
      (is (= "001_init"
             (query-single-value db-path "SELECT migration_id FROM schema_migrations WHERE migration_id = '001_init'"))))
    (testing "the keyword-alignment migration is recorded"
      (is (= "002_align_keyword_literals"
             (query-single-value db-path "SELECT migration_id FROM schema_migrations WHERE migration_id = '002_align_keyword_literals'"))))
    (testing "the semantic idempotency migration is recorded"
      (is (= "003_add_semantic_idempotency_keys"
             (query-single-value db-path "SELECT migration_id FROM schema_migrations WHERE migration_id = '003_add_semantic_idempotency_keys'"))))
    (testing "phase 1 views exist"
      (is (= "runnable_tasks_v1"
             (query-single-value db-path "SELECT name FROM sqlite_master WHERE type = 'view' AND name = 'runnable_tasks_v1'"))))))

(deftest open-connection-applies-sqlite-pragmas-on-every-connection
  (let [temp-dir (java.nio.file.Files/createTempDirectory "meta-flow-db-pragmas" (make-array java.nio.file.attribute.FileAttribute 0))
        db-path (str (.toFile temp-dir) "/meta-flow.sqlite3")]
    (db/initialize-database! db-path)
    (testing "connection-local pragmas are re-applied for every app connection"
      (is (= 1 (query-single-value db-path "PRAGMA foreign_keys")))
      (is (= 5000 (query-single-value db-path "PRAGMA busy_timeout")))
      (is (= "wal" (query-single-value db-path "PRAGMA journal_mode"))))))

(deftest pooled-data-source-is-reused-per-db-path-and-can-be-closed
  (let [temp-dir (java.nio.file.Files/createTempDirectory "meta-flow-db-pool"
                                                          (make-array java.nio.file.attribute.FileAttribute 0))
        db-path-a (str (.toFile temp-dir) "/a.sqlite3")
        db-path-b (str (.toFile temp-dir) "/b.sqlite3")]
    (db/initialize-database! db-path-a)
    (db/initialize-database! db-path-b)
    (let [ds-a-1 (db/data-source db-path-a)
          ds-a-2 (db/data-source db-path-a)
          ds-b (db/data-source db-path-b)]
      (testing "the same database path reuses a cached pooled data source"
        (is (identical? ds-a-1 ds-a-2)))
      (testing "different database paths get isolated pooled data sources"
        (is (not (identical? ds-a-1 ds-b))))
      (testing "closing a cached data source evicts it so a later call rebuilds it"
        (db/close-data-source! db-path-a)
        (let [ds-a-3 (db/data-source db-path-a)]
          (is (not (identical? ds-a-1 ds-a-3)))
          (db/close-data-source! db-path-a)
          (db/close-data-source! db-path-b))))))

(deftest keyword-literal-state-schema-drives-views-and-unique-indexes
  (let [temp-dir (java.nio.file.Files/createTempDirectory "meta-flow-db-state" (make-array java.nio.file.attribute.FileAttribute 0))
        db-path (str (.toFile temp-dir) "/meta-flow.sqlite3")]
    (db/initialize-database! db-path)
    (execute-sql! db-path
                  (str "INSERT INTO tasks "
                       "(task_id, work_key, task_type_id, task_type_version, task_fsm_id, task_fsm_version, "
                       "runtime_profile_id, runtime_profile_version, artifact_contract_id, artifact_contract_version, "
                       "validator_id, validator_version, resource_policy_id, resource_policy_version, state, task_edn, created_at, updated_at) "
                       "VALUES "
                       "('task-1', 'wk-1', ':task-type/default', 1, ':task-fsm/default', 1, "
                       "':runtime-profile/mock-worker', 1, ':artifact-contract/default', 1, "
                       "':validator/required-paths', 1, ':resource-policy/default', 1, ':task.state/queued', '{}', "
                       "'2026-04-01T00:00:00Z', '2026-04-01T00:00:00Z')"))
    (testing "queued task view matches keyword-text state values"
      (is (= "task-1"
             (query-single-value db-path "SELECT task_id FROM runnable_tasks_v1 WHERE task_id = 'task-1'"))))
    (execute-sql! db-path
                  (str "INSERT INTO runs "
                       "(run_id, task_id, attempt, run_fsm_id, run_fsm_version, runtime_profile_id, runtime_profile_version, "
                       "state, run_edn, created_at, updated_at) VALUES "
                       "('run-1', 'task-1', 1, ':run-fsm/default', 1, ':runtime-profile/mock-worker', 1, "
                       "':run.state/created', '{}', '2026-04-01T00:00:00Z', '2026-04-01T00:00:00Z')"))
    (testing "non-terminal run unique index matches keyword-text state values"
      (is (thrown? java.sql.SQLException
                   (execute-sql! db-path
                                 (str "INSERT INTO runs "
                                      "(run_id, task_id, attempt, run_fsm_id, run_fsm_version, runtime_profile_id, runtime_profile_version, "
                                      "state, run_edn, created_at, updated_at) VALUES "
                                      "('run-2', 'task-1', 2, ':run-fsm/default', 1, ':runtime-profile/mock-worker', 1, "
                                      "':run.state/created', '{}', '2026-04-01T00:00:00Z', '2026-04-01T00:00:00Z')")))))))

(deftest semantic-idempotency-migration-backfills-current-keys-without-losing-history
  (let [temp-dir (java.nio.file.Files/createTempDirectory "meta-flow-db-semantic-backfill"
                                                          (make-array java.nio.file.attribute.FileAttribute 0))
        db-path (str (.toFile temp-dir) "/meta-flow.sqlite3")
        store (store.sqlite/sqlite-state-store db-path)]
    (apply-resource-sql! db-path "meta_flow/sql/001_init.sql")
    (apply-resource-sql! db-path "meta_flow/sql/002_align_keyword_literals.sql")
    (execute-sql! db-path
                  (str "INSERT INTO tasks "
                       "(task_id, work_key, task_type_id, task_type_version, task_fsm_id, task_fsm_version, "
                       "runtime_profile_id, runtime_profile_version, artifact_contract_id, artifact_contract_version, "
                       "validator_id, validator_version, resource_policy_id, resource_policy_version, state, task_edn, created_at, updated_at) "
                       "VALUES "
                       "('task-1', 'wk-backfill', ':task-type/default', 1, ':task-fsm/default', 1, "
                       "':runtime-profile/mock-worker', 1, ':artifact-contract/default', 1, "
                       "':validator/required-paths', 1, ':resource-policy/default', 1, ':task.state/awaiting-validation', '{}', "
                       "'2026-04-01T00:00:00Z', '2026-04-01T00:00:00Z')"))
    (execute-sql! db-path
                  (str "INSERT INTO runs "
                       "(run_id, task_id, attempt, run_fsm_id, run_fsm_version, runtime_profile_id, runtime_profile_version, "
                       "state, run_edn, created_at, updated_at) VALUES "
                       "('run-1', 'task-1', 1, ':run-fsm/default', 1, ':runtime-profile/mock-worker', 1, "
                       "':run.state/awaiting-validation', '{}', '2026-04-01T00:00:00Z', '2026-04-01T00:00:00Z')"))
    (execute-sql! db-path
                  (str "INSERT INTO assessments "
                       "(assessment_id, run_id, validator_id, validator_version, status, assessment_edn, created_at) VALUES "
                       "('assessment-older', 'run-1', ':validator/required-paths', 1, ':assessment/rejected', "
                       "'{:assessment/id \"assessment-older\" :assessment/run-id \"run-1\" :assessment/outcome :assessment/rejected}', "
                       "'2026-04-01T00:10:00Z'), "
                       "('assessment-newer', 'run-1', ':validator/required-paths', 1, ':assessment/accepted', "
                       "'{:assessment/id \"assessment-newer\" :assessment/run-id \"run-1\" :assessment/outcome :assessment/accepted}', "
                       "'2026-04-01T00:20:00Z')"))
    (execute-sql! db-path
                  (str "INSERT INTO dispositions "
                       "(disposition_id, run_id, disposition_type, disposition_edn, created_at) VALUES "
                       "('disposition-older', 'run-1', ':disposition/rejected', "
                       "'{:disposition/id \"disposition-older\" :disposition/run-id \"run-1\" :disposition/action :disposition/rejected}', "
                       "'2026-04-01T00:10:00Z'), "
                       "('disposition-newer', 'run-1', ':disposition/accepted', "
                       "'{:disposition/id \"disposition-newer\" :disposition/run-id \"run-1\" :disposition/action :disposition/accepted}', "
                       "'2026-04-01T00:20:00Z')"))
    (db/initialize-database! db-path)
    (testing "the newest row per run becomes the semantic current record and older history stays unique"
      (is (= [{:assessment_id "assessment-older"
               :assessment_key "legacy:assessment-older"}
              {:assessment_id "assessment-newer"
               :assessment_key "validation/current"}]
             (mapv #(select-keys % [:assessment_id :assessment_key])
                   (query-rows db-path
                               (str "SELECT assessment_id, assessment_key "
                                    "FROM assessments WHERE run_id = ? ORDER BY created_at ASC")
                               ["run-1"]))))
      (is (= [{:disposition_id "disposition-older"
               :disposition_key "legacy:disposition-older"}
              {:disposition_id "disposition-newer"
               :disposition_key "decision/current"}]
             (mapv #(select-keys % [:disposition_id :disposition_key])
                   (query-rows db-path
                               (str "SELECT disposition_id, disposition_key "
                                    "FROM dispositions WHERE run_id = ? ORDER BY created_at ASC")
                               ["run-1"])))))
    (testing "post-migration semantic writes reuse the current rows instead of inserting duplicates"
      (is (= "assessment-newer"
             (:assessment/id (store.protocol/record-assessment! store
                                                                {:assessment/id "assessment-replayed"
                                                                 :assessment/run-id "run-1"
                                                                 :assessment/key "validation/current"
                                                                 :assessment/validator-ref {:definition/id :validator/required-paths
                                                                                            :definition/version 1}
                                                                 :assessment/outcome :assessment/accepted
                                                                 :assessment/checked-at "2026-04-01T00:30:00Z"}))))
      (is (= "disposition-newer"
             (:disposition/id (store.protocol/record-disposition! store
                                                                  {:disposition/id "disposition-replayed"
                                                                   :disposition/run-id "run-1"
                                                                   :disposition/key "decision/current"
                                                                   :disposition/action :disposition/accepted
                                                                   :disposition/decided-at "2026-04-01T00:30:00Z"}))))
      (is (= 1
             (query-single-value db-path
                                 "SELECT COUNT(*) FROM assessments WHERE run_id = 'run-1' AND assessment_key = 'validation/current'")))
      (is (= 1
             (query-single-value db-path
                                 "SELECT COUNT(*) FROM dispositions WHERE run_id = 'run-1' AND disposition_key = 'decision/current'"))))))
