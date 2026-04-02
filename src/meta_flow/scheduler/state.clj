(ns meta-flow.scheduler.state
  (:require [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.fsm :as fsm]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.store.protocol :as store.protocol]))

(def terminal-run-states
  #{:run.state/finalized
    :run.state/retryable-failed})

(defn task-fsm
  [defs-repo task]
  (defs.protocol/find-task-fsm-def defs-repo
                                   (get-in task [:task/task-fsm-ref :definition/id])
                                   (get-in task [:task/task-fsm-ref :definition/version])))

(defn run-fsm
  [defs-repo run]
  (defs.protocol/find-run-fsm-def defs-repo
                                  (get-in run [:run/run-fsm-ref :definition/id])
                                  (get-in run [:run/run-fsm-ref :definition/version])))

(defn task-transition-for-event
  [defs-repo task event-type]
  (fsm/apply-task-event (task-fsm defs-repo task) task event-type))

(defn run-transition-for-event
  [defs-repo run event-type]
  (fsm/apply-run-event (run-fsm defs-repo run) run event-type))

(defn terminal-run-state?
  [run]
  (contains? terminal-run-states (:run/state run)))

(defn apply-task-event!
  [store defs-repo task event now-value]
  (if-let [transition (task-transition-for-event defs-repo task (:event/type event))]
    (or (store.protocol/transition-task! store (:task/id task) transition now-value)
        task)
    task))

(defn apply-run-event!
  [store defs-repo run event now-value]
  (if-let [transition (run-transition-for-event defs-repo run (:event/type event))]
    (or (store.protocol/transition-run! store (:run/id run) transition now-value)
        run)
    run))

(defn event-seq
  [event]
  (long (or (:event/seq event) 0)))

(defn apply-run-event-stream!
  [store defs-repo run now-value]
  (let [watermark (long (or (:run/last-applied-event-seq run) 0))
        events (store.protocol/list-run-events-after store (:run/id run) watermark)]
    (if (seq events)
      (let [applied-run (reduce (fn [current-run event]
                                  (apply-run-event! store defs-repo current-run event now-value))
                                run
                                events)
            max-seq (reduce max watermark (map event-seq events))]
        (or (store.protocol/transition-run! store (:run/id applied-run)
                                            {:transition/from (:run/state applied-run)
                                             :transition/to (:run/state applied-run)
                                             :changes {:run/last-applied-event-seq max-seq}}
                                            now-value)
            (assoc applied-run :run/last-applied-event-seq max-seq)))
      run)))

(defn apply-task-event-stream!
  [store defs-repo task run-id now-value]
  (let [watermark (long (or (:task/last-applied-event-seq task) 0))
        events (store.protocol/list-run-events-after store run-id watermark)]
    (if (seq events)
      (let [applied-task (reduce (fn [current-task event]
                                   (apply-task-event! store defs-repo current-task event now-value))
                                 task
                                 events)
            max-seq (reduce max watermark (map event-seq events))]
        (or (store.protocol/transition-task! store (:task/id applied-task)
                                             {:transition/from (:task/state applied-task)
                                              :transition/to (:task/state applied-task)
                                              :changes {:task/last-applied-event-seq max-seq}}
                                             now-value)
            (assoc applied-task :task/last-applied-event-seq max-seq)))
      task)))

(defn apply-event-stream!
  [store defs-repo run task now-value]
  {:run (apply-run-event-stream! store defs-repo run now-value)
   :task (apply-task-event-stream! store defs-repo task (:run/id run) now-value)})

(defn scheduler-event-intent
  [run event-type payload now-value]
  {:event/run-id (:run/id run)
   :event/type event-type
   :event/payload payload
   :event/caused-by {:actor/type :scheduler
                     :actor/id "meta-flow-scheduler"}
   :event/idempotency-key (str "scheduler:" (:run/id run) ":" event-type)
   :event/emitted-at now-value})

(defn emit-event!
  [store run event-type payload now-value]
  (event-ingest/ingest-run-event! store
                                  (scheduler-event-intent run event-type payload now-value)))
