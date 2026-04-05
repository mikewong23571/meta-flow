(ns meta-flow.ui.defs
  (:require [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]))

(defn- resolve-ref
  [defs-repo find-fn ref]
  (when ref
    (find-fn defs-repo (:definition/id ref) (:definition/version ref))))

(defn- base-ref-summary
  [ref definition name-key]
  (cond-> {:definition/id (:definition/id ref)
           :definition/version (:definition/version ref)}
    definition (assoc :definition/name (name-key definition))))

(defn- assoc-when-defined
  [m definition key-name]
  (if (contains? definition key-name)
    (assoc m key-name (get definition key-name))
    m))

(defn- task-fsm-summary
  [defs-repo ref]
  (let [definition (resolve-ref defs-repo defs.protocol/find-task-fsm-def ref)]
    (cond-> (base-ref-summary ref definition :task-fsm/name)
      definition (assoc :task-fsm/initial-state (:task-fsm/initial-state definition)
                        :task-fsm/state-count (count (:task-fsm/states definition))
                        :task-fsm/terminal-state-count (count (:task-fsm/terminal-states definition))))))

(defn- run-fsm-summary
  [defs-repo ref]
  (let [definition (resolve-ref defs-repo defs.protocol/find-run-fsm-def ref)]
    (cond-> (base-ref-summary ref definition :run-fsm/name)
      definition (assoc :run-fsm/initial-state (:run-fsm/initial-state definition)
                        :run-fsm/state-count (count (:run-fsm/states definition))
                        :run-fsm/terminal-state-count (count (:run-fsm/terminal-states definition))))))

(defn- validator-summary
  [defs-repo ref]
  (let [definition (resolve-ref defs-repo defs.protocol/find-validator-def ref)]
    (cond-> (base-ref-summary ref definition :validator/name)
      definition (assoc :validator/type (:validator/type definition)))))

(defn- runtime-profile-summary
  [defs-repo ref]
  (let [definition (resolve-ref defs-repo defs.protocol/find-runtime-profile ref)]
    (cond-> (base-ref-summary ref definition :runtime-profile/name)
      definition (assoc :runtime-profile/adapter-id (:runtime-profile/adapter-id definition)
                        :runtime-profile/dispatch-mode (:runtime-profile/dispatch-mode definition))
      definition (assoc-when-defined definition :runtime-profile/default-launch-mode)
      definition (assoc-when-defined definition :runtime-profile/worker-timeout-seconds)
      definition (assoc-when-defined definition :runtime-profile/heartbeat-interval-seconds)
      definition (assoc-when-defined definition :runtime-profile/allowed-mcp-servers)
      definition (assoc-when-defined definition :runtime-profile/web-search-enabled?)
      definition (assoc-when-defined definition :runtime-profile/env-allowlist))))

(defn- resource-policy-summary
  [defs-repo ref]
  (let [definition (resolve-ref defs-repo defs.protocol/find-resource-policy ref)]
    (cond-> (base-ref-summary ref definition :resource-policy/name)
      definition (assoc :resource-policy/max-active-runs (:resource-policy/max-active-runs definition)
                        :resource-policy/max-attempts (:resource-policy/max-attempts definition)
                        :resource-policy/lease-duration-seconds (:resource-policy/lease-duration-seconds definition)
                        :resource-policy/heartbeat-timeout-seconds (:resource-policy/heartbeat-timeout-seconds definition)
                        :resource-policy/queue-order (:resource-policy/queue-order definition)))))

(defn- artifact-contract-summary
  [defs-repo ref]
  (let [definition (resolve-ref defs-repo defs.protocol/find-artifact-contract ref)]
    (cond-> (base-ref-summary ref definition :artifact-contract/name)
      definition (assoc :artifact-contract/required-paths (:artifact-contract/required-paths definition)
                        :artifact-contract/optional-paths (:artifact-contract/optional-paths definition)
                        :artifact-contract/required-path-count (count (:artifact-contract/required-paths definition))
                        :artifact-contract/optional-path-count (count (:artifact-contract/optional-paths definition))))))

