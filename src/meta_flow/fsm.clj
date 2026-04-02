(ns meta-flow.fsm)

(defn- transition-list
  [fsm]
  (or (:task-fsm/transitions fsm)
      (:run-fsm/transitions fsm)))

(defn transition-to
  [fsm from-state event-type]
  (when-let [transition (some (fn [candidate]
                                (when (and (= (:transition/from candidate) from-state)
                                           (= (:transition/event candidate) event-type))
                                  candidate))
                              (transition-list fsm))]
    (:transition/to transition)))

(defn event-allowed?
  [fsm from-state event-type]
  (boolean (transition-to fsm from-state event-type)))

(defn ensure-transition!
  [fsm entity-kind entity-id from-state event-type]
  (or (transition-to fsm from-state event-type)
      (throw (ex-info (str "Invalid " (name entity-kind) " transition: no transition for event " event-type
                           " from state " from-state)
                      {:entity-kind entity-kind
                       :entity-id entity-id
                       :state from-state
                       :event event-type}))))

(defn- event-transition
  [fsm entity state event-type]
  (let [from-state (state entity)
        to-state (transition-to fsm from-state event-type)]
    (when to-state
      {:transition/from from-state
       :transition/to to-state})))

(defn apply-task-event
  [task-fsm task event-type]
  (event-transition task-fsm task :task/state event-type))

(defn apply-run-event
  [run-fsm run event-type]
  (event-transition run-fsm run :run/state event-type))
