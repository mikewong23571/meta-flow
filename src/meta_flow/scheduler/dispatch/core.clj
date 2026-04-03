(ns meta-flow.scheduler.dispatch.core
  (:require [meta-flow.control.projection :as projection]
            [meta-flow.control.projection.snapshot :as projection.snapshot]
            [meta-flow.scheduler.runtime :as runtime]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.store.protocol :as store.protocol]))

(def ^:private default-runnable-limit
  100)

(defn cooldown-active?
  [cooldown-until now]
  (projection.snapshot/cooldown-active? cooldown-until now))

(defn dispatch-cooldown-until
  [collection-state]
  (projection.snapshot/dispatch-cooldown-until collection-state))

(defn dispatch-block-reason
  [collection-state now]
  (cond
    (:dispatch/paused? (:collection/dispatch collection-state))
    :dispatch.block/paused

    (cooldown-active? (dispatch-cooldown-until collection-state) now)
    :dispatch.block/cooldown

    :else
    nil))

(defn dispatch-blocked?
  [collection-state now]
  (boolean (dispatch-block-reason collection-state now)))

(defn task-error
  [task throwable]
  {:task/id (:task/id task)
   :task/work-key (:task/work-key task)
   :error/message (.getMessage throwable)
   :error/data (ex-data throwable)})

(defn- task-queue-order
  [defs-repo task]
  (:resource-policy/queue-order (shared/task-policy defs-repo task)))

(defn- task-sort-key
  [defs-repo {:keys [task index]}]
  (case (task-queue-order defs-repo task)
    :resource-policy.queue-order/work-key
    [0 (:task/work-key task) (:task/created-at task) (:task/id task)]

    :resource-policy.queue-order/created-at
    [1 (:task/created-at task) (:task/work-key task) (:task/id task)]

    [2 index (:task/id task)]))

(defn order-runnable-task-ids
  [store defs-repo task-ids]
  (->> task-ids
       (map-indexed (fn [index task-id]
                      (when-let [task (store.protocol/find-task store task-id)]
                        {:index index
                         :task-id task-id
                         :task task})))
       (keep identity)
       (sort-by #(task-sort-key defs-repo %))
       (mapv :task-id)))

(defn- resource-active-count
  [reader resource-policy-ref now cached-counts]
  (if-some [count-now (get @cached-counts resource-policy-ref)]
    count-now
    (let [count-now (projection/count-active-runs-for-resource-policy reader
                                                                      resource-policy-ref
                                                                      now)]
      (swap! cached-counts assoc resource-policy-ref count-now)
      count-now)))

(defn- increment-resource-active-count!
  [cached-counts resource-policy-ref]
  (swap! cached-counts update resource-policy-ref (fnil inc 0)))

(defn dispatch-runnable-tasks!
  [{:keys [reader now store defs-repo] :as env} collection-state snapshot]
  (let [active-count (projection/count-active-runs reader now)
        max-active (or (:resource-policy/max-active-runs
                        (shared/collection-policy defs-repo collection-state))
                       1)
        runnable-limit (max default-runnable-limit
                            (long (or (:snapshot/runnable-count snapshot) 0)))
        created-runs (atom [])
        task-errors (atom [])
        capacity-skipped-task-ids (atom [])
        dispatch-block-reason-now (dispatch-block-reason collection-state now)
        cached-resource-counts (atom {})]
    (when-not dispatch-block-reason-now
      (loop [remaining (max 0 (- max-active active-count))
             runnable-ids (order-runnable-task-ids store
                                                   defs-repo
                                                   (projection/list-runnable-task-ids reader
                                                                                      now
                                                                                      runnable-limit))]
        (when (and (pos? remaining) (seq runnable-ids))
          (let [task-id (first runnable-ids)
                task (store.protocol/find-task store task-id)]
            (cond
              (nil? task)
              (recur remaining (rest runnable-ids))

              (not= :task.state/queued (:task/state task))
              (recur remaining (rest runnable-ids))

              :else
              (let [resource-policy-ref (:task/resource-policy-ref task)
                    policy (shared/task-policy defs-repo task)
                    resource-limit (long (or (:resource-policy/max-active-runs policy) 1))
                    resource-active-now (resource-active-count reader
                                                               resource-policy-ref
                                                               now
                                                               cached-resource-counts)]
                (if (>= resource-active-now resource-limit)
                  (do
                    (swap! capacity-skipped-task-ids conj task-id)
                    (recur remaining (rest runnable-ids)))
                  (let [created-run (try
                                      (runtime/create-run! env task)
                                      (catch Throwable throwable
                                        (swap! task-errors conj (task-error task throwable))
                                        nil))]
                    (when created-run
                      (swap! created-runs conj created-run)
                      (increment-resource-active-count! cached-resource-counts resource-policy-ref))
                    (recur (if created-run
                             (dec remaining)
                             remaining)
                           (rest runnable-ids))))))))))
    {:created-runs @created-runs
     :task-errors @task-errors
     :dispatch-block-reason dispatch-block-reason-now
     :capacity-skipped-task-ids @capacity-skipped-task-ids}))
