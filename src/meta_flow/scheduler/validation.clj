(ns meta-flow.scheduler.validation
  (:require [meta-flow.control.events :as events]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.scheduler.state :as state]
            [meta-flow.sql :as sql]
            [meta-flow.service.validation :as service.validation]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]
            [meta-flow.store.sqlite.artifact.assessments :as assessments]
            [meta-flow.store.sqlite.artifact.dispositions :as dispositions]
            [meta-flow.store.sqlite.run.events :as run-events]
            [meta-flow.store.sqlite.shared :as store.sqlite.shared]))

(def current-assessment-key
  "validation/current")

(def current-disposition-key
  "decision/current")

(defn- unsupported-operation
  [operation]
  (throw (ex-info "Unsupported operation for validation transaction store"
                  {:operation operation})))

(defn- sqlite-connection-store
  [connection]
  (reify store.protocol/StateStore
    (upsert-collection-state! [_ _]
      (unsupported-operation :upsert-collection-state!))
    (find-collection-state [_ _]
      (unsupported-operation :find-collection-state))
    (enqueue-task! [_ _]
      (unsupported-operation :enqueue-task!))
    (find-task [_ task-id]
      (some-> (store.sqlite/find-task-row connection task-id)
              (store.sqlite.shared/parse-edn-column :task_edn)))
    (find-task-by-work-key [_ _]
      (unsupported-operation :find-task-by-work-key))
    (create-run! [_ _ _ _]
      (unsupported-operation :create-run!))
    (claim-task-for-run! [_ _ _ _ _ _ _]
      (unsupported-operation :claim-task-for-run!))
    (recover-run-startup-failure! [_ _ _ _]
      (unsupported-operation :recover-run-startup-failure!))
    (find-run [_ run-id]
      (some-> (store.sqlite/find-run-row connection run-id)
              store.sqlite.shared/run-row->entity))
    (find-latest-run-for-task [_ _]
      (unsupported-operation :find-latest-run-for-task))
    (ingest-run-event! [_ event-intent]
      (store.sqlite/ingest-run-event-via-connection! connection event-intent))
    (list-run-events [_ run-id]
      (run-events/list-run-events-after-via-connection! connection run-id 0))
    (list-run-events-after [_ run-id event-seq]
      (run-events/list-run-events-after-via-connection! connection run-id event-seq))
    (attach-artifact! [_ _ _]
      (unsupported-operation :attach-artifact!))
    (find-artifact [_ _]
      (unsupported-operation :find-artifact))
    (record-assessment! [_ assessment]
      (assessments/record-assessment-via-connection! connection assessment))
    (find-assessment-by-key [_ run-id assessment-key]
      (some-> (assessments/find-assessment-row connection run-id assessment-key)
              (store.sqlite.shared/parse-edn-column :assessment_edn)))
    (record-disposition! [_ disposition]
      (dispositions/record-disposition-via-connection! connection disposition))
    (find-disposition-by-key [_ run-id disposition-key]
      (some-> (dispositions/find-disposition-row connection run-id disposition-key)
              (store.sqlite.shared/parse-edn-column :disposition_edn)))
    (transition-task! [_ task-id transition now]
      (store.sqlite/transition-task-via-connection! connection task-id transition now))
    (transition-run! [_ run-id transition now]
      (store.sqlite/transition-run-via-connection! connection run-id transition now))))

(defn assess-run!
  [{:keys [db-path store defs-repo now]} run task]
  (let [run-id (:run/id run)]
    (sql/with-transaction db-path
      (fn [connection]
        (let [tx-store (sqlite-connection-store connection)
              current-run (or (store.protocol/find-run tx-store run-id) run)
              current-task (or (store.protocol/find-task tx-store (:task/id task)) task)
              existing-assessment (store.protocol/find-assessment-by-key tx-store run-id current-assessment-key)
              assessment (or existing-assessment
                             (let [artifact-root (shared/run-artifact-root store current-run)
                                   contract (defs.protocol/find-artifact-contract defs-repo
                                                                                  (get-in current-task [:task/artifact-contract-ref :definition/id])
                                                                                  (get-in current-task [:task/artifact-contract-ref :definition/version]))
                                   outcome (service.validation/assess-required-paths artifact-root contract)
                                   recorded {:assessment/id (shared/new-id)
                                             :assessment/run-id run-id
                                             :assessment/key current-assessment-key
                                             :assessment/validator-ref (:task/validator-ref current-task)
                                             :assessment/outcome (:assessment/outcome outcome)
                                             :assessment/missing-paths (:assessment/missing-paths outcome)
                                             :assessment/checks (:assessment/checks outcome)
                                             :assessment/notes (:assessment/notes outcome)
                                             :assessment/checked-at now}]
                               (store.protocol/record-assessment! tx-store recorded)))
              existing-disposition (store.protocol/find-disposition-by-key tx-store run-id current-disposition-key)
              disposition (or existing-disposition
                              (let [recorded {:disposition/id (shared/new-id)
                                              :disposition/run-id run-id
                                              :disposition/key current-disposition-key
                                              :disposition/decided-at now
                                              :disposition/action (if (= :assessment/accepted (:assessment/outcome assessment))
                                                                    :disposition/accepted
                                                                    :disposition/rejected)
                                              :disposition/notes (:assessment/notes assessment)}]
                                (store.protocol/record-disposition! tx-store recorded)))]
          (if (= :assessment/accepted (:assessment/outcome assessment))
            (do
              (state/emit-event! tx-store current-run events/run-assessment-accepted {:artifact/id (:run/artifact-id current-run)} now)
              (state/emit-event! tx-store current-run events/task-assessment-accepted {:artifact/id (:run/artifact-id current-run)} now)
              (state/apply-event-stream! tx-store defs-repo current-run current-task now))
            (do
              (state/emit-event! tx-store current-run events/run-assessment-rejected
                                 {:artifact/id (:run/artifact-id current-run)
                                  :assessment/notes (:assessment/notes assessment)}
                                 now)
              (state/emit-event! tx-store current-run events/task-assessment-rejected
                                 {:artifact/id (:run/artifact-id current-run)
                                  :assessment/notes (:assessment/notes assessment)}
                                 now)
              (state/apply-event-stream! tx-store defs-repo current-run current-task now)))
          {:assessment assessment
           :disposition disposition})))))
