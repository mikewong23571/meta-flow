(ns meta-flow.defs.generation.core
  (:require [meta-flow.defs.authoring :as defs.authoring]
            [meta-flow.defs.authoring.kinds :as authoring.kinds]
            [meta-flow.defs.authoring.workspace :as authoring.workspace]
            [meta-flow.defs.generation.description :as generation.description]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.defs.source :as defs.source]))

(defn- runtime-profile-ref
  [runtime-profile]
  {:definition/id (:runtime-profile/id runtime-profile)
   :definition/version (:runtime-profile/version runtime-profile)})

(defn- overlay-root-for
  [defs-repo]
  (or (:overlay-root defs-repo)
      defs.source/default-overlay-root))

(defn generate-task-type-draft!
  [defs-repo request]
  (let [{:keys [request
                task-type-template
                task-type-id
                task-type-name
                runtime-profile-template
                runtime-profile-request]}
        (generation.description/derive-task-type-generation-context! defs-repo request)
        runtime-profile-plan (when runtime-profile-request
                               (defs.authoring/plan-definition-draft! defs-repo
                                                                      :runtime-profile
                                                                      runtime-profile-request))
        selected-runtime-profile-ref (or (some-> runtime-profile-plan :definition runtime-profile-ref)
                                         (runtime-profile-ref runtime-profile-template))
        task-type-work-key-expr (let [work-key-expr (:task-type/work-key-expr task-type-template)]
                                  (when (and (not= task-type-id
                                                   (:task-type/id task-type-template))
                                             (= :validator/repo-arch
                                                (get-in task-type-template [:task-type/validator-ref :definition/id]))
                                             (= :work-key.type/tuple
                                                (:work-key/type work-key-expr)))
                                    (assoc work-key-expr
                                           :work-key/tag (keyword (name task-type-id)))))
        task-type-overrides (cond-> {:task-type/description (:generation/description request)}
                              task-type-work-key-expr
                              (assoc :task-type/work-key-expr task-type-work-key-expr)
                              (not= selected-runtime-profile-ref
                                    (:task-type/runtime-profile-ref task-type-template))
                              (assoc :task-type/runtime-profile-ref selected-runtime-profile-ref))
        task-type-plan (defs.authoring/plan-definition-draft!
                        defs-repo
                        :task-type
                        {:authoring/from-id (:task-type/id task-type-template)
                         :authoring/from-version (:task-type/version task-type-template)
                         :authoring/new-id task-type-id
                         :authoring/new-name task-type-name
                         :authoring/overrides task-type-overrides}
                        (cond-> {}
                          runtime-profile-plan
                          (assoc :allowed-runtime-profile-refs #{selected-runtime-profile-ref})))
        overlay-root (overlay-root-for defs-repo)
        runtime-profile-spec (authoring.kinds/definition-spec :runtime-profile)
        task-type-spec (authoring.kinds/definition-spec :task-type)
        _ (cond-> (defs.protocol/load-workflow-defs defs-repo)
            runtime-profile-plan
            (authoring.workspace/add-definition-to-defs! runtime-profile-spec
                                                         (:definition runtime-profile-plan)
                                                         "generate draft for")
            true
            (authoring.workspace/add-definition-to-defs! task-type-spec
                                                         (:definition task-type-plan)
                                                         "generate draft for")
            true
            authoring.workspace/validate-candidate-definitions!)
        runtime-profile-result (when runtime-profile-plan
                                 (assoc runtime-profile-plan
                                        :draft-path (authoring.workspace/write-draft! runtime-profile-spec
                                                                                      overlay-root
                                                                                      (:definition runtime-profile-plan))))
        task-type-result (assoc task-type-plan
                                :draft-path (authoring.workspace/write-draft! task-type-spec
                                                                              overlay-root
                                                                              (:definition task-type-plan)))]
    {:generation/description (:generation/description request)
     :runtime-profile runtime-profile-result
     :task-type task-type-result
     :validation {:status :ok}
     :notes (cond-> ["Drafts remain under defs/drafts until publish."]
              runtime-profile-plan
              (conj "Publish the runtime-profile draft before publishing the task-type draft."))}))
