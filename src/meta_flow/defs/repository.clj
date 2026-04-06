(ns meta-flow.defs.repository
  (:require [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.defs.source :as defs.source]
            [meta-flow.defs.validation :as defs.validation]))

(defn build-loaded-definitions
  [{:keys [resource-base overlay-root]}]
  (let [defs (-> (defs.source/load-definition-data resource-base
                                                   {:overlay-root overlay-root})
                 defs.validation/validate-definition-schemas!)
        indexes (defs.validation/validate-definition-links! defs)]
    {:defs defs
     :indexes indexes}))

(defn ensure-loaded!
  [cache options]
  (or @cache
      (let [loaded (build-loaded-definitions options)]
        (reset! cache loaded)
        loaded)))

(defrecord FilesystemDefinitionRepository [resource-base overlay-root cache]
  defs.protocol/DefinitionRepository
  (load-workflow-defs [_]
    (:defs (ensure-loaded! cache {:resource-base resource-base
                                  :overlay-root overlay-root})))
  (list-task-type-defs [_]
    (->> (vals (get-in (ensure-loaded! cache {:resource-base resource-base
                                              :overlay-root overlay-root})
                       [:indexes :task-types]))
         (sort-by #(str (:task-type/id %)))))
  (find-task-type-def [_ task-type-id version]
    (get-in (ensure-loaded! cache {:resource-base resource-base
                                   :overlay-root overlay-root})
            [:indexes :task-types [task-type-id version]]))
  (find-run-fsm-def [_ run-fsm-id version]
    (get-in (ensure-loaded! cache {:resource-base resource-base
                                   :overlay-root overlay-root})
            [:indexes :run-fsms [run-fsm-id version]]))
  (find-task-fsm-def [_ task-fsm-id version]
    (get-in (ensure-loaded! cache {:resource-base resource-base
                                   :overlay-root overlay-root})
            [:indexes :task-fsms [task-fsm-id version]]))
  (find-artifact-contract [_ contract-id version]
    (get-in (ensure-loaded! cache {:resource-base resource-base
                                   :overlay-root overlay-root})
            [:indexes :artifact-contracts [contract-id version]]))
  (find-validator-def [_ validator-id version]
    (get-in (ensure-loaded! cache {:resource-base resource-base
                                   :overlay-root overlay-root})
            [:indexes :validators [validator-id version]]))
  (find-runtime-profile [_ runtime-profile-id version]
    (get-in (ensure-loaded! cache {:resource-base resource-base
                                   :overlay-root overlay-root})
            [:indexes :runtime-profiles [runtime-profile-id version]]))
  (find-resource-policy [_ resource-policy-id version]
    (get-in (ensure-loaded! cache {:resource-base resource-base
                                   :overlay-root overlay-root})
            [:indexes :resource-policies [resource-policy-id version]])))

(defn reload-filesystem-definition-repository!
  [repository]
  (if-let [cache (:cache repository)]
    (do
      (reset! cache nil)
      (defs.protocol/load-workflow-defs repository))
    (throw (ex-info "Repository does not support filesystem reload"
                    {:repository repository}))))

(defn- normalize-repository-options
  [resource-base-or-options]
  (if (map? resource-base-or-options)
    {:resource-base (or (:resource-base resource-base-or-options)
                        defs.source/default-resource-base)
     :overlay-root (get resource-base-or-options
                        :overlay-root
                        defs.source/default-overlay-root)}
    {:resource-base resource-base-or-options
     :overlay-root defs.source/default-overlay-root}))

(defn filesystem-definition-repository
  ([] (filesystem-definition-repository {:resource-base defs.source/default-resource-base
                                         :overlay-root defs.source/default-overlay-root}))
  ([resource-base-or-options]
   (let [{:keys [resource-base overlay-root]}
         (normalize-repository-options resource-base-or-options)]
     (->FilesystemDefinitionRepository resource-base overlay-root (atom nil)))))
