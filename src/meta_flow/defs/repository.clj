(ns meta-flow.defs.repository
  (:require [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.defs.source :as defs.source]
            [meta-flow.defs.validation :as defs.validation]))

(defn build-loaded-definitions
  [resource-base]
  (let [defs (-> (defs.source/load-definition-data resource-base)
                 defs.validation/validate-definition-schemas!)
        indexes (defs.validation/validate-definition-links! defs)]
    {:defs defs
     :indexes indexes}))

(defn ensure-loaded!
  [cache resource-base]
  (or @cache
      (let [loaded (build-loaded-definitions resource-base)]
        (reset! cache loaded)
        loaded)))

(defrecord FilesystemDefinitionRepository [resource-base cache]
  defs.protocol/DefinitionRepository
  (load-workflow-defs [_]
    (:defs (ensure-loaded! cache resource-base)))
  (find-task-type-def [_ task-type-id version]
    (get-in (ensure-loaded! cache resource-base) [:indexes :task-types [task-type-id version]]))
  (find-run-fsm-def [_ run-fsm-id version]
    (get-in (ensure-loaded! cache resource-base) [:indexes :run-fsms [run-fsm-id version]]))
  (find-task-fsm-def [_ task-fsm-id version]
    (get-in (ensure-loaded! cache resource-base) [:indexes :task-fsms [task-fsm-id version]]))
  (find-artifact-contract [_ contract-id version]
    (get-in (ensure-loaded! cache resource-base) [:indexes :artifact-contracts [contract-id version]]))
  (find-validator-def [_ validator-id version]
    (get-in (ensure-loaded! cache resource-base) [:indexes :validators [validator-id version]]))
  (find-runtime-profile [_ runtime-profile-id version]
    (get-in (ensure-loaded! cache resource-base) [:indexes :runtime-profiles [runtime-profile-id version]]))
  (find-resource-policy [_ resource-policy-id version]
    (get-in (ensure-loaded! cache resource-base) [:indexes :resource-policies [resource-policy-id version]])))

(defn filesystem-definition-repository
  ([] (filesystem-definition-repository defs.source/default-resource-base))
  ([resource-base]
   (->FilesystemDefinitionRepository resource-base (atom nil))))
