(ns meta-flow.store.sqlite-run-data-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.store.sqlite.artifact.assessments :as assessments]
            [meta-flow.store.sqlite.artifact.core :as artifacts]
            [meta-flow.store.sqlite.artifact.dispositions :as dispositions]
            [meta-flow.store.sqlite.run-data :as run-data]
            [meta-flow.store.sqlite.run.events :as run-events]))

(deftest run-data-namespace-delegates-event-and-record-operations
  (let [calls (atom [])]
    (with-redefs [run-events/find-run-event-row (fn [connection run-id idempotency-key]
                                                  (swap! calls conj [:find-run-event-row connection run-id idempotency-key])
                                                  {:run-id run-id :idempotency-key idempotency-key})
                  run-events/next-event-seq (fn [connection run-id]
                                              (swap! calls conj [:next-event-seq connection run-id])
                                              7)
                  run-events/update-run-summary-from-event! (fn [connection event]
                                                              (swap! calls conj [:update-run-summary-from-event! connection event])
                                                              {:event event})
                  run-events/load-existing-run-event (fn [db-path run-id idempotency-key]
                                                       (swap! calls conj [:load-existing-run-event db-path run-id idempotency-key])
                                                       {:db-path db-path})
                  run-events/retryable-event-ingest-exception? (fn [throwable]
                                                                 (swap! calls conj [:retryable-event-ingest-exception? throwable])
                                                                 (= :retryable (:kind (ex-data throwable))))
                  run-events/ingest-run-event-via-connection! (fn [connection event-intent]
                                                                (swap! calls conj [:ingest-run-event-via-connection! connection event-intent])
                                                                {:event-intent event-intent})
                  run-events/ingest-run-event-once! (fn [db-path event-intent]
                                                      (swap! calls conj [:ingest-run-event-once! db-path event-intent])
                                                      {:mode :once})
                  run-events/ingest-run-event-with-retry! (fn [db-path event-intent]
                                                            (swap! calls conj [:ingest-run-event-with-retry! db-path event-intent])
                                                            {:mode :retry})
                  run-events/list-run-events (fn [db-path run-id]
                                               (swap! calls conj [:list-run-events db-path run-id])
                                               [{:run-id run-id}])
                  run-events/list-run-events-after (fn [db-path run-id event-seq]
                                                     (swap! calls conj [:list-run-events-after db-path run-id event-seq])
                                                     [{:run-id run-id :event-seq event-seq}])
                  artifacts/attach-artifact! (fn [db-path run-id artifact]
                                               (swap! calls conj [:attach-artifact! db-path run-id artifact])
                                               artifact)
                  artifacts/find-artifact (fn [db-path artifact-id]
                                            (swap! calls conj [:find-artifact db-path artifact-id])
                                            {:artifact/id artifact-id})
                  assessments/record-assessment! (fn [db-path assessment]
                                                   (swap! calls conj [:record-assessment! db-path assessment])
                                                   assessment)
                  assessments/find-assessment-by-key (fn [db-path run-id assessment-key]
                                                       (swap! calls conj [:find-assessment-by-key db-path run-id assessment-key])
                                                       {:assessment/key assessment-key})
                  dispositions/record-disposition! (fn [db-path disposition]
                                                     (swap! calls conj [:record-disposition! db-path disposition])
                                                     disposition)
                  dispositions/find-disposition-by-key (fn [db-path run-id disposition-key]
                                                         (swap! calls conj [:find-disposition-by-key db-path run-id disposition-key])
                                                         {:disposition/key disposition-key})]
      (let [connection ::connection
            db-path "var/test.sqlite3"
            event-intent {:event/type :run-dispatched}
            artifact {:artifact/id "artifact-1"}
            assessment {:assessment/id "assessment-1"}
            disposition {:disposition/id "disposition-1"}
            retryable (ex-info "retryable" {:kind :retryable})]
        (is (= {:run-id "run-1" :idempotency-key "idem-1"}
               (run-data/find-run-event-row connection "run-1" "idem-1")))
        (is (= 7 (run-data/next-event-seq connection "run-1")))
        (is (= {:event event-intent}
               (run-data/update-run-summary-from-event! connection event-intent)))
        (is (= {:db-path db-path}
               (run-data/load-existing-run-event db-path "run-1" "idem-1")))
        (is (true? (run-data/retryable-event-ingest-exception? retryable)))
        (is (= {:event-intent event-intent}
               (run-data/ingest-run-event-via-connection! connection event-intent)))
        (is (= {:mode :once}
               (run-data/ingest-run-event-once! db-path event-intent)))
        (is (= {:mode :retry}
               (run-data/ingest-run-event-with-retry! db-path event-intent)))
        (is (= [{:run-id "run-1"}]
               (run-data/list-run-events db-path "run-1")))
        (is (= [{:run-id "run-1" :event-seq 3}]
               (run-data/list-run-events-after db-path "run-1" 3)))
        (is (= artifact (run-data/attach-artifact! db-path "run-1" artifact)))
        (is (= {:artifact/id "artifact-1"} (run-data/find-artifact db-path "artifact-1")))
        (is (= assessment (run-data/record-assessment! db-path assessment)))
        (is (= {:assessment/key "validation/current"}
               (run-data/find-assessment-by-key db-path "run-1" "validation/current")))
        (is (= disposition (run-data/record-disposition! db-path disposition)))
        (is (= {:disposition/key "decision/current"}
               (run-data/find-disposition-by-key db-path "run-1" "decision/current")))))
    (is (= 16 (count @calls)))))
