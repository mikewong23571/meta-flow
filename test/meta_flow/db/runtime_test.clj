(ns meta-flow.db.runtime-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.db :as db]))

(deftest runtime-directory-and-keyword-helpers-cover-local-utilities
  (let [temp-dir (java.nio.file.Files/createTempDirectory "meta-flow-db-runtime"
                                                          (make-array java.nio.file.attribute.FileAttribute 0))
        root (.toFile temp-dir)
        runtime-dirs [(str root "/artifacts")
                      (str root "/runs")
                      (str root "/codex-home")]]
    (with-redefs [db/runtime-directories runtime-dirs]
      (is (= runtime-dirs (db/ensure-runtime-directories!)))
      (doseq [directory runtime-dirs]
        (is (.exists (java.io.File. directory)))))
    (is (= ":task.state/queued" (db/keyword-text :task.state/queued)))
    (is (nil? (db/keyword-text nil)))
    (is (= "42" (db/keyword-text 42)))))

(deftest migration-resource-helpers-cover-success-and-error-paths
  (testing "resource directory and migration metadata load from bundled SQL resources"
    (let [resource-dir (#'db/resource-directory! db/default-sql-resource-base)
          resource-paths (#'db/migration-resource-paths)
          statements (#'db/migration-statements! "meta_flow/sql/001_init.sql")]
      (is (.exists resource-dir))
      (is (= ["meta_flow/sql/001_init.sql"
              "meta_flow/sql/002_align_keyword_literals.sql"
              "meta_flow/sql/003_add_semantic_idempotency_keys.sql"]
             resource-paths))
      (is (= "003_add_semantic_idempotency_keys"
             (#'db/migration-id "meta_flow/sql/003_add_semantic_idempotency_keys.sql")))
      (is (seq statements))
      (is (some #(str/includes? % "CREATE TABLE IF NOT EXISTS tasks") statements))))
  (testing "missing resources produce actionable ex-info"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing SQL resource directory"
                          (#'db/resource-directory! "meta_flow/sql/missing")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing SQL resource"
                          (#'db/migration-statements! "meta_flow/sql/missing.sql")))))

(deftest close-all-data-sources-closes-and-evicts-all-cached-pools
  (is (integer? (db/close-all-data-sources!)))
  (is (<= 0 (db/close-all-data-sources!)))
  (is (= 0 (db/close-all-data-sources!))))
