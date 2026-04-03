(ns meta-flow.store.sqlite.run.events-unit-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.store.sqlite.run.rows :as run.rows]
            [meta-flow.store.sqlite.run.events :as run.events]))

(deftest retryable-exception-classifier-covers-lock-busy-and-seq-collision-codes
  (is (true? (run.events/retryable-event-ingest-exception? (org.sqlite.SQLiteException.
                                                            "database is locked"
                                                            org.sqlite.SQLiteErrorCode/SQLITE_BUSY))))
  (is (true? (run.events/retryable-event-ingest-exception? (org.sqlite.SQLiteException.
                                                            "share-cache lock"
                                                            org.sqlite.SQLiteErrorCode/SQLITE_LOCKED_SHAREDCACHE))))
  (is (true? (run.events/retryable-event-ingest-exception? (org.sqlite.SQLiteException.
                                                            "duplicate run event seq"
                                                            org.sqlite.SQLiteErrorCode/SQLITE_CONSTRAINT_PRIMARYKEY))))
  (is (false? (run.events/retryable-event-ingest-exception? (java.sql.SQLException. "syntax error"))))
  (is (false? (run.events/retryable-event-ingest-exception? nil))))

(deftest update-run-summary-prefers-newer-emitted-at-and-finds-artifact-ids-in-payload
  (let [updated-runs (atom [])]
    (with-redefs [run.rows/update-run-row! (fn [_ run-id update-fn]
                                             (let [existing {:run/id run-id
                                                             :run/updated-at "2026-04-01T00:05:00Z"}]
                                               (swap! updated-runs conj (update-fn existing {}))))]
      (run.events/update-run-summary-from-event! ::connection {:event/run-id "run-1"
                                                               :event/emitted-at "2026-04-01T00:10:00Z"
                                                               :event/payload {:artifact/id "artifact-1"}})
      (run.events/update-run-summary-from-event! ::connection {:event/run-id "run-1"
                                                               :event/emitted-at "2026-04-01T00:01:00Z"
                                                               :event/payload {:artifact-id "artifact-2"}}))
    (is (= [{:run/id "run-1"
             :run/updated-at "2026-04-01T00:10:00Z"
             :run/artifact-id "artifact-1"}
            {:run/id "run-1"
             :run/updated-at "2026-04-01T00:05:00Z"
             :run/artifact-id "artifact-2"}]
           @updated-runs))))

(deftest ingest-run-event-with-retry-reuses-existing-events-and-stops-on-non-retryable-errors
  (let [event-intent {:event/run-id "run-1"
                      :event/idempotency-key "idem-1"}]
    (testing "a duplicate discovered after an exception reuses the stored event"
      (let [attempts (atom 0)]
        (with-redefs [run.events/ingest-run-event-once! (fn [_ _]
                                                          (swap! attempts inc)
                                                          (throw (java.sql.SQLException. "database is locked")))
                      run.events/load-existing-run-event (fn [_ _ _]
                                                           {:event/idempotency-key "idem-1"})
                      run.events/retryable-event-ingest-exception? (fn [_] true)]
          (is (= {:event/idempotency-key "idem-1"}
                 (run.events/ingest-run-event-with-retry! "var/test.sqlite3" event-intent)))
          (is (= 1 @attempts)))))
    (testing "retryable errors are retried until they succeed"
      (let [attempts (atom 0)]
        (with-redefs [run.events/ingest-run-event-once! (fn [_ _]
                                                          (if (< (swap! attempts inc) 3)
                                                            (throw (java.sql.SQLException. "database is locked"))
                                                            {:event/seq 3}))
                      run.events/load-existing-run-event (fn [_ _ _] nil)
                      run.events/retryable-event-ingest-exception? (fn [_] true)]
          (is (= {:event/seq 3}
                 (run.events/ingest-run-event-with-retry! "var/test.sqlite3" event-intent)))
          (is (= 3 @attempts)))))
    (testing "non-retryable errors are rethrown immediately"
      (let [attempts (atom 0)]
        (with-redefs [run.events/ingest-run-event-once! (fn [_ _]
                                                          (swap! attempts inc)
                                                          (throw (java.sql.SQLException. "syntax error")))
                      run.events/load-existing-run-event (fn [_ _ _] nil)
                      run.events/retryable-event-ingest-exception? (fn [_] false)]
          (is (thrown-with-msg? java.sql.SQLException
                                #"syntax error"
                                (run.events/ingest-run-event-with-retry! "var/test.sqlite3" event-intent)))
          (is (= 1 @attempts)))))))
