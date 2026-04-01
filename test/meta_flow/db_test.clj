(ns meta-flow.db-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.db :as db]))

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
