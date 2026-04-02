(ns meta-flow.store.sqlite-records-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite-test-support :as support]))

(deftest store-read-boundaries-return-protocol-entities
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task-entity (store.protocol/enqueue-task! store (support/task "task-read" "work/read" now))
        _ (store.protocol/upsert-collection-state! store (support/collection-state true now))
        _ (store.protocol/create-run! store task-entity
                                      (support/run "run-read-1" 1 now)
                                      (support/lease "lease-read-1" "run-read-1" "2026-04-01T00:10:00Z" now))
        _ (support/execute-sql! db-path
                                "UPDATE runs SET state = ':run.state/finalized' WHERE run_id = 'run-read-1'")
        _ (store.protocol/create-run! store task-entity
                                      (support/run "run-read-2" 2 "2026-04-01T00:01:00Z")
                                      (support/lease "lease-read-2" "run-read-2" "2026-04-01T00:11:00Z" "2026-04-01T00:01:00Z"))
        artifact {:artifact/id "artifact-read"
                  :artifact/run-id "run-read-2"
                  :artifact/task-id "task-read"
                  :artifact/contract-ref {:definition/id :artifact-contract/default
                                          :definition/version 1}
                  :artifact/location "/tmp/artifact-read"
                  :artifact/created-at "2026-04-01T00:02:00Z"}
        assessment {:assessment/id "assessment-read"
                    :assessment/run-id "run-read-2"
                    :assessment/key "validation/current"
                    :assessment/validator-ref {:definition/id :validator/required-paths
                                               :definition/version 1}
                    :assessment/outcome :assessment/accepted
                    :assessment/checked-at "2026-04-01T00:03:00Z"}
        disposition {:disposition/id "disposition-read"
                     :disposition/run-id "run-read-2"
                     :disposition/key "decision/current"
                     :disposition/action :disposition/accepted
                     :disposition/decided-at "2026-04-01T00:04:00Z"}
        stored-artifact (store.protocol/attach-artifact! store "run-read-2" artifact)
        stored-assessment (store.protocol/record-assessment! store assessment)
        stored-disposition (store.protocol/record-disposition! store disposition)]
    (is (true? (get-in (store.protocol/find-collection-state store :collection/default)
                       [:collection/dispatch :dispatch/paused?])))
    (is (= stored-artifact
           (store.protocol/find-artifact store "artifact-read")))
    (is (= stored-assessment
           (store.protocol/find-assessment-by-key store "run-read-2" "validation/current")))
    (is (= stored-disposition
           (store.protocol/find-disposition-by-key store "run-read-2" "decision/current")))
    (is (= "run-read-2"
           (:run/id (store.protocol/find-latest-run-for-task store "task-read"))))))

(deftest attach-artifact-rejects-mismatched-run-id
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (store.protocol/enqueue-task! store (support/task "task-1" "work/cve-artifact-1" now))
        task-2 (store.protocol/enqueue-task! store (support/task "task-2" "work/cve-artifact-2" now))]
    (store.protocol/create-run! store task-1
                                (support/run "run-1" 1 now)
                                (support/lease "lease-1" "run-1" "2026-04-01T00:10:00Z" now))
    (store.protocol/create-run! store task-2
                                (support/run "run-2" 1 now)
                                (support/lease "lease-2" "run-2" "2026-04-01T00:11:00Z" now))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"artifact/run-id"
                          (store.protocol/attach-artifact! store "run-2"
                                                           {:artifact/id "artifact-1"
                                                            :artifact/run-id "run-1"
                                                            :artifact/task-id "task-1"
                                                            :artifact/contract-ref {:definition/id :artifact-contract/default
                                                                                    :definition/version 1}
                                                            :artifact/location "/tmp/artifact-1"
                                                            :artifact/created-at "2026-04-01T00:20:00Z"})))
    (is (= 0
           (support/query-single-value db-path "SELECT COUNT(*) FROM artifacts")))
    (is (nil? (support/query-single-value db-path "SELECT artifact_id FROM runs WHERE run_id = 'run-2'")))))

