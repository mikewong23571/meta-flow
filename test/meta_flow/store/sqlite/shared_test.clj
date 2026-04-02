(ns meta-flow.store.sqlite.shared-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.shared :as shared]))

(deftest required-entity-accessors-enforce-keys-and-definition-refs
  (is (= "task-1" (shared/require-key! {:task/id "task-1"} :task/id)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Missing required entity key :task/id"
                        (shared/require-key! {} :task/id)))
  (is (= {:definition/id :runtime-profile/mock-worker
          :definition/version 1}
         (shared/require-definition-ref! {:task/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                                                     :definition/version 1}}
                                         :task/runtime-profile-ref)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Missing required definition ref :task/runtime-profile-ref"
                        (shared/require-definition-ref! {} :task/runtime-profile-ref))))

(deftest matching-values-and-event-intents-validate-boundaries
  (is (= "run-1"
         (shared/require-matching-value! {:run/id "run-1"} :run/id "run-1")))
  (is (= "run-1"
         (shared/require-matching-value! {} :run/id "run-1")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Mismatched entity key :run/id"
                        (shared/require-matching-value! {:run/id "run-2"} :run/id "run-1")))
  (let [event-intent {:event/run-id "run-1"
                      :event/type :event/run-dispatched
                      :event/payload {:worker/id "w1"}
                      :event/caused-by {:actor/type :worker}
                      :event/idempotency-key "idem-1"}]
    (is (= event-intent (shared/validate-event-intent! event-intent)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing required event key :event/payload"
                          (shared/validate-event-intent! (assoc event-intent :event/payload nil))))))

(deftest normalizers-fill-timestamps-and-preserve-existing-values
  (with-redefs [sql/utc-now (constantly "2026-04-02T00:00:00Z")]
    (testing "task, run, lease, and collection state reuse created-at for updated-at by default"
      (is (= {:task/id "task-1"
              :task/created-at "2026-04-02T00:00:00Z"
              :task/updated-at "2026-04-02T00:00:00Z"}
             (shared/normalize-task {:task/id "task-1"})))
      (is (= {:run/id "run-1"
              :run/lease-id "lease-1"
              :run/created-at "2026-04-02T00:00:00Z"
              :run/updated-at "2026-04-02T00:00:00Z"}
             (shared/normalize-run {:run/id "run-1"} "lease-1")))
      (is (= {:lease/id "lease-1"
              :lease/created-at "2026-04-02T00:00:00Z"
              :lease/updated-at "2026-04-02T00:00:00Z"}
             (shared/normalize-lease {:lease/id "lease-1"})))
      (is (= {:collection/id :collection/default
              :collection/created-at "2026-04-01T00:00:00Z"
              :collection/updated-at "2026-04-02T00:00:00Z"}
             (shared/normalize-collection-state {:collection/id :collection/default
                                                 :collection/updated-at "2026-04-02T00:00:00Z"}
                                                "2026-04-01T00:00:00Z"))))
    (testing "artifact, assessment, disposition, and event timestamps default from now or created-at"
      (is (= {:artifact/id "artifact-1"
              :artifact/created-at "2026-04-02T00:00:00Z"}
             (shared/normalize-artifact {:artifact/id "artifact-1"})))
      (is (= {:assessment/id "assessment-1"
              :assessment/created-at "2026-04-01T00:00:00Z"
              :assessment/checked-at "2026-04-01T00:00:00Z"}
             (shared/normalize-assessment {:assessment/id "assessment-1"
                                           :assessment/created-at "2026-04-01T00:00:00Z"})))
      (is (= {:disposition/id "disposition-1"
              :disposition/created-at "2026-04-01T00:00:00Z"
              :disposition/decided-at "2026-04-01T00:00:00Z"}
             (shared/normalize-disposition {:disposition/id "disposition-1"
                                            :disposition/created-at "2026-04-01T00:00:00Z"})))
      (is (= {:event/run-id "run-1"
              :event/seq 3
              :event/emitted-at "2026-04-02T00:00:00Z"}
             (shared/normalize-event {:event/run-id "run-1"} 3))))))

(deftest refs-and-row-converters-map-between-storage-and-entities
  (is (= ":runtime-profile/mock-worker"
         (shared/ref-id {:task/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                                    :definition/version 1}}
                        :task/runtime-profile-ref)))
  (is (= 1
         (shared/ref-version {:task/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                                         :definition/version 1}}
                             :task/runtime-profile-ref)))
  (is (= {:notes ["ok"]}
         (shared/parse-edn-column {:payload "{:notes [\"ok\"]}"} :payload)))
  (is (= {:run/id "run-1"
          :run/task-id "task-1"
          :run/attempt 2
          :run/state :run.state/dispatched
          :run/lease-id "lease-1"
          :run/artifact-id "artifact-1"
          :run/created-at "2026-04-01T00:00:00Z"
          :run/updated-at "2026-04-01T00:01:00Z"}
         (shared/run-row->entity {:run_edn "{:run/id \"run-1\"}"
                                  :task_id "task-1"
                                  :attempt 2
                                  :state ":run.state/dispatched"
                                  :lease_id "lease-1"
                                  :artifact_id "artifact-1"
                                  :created_at "2026-04-01T00:00:00Z"
                                  :updated_at "2026-04-01T00:01:00Z"})))
  (is (= {:collection/id :collection/default
          :collection/created-at "2026-04-01T00:00:00Z"
          :collection/updated-at "2026-04-01T00:01:00Z"}
         (shared/collection-state-row->entity {:state_edn "{:collection/id :collection/default}"
                                               :created_at "2026-04-01T00:00:00Z"
                                               :updated_at "2026-04-01T00:01:00Z"}))))

(deftest transitioned-entity-prefers-explicit-entities-before-merging-changes
  (let [existing {:task/id "task-1"
                  :task/state :task.state/queued
                  :task/updated-at "2026-04-01T00:00:00Z"}]
    (is (= {:task/id "task-explicit"}
           (shared/build-transitioned-entity existing
                                             :task
                                             :task/state
                                             :task.state/running
                                             "2026-04-02T00:00:00Z"
                                             {:entity {:task/id "task-explicit"}})))
    (is (= {:task/id "task-from-transition"}
           (shared/build-transitioned-entity existing
                                             :task
                                             :task/state
                                             :task.state/running
                                             "2026-04-02T00:00:00Z"
                                             {:task {:task/id "task-from-transition"}})))
    (is (= {:task/id "task-1"
            :task/state :task.state/running
            :task/updated-at "2026-04-02T00:00:00Z"
            :task/error "none"}
           (shared/build-transitioned-entity existing
                                             :task
                                             :task/state
                                             :task.state/running
                                             "2026-04-02T00:00:00Z"
                                             {:changes {:task/error "none"}})))))
