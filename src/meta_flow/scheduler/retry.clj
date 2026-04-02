(ns meta-flow.scheduler.retry
  (:require [meta-flow.control.events :as events]
            [meta-flow.control.fsm :as fsm]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.scheduler.state :as state]
            [meta-flow.store.protocol :as store.protocol]))

(defn retry-action
  [policy latest-run]
  (let [max-attempts (long (or (:resource-policy/max-attempts policy) 1))
        latest-attempt (long (or (:run/attempt latest-run) 0))]
    (if (< latest-attempt max-attempts)
      :retry.action/requeue
      :retry.action/needs-review)))

(defn- task-transition
  ([defs-repo task event-type]
   (task-transition defs-repo task event-type nil))
  ([defs-repo task event-type changes]
   (cond-> {:transition/from (:task/state task)
            :transition/to (fsm/ensure-transition! (state/task-fsm defs-repo task)
                                                   :task
                                                   (:task/id task)
                                                   (:task/state task)
                                                   event-type)}
     changes (assoc :changes changes))))

(defn process-retryable-failures!
  [{:keys [store defs-repo now]} task-ids]
  (let [requeued-task-ids (atom [])
        escalated-task-ids (atom [])]
    (doseq [task-id task-ids]
      (when-let [task (store.protocol/find-task store task-id)]
        (when (= :task.state/retryable-failed (:task/state task))
          (when-let [latest-run (shared/latest-run store task-id)]
            (let [policy (shared/task-policy defs-repo task)
                  action (retry-action policy latest-run)
                  transition (case action
                               :retry.action/requeue
                               (task-transition defs-repo task events/task-requeued
                                                {:task/last-applied-event-seq 0})
                               :retry.action/needs-review
                               (task-transition defs-repo task events/task-retry-exhausted))]
              (when-let [updated-task (store.protocol/transition-task! store task-id transition now)]
                (case action
                  :retry.action/requeue
                  (swap! requeued-task-ids conj (:task/id updated-task))
                  :retry.action/needs-review
                  (swap! escalated-task-ids conj (:task/id updated-task)))))))))
    {:requeued-task-ids @requeued-task-ids
     :escalated-task-ids @escalated-task-ids}))
