(ns meta-flow.scheduler.validation
  (:require [meta-flow.control.events :as events]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.scheduler.state :as state]
            [meta-flow.service.validation :as service.validation]
            [meta-flow.store.protocol :as store.protocol]))

(def current-assessment-key
  "validation/current")

(def current-disposition-key
  "decision/current")

(defn assess-run!
  [{:keys [store defs-repo now]} run task]
  (let [run-id (:run/id run)
        existing-assessment (store.protocol/find-assessment-by-key store run-id current-assessment-key)
        existing-disposition (store.protocol/find-disposition-by-key store run-id current-disposition-key)
        assessment (or existing-assessment
                       (let [artifact-root (shared/run-artifact-root store run)
                             contract (defs.protocol/find-artifact-contract defs-repo
                                                                            (get-in task [:task/artifact-contract-ref :definition/id])
                                                                            (get-in task [:task/artifact-contract-ref :definition/version]))
                             outcome (service.validation/assess-required-paths artifact-root contract)
                             recorded {:assessment/id (shared/new-id)
                                       :assessment/run-id run-id
                                       :assessment/key current-assessment-key
                                       :assessment/validator-ref (:task/validator-ref task)
                                       :assessment/outcome (:assessment/outcome outcome)
                                       :assessment/missing-paths (:assessment/missing-paths outcome)
                                       :assessment/checks (:assessment/checks outcome)
                                       :assessment/notes (:assessment/notes outcome)
                                       :assessment/checked-at now}]
                         (store.protocol/record-assessment! store recorded)))
        disposition (or existing-disposition
                        (let [recorded {:disposition/id (shared/new-id)
                                        :disposition/run-id run-id
                                        :disposition/key current-disposition-key
                                        :disposition/decided-at now
                                        :disposition/action (if (= :assessment/accepted (:assessment/outcome assessment))
                                                              :disposition/accepted
                                                              :disposition/rejected)
                                        :disposition/notes (:assessment/notes assessment)}]
                          (store.protocol/record-disposition! store recorded)))]
    (if (= :assessment/accepted (:assessment/outcome assessment))
      (do
        (state/emit-event! store run events/run-assessment-accepted {:artifact/id (:run/artifact-id run)} now)
        (state/emit-event! store run events/task-assessment-accepted {:artifact/id (:run/artifact-id run)} now)
        (state/apply-event-stream! store defs-repo run task now))
      (do
        (state/emit-event! store run events/run-assessment-rejected
                           {:artifact/id (:run/artifact-id run)
                            :assessment/notes (:assessment/notes assessment)}
                           now)
        (state/emit-event! store run events/task-assessment-rejected
                           {:artifact/id (:run/artifact-id run)
                            :assessment/notes (:assessment/notes assessment)}
                           now)
        (state/apply-event-stream! store defs-repo run task now)))
    {:assessment assessment
     :disposition disposition}))