(defn- work-key-summary
  [task-type]
  (let [work-key-expr (:task-type/work-key-expr task-type)]
    (cond-> {:work-key/type (:work-key/type work-key-expr)}
      (= :work-key.type/direct (:work-key/type work-key-expr))
      (assoc :work-key/field (:work-key/field work-key-expr))

      (= :work-key.type/tuple (:work-key/type work-key-expr))
      (assoc :work-key/tag (:work-key/tag work-key-expr)
             :work-key/fields (:work-key/fields work-key-expr)))))

(defn- task-type-list-item
  [defs-repo task-type]
  {:task-type/id (:task-type/id task-type)
   :task-type/version (:task-type/version task-type)
   :task-type/name (:task-type/name task-type)
   :task-type/description (:task-type/description task-type)
   :task-type/input-labels (mapv :field/label (:task-type/input-schema task-type))
   :task-type/input-count (count (:task-type/input-schema task-type))
   :task-type/work-key (work-key-summary task-type)
   :task-type/runtime-profile (runtime-profile-summary defs-repo (:task-type/runtime-profile-ref task-type))
   :task-type/resource-policy (resource-policy-summary defs-repo (:task-type/resource-policy-ref task-type))
   :task-type/artifact-contract (artifact-contract-summary defs-repo (:task-type/artifact-contract-ref task-type))})

(defn- task-type-detail
  [defs-repo task-type]
  {:task-type/id (:task-type/id task-type)
   :task-type/version (:task-type/version task-type)
   :task-type/name (:task-type/name task-type)
   :task-type/description (:task-type/description task-type)
   :task-type/input-schema (:task-type/input-schema task-type)
   :task-type/work-key (work-key-summary task-type)
   :task-type/task-fsm (task-fsm-summary defs-repo (:task-type/task-fsm-ref task-type))
   :task-type/run-fsm (run-fsm-summary defs-repo (:task-type/run-fsm-ref task-type))
   :task-type/runtime-profile (runtime-profile-summary defs-repo (:task-type/runtime-profile-ref task-type))
   :task-type/validator (validator-summary defs-repo (:task-type/validator-ref task-type))
   :task-type/resource-policy (resource-policy-summary defs-repo (:task-type/resource-policy-ref task-type))
   :task-type/artifact-contract (artifact-contract-summary defs-repo (:task-type/artifact-contract-ref task-type))})

(defn list-task-types
  ([] (list-task-types (defs.loader/filesystem-definition-repository)))
  ([defs-repo]
   (->> (defs.protocol/list-task-type-defs defs-repo)
        (mapv #(task-type-list-item defs-repo %)))))

(defn load-task-type-detail
  ([task-type-id task-type-version]
   (load-task-type-detail (defs.loader/filesystem-definition-repository)
                          task-type-id
                          task-type-version))
  ([defs-repo task-type-id task-type-version]
   (let [task-type (defs.protocol/find-task-type-def defs-repo task-type-id task-type-version)]
     (when-not task-type
       (throw (ex-info (str "Task type not found: " task-type-id)
                       {:task-type-id task-type-id
                        :task-type-version task-type-version})))
     (task-type-detail defs-repo task-type))))

(defn list-task-type-create-options
  ([] (list-task-type-create-options (defs.loader/filesystem-definition-repository)))
  ([defs-repo]
   (->> (defs.protocol/list-task-type-defs defs-repo)
        (mapv (fn [task-type]
                {:task-type/id (:task-type/id task-type)
                 :task-type/version (:task-type/version task-type)
                 :task-type/name (:task-type/name task-type)
                 :task-type/description (:task-type/description task-type)
                 :task-type/input-schema (:task-type/input-schema task-type)})))))