(deftest attach-artifact-rejects-mismatched-task-id
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task-1 (store.protocol/enqueue-task! store (support/task "task-1" "work/cve-artifact-task-1" now))
        task-2 (store.protocol/enqueue-task! store (support/task "task-2" "work/cve-artifact-task-2" now))]
    (store.protocol/create-run! store task-1
                                (support/run "run-1" 1 now)
                                (support/lease "lease-1" "run-1" "2026-04-01T00:10:00Z" now))
    (store.protocol/create-run! store task-2
                                (support/run "run-2" 1 now)
                                (support/lease "lease-2" "run-2" "2026-04-01T00:11:00Z" now))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"artifact/task-id"
                          (store.protocol/attach-artifact! store "run-2"
                                                           {:artifact/id "artifact-1"
                                                            :artifact/run-id "run-2"
                                                            :artifact/task-id "task-1"
                                                            :artifact/contract-ref {:definition/id :artifact-contract/default
                                                                                    :definition/version 1}
                                                            :artifact/location "/tmp/artifact-1"
                                                            :artifact/created-at "2026-04-01T00:20:00Z"})))
    (is (= 0
           (support/query-single-value db-path "SELECT COUNT(*) FROM artifacts")))
    (is (nil? (support/query-single-value db-path "SELECT artifact_id FROM runs WHERE run_id = 'run-2'")))))

(deftest record-assessment-is-idempotent-by-run-and-assessment-key
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task-entity (store.protocol/enqueue-task! store (support/task "task-assessment" "work/assessment" now))
        _ (store.protocol/create-run! store task-entity
                                      (support/run "run-assessment" 1 now)
                                      (support/lease "lease-assessment" "run-assessment" "2026-04-01T00:10:00Z" now))
        first {:assessment/id "assessment-1"
               :assessment/run-id "run-assessment"
               :assessment/key "validation/current"
               :assessment/validator-ref {:definition/id :validator/required-paths
                                          :definition/version 1}
               :assessment/outcome :assessment/accepted
               :assessment/notes ["accepted"]
               :assessment/checked-at now}
        duplicate (assoc first
                         :assessment/id "assessment-2"
                         :assessment/outcome :assessment/rejected
                         :assessment/notes ["should-not-overwrite"]
                         :assessment/checked-at "2026-04-01T00:01:00Z")]
    (is (= first
           (store.protocol/record-assessment! store first)))
    (is (= first
           (store.protocol/record-assessment! store duplicate)))
    (is (= 1
           (support/query-single-value db-path "SELECT COUNT(*) FROM assessments")))
    (is (= "validation/current"
           (support/query-single-value db-path "SELECT assessment_key FROM assessments WHERE run_id = 'run-assessment'")))
    (is (= ":assessment/accepted"
           (support/query-single-value db-path "SELECT status FROM assessments WHERE run_id = 'run-assessment'")))))

(deftest record-disposition-is-idempotent-by-run-and-disposition-key
  (let [{:keys [db-path store]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task-entity (store.protocol/enqueue-task! store (support/task "task-disposition" "work/disposition" now))
        _ (store.protocol/create-run! store task-entity
                                      (support/run "run-disposition" 1 now)
                                      (support/lease "lease-disposition" "run-disposition" "2026-04-01T00:10:00Z" now))
        first {:disposition/id "disposition-1"
               :disposition/run-id "run-disposition"
               :disposition/key "decision/current"
               :disposition/action :disposition/accepted
               :disposition/notes ["accepted"]
               :disposition/decided-at now}
        duplicate (assoc first
                         :disposition/id "disposition-2"
                         :disposition/action :disposition/rejected
                         :disposition/notes ["should-not-overwrite"]
                         :disposition/decided-at "2026-04-01T00:01:00Z")]
    (is (= first
           (store.protocol/record-disposition! store first)))
    (is (= first
           (store.protocol/record-disposition! store duplicate)))
    (is (= 1
           (support/query-single-value db-path "SELECT COUNT(*) FROM dispositions")))
    (is (= "decision/current"
           (support/query-single-value db-path "SELECT disposition_key FROM dispositions WHERE run_id = 'run-disposition'")))
    (is (= ":disposition/accepted"
           (support/query-single-value db-path "SELECT disposition_type FROM dispositions WHERE run_id = 'run-disposition'")))))
