(ns meta-flow.defs.authoring
  (:require [meta-flow.defs.authoring.drafts :as authoring.drafts]
            [meta-flow.defs.authoring.kinds :as authoring.kinds]
            [meta-flow.defs.authoring.prepare :as authoring.prepare]
            [meta-flow.defs.authoring.workspace :as authoring.workspace]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.defs.source :as defs.source]))

(defn supported-override-keys
  [definition-kind]
  (authoring.kinds/supported-override-keys definition-kind))

(defn authoring-contract
  []
  (authoring.kinds/authoring-contract))

(defn list-definition-templates
  [defs-repo definition-kind]
  (authoring.kinds/list-definition-templates defs-repo definition-kind))

(defn validate-draft-request!
  ([defs-repo definition-kind request]
   (authoring.prepare/validate-draft-request! defs-repo definition-kind request))
  ([defs-repo definition-kind request {:keys [allowed-runtime-profile-refs]}]
   (authoring.prepare/validate-draft-request! defs-repo
                                              definition-kind
                                              request
                                              {:allowed-runtime-profile-refs allowed-runtime-profile-refs})))

(defn prepare-runtime-profile-draft-request!
  [defs-repo request]
  (authoring.prepare/prepare-runtime-profile-draft-request! defs-repo request))

(defn prepare-task-type-draft-request!
  [defs-repo request]
  (authoring.prepare/prepare-task-type-draft-request! defs-repo request))

(defn- require-definition-namespace!
  [definition-kind keyword-value label]
  (let [expected-namespace (:expected-namespace
                            (authoring.kinds/definition-kind-settings definition-kind))]
    (when-not (= expected-namespace (namespace keyword-value))
      (throw (ex-info (str label " must use keyword namespace " expected-namespace)
                      {:definition-kind definition-kind
                       :label label
                       :expected-namespace expected-namespace
                       :value keyword-value})))))

(defn- overlay-root-for
  [defs-repo]
  (or (:overlay-root defs-repo)
      defs.source/default-overlay-root))

(defn plan-definition-draft!
  ([defs-repo definition-kind request]
   (authoring.prepare/plan-definition-draft! defs-repo definition-kind request))
  ([defs-repo definition-kind request opts]
   (authoring.prepare/plan-definition-draft! defs-repo definition-kind request opts)))

(defn create-definition-draft!
  ([defs-repo definition-kind request]
   (create-definition-draft! defs-repo definition-kind request nil))
  ([defs-repo definition-kind request opts]
   (let [{:keys [definition] :as draft-plan}
         (plan-definition-draft! defs-repo definition-kind request opts)
         definition-spec (authoring.kinds/definition-spec definition-kind)
         definitions (defs.protocol/load-workflow-defs defs-repo)
         overlay-root (overlay-root-for defs-repo)]
     (-> definitions
         (authoring.workspace/add-definition-to-defs! definition-spec definition "create draft for")
         authoring.workspace/validate-candidate-definitions!)
     (assoc draft-plan
            :draft-path (authoring.workspace/write-draft! definition-spec overlay-root definition)
            :validation {:status :ok}))))

(defn create-runtime-profile-draft!
  [defs-repo request]
  (create-definition-draft! defs-repo :runtime-profile request))

(defn create-task-type-draft!
  [defs-repo request]
  (create-definition-draft! defs-repo :task-type request))

(defn publish-draft!
  [defs-repo definition-kind definition-ref]
  (let [definition-spec (authoring.kinds/definition-spec definition-kind)
        definition-id (:definition/id definition-ref)
        definition-version (:definition/version definition-ref)
        overlay-root (overlay-root-for defs-repo)
        {:keys [draft-path draft-file]}
        (authoring.workspace/load-draft! definition-spec overlay-root definition-id definition-version)]
    (require-definition-namespace! definition-kind definition-id ":definition/id")
    (when-not (.isFile draft-file)
      (throw (ex-info (str "Draft " (:kind-label definition-spec) " not found at " draft-path)
                      {:definition-kind definition-kind
                       :definition/id definition-id
                       :definition/version definition-version
                       :draft-path draft-path})))
    (let [draft-definition (defs.source/load-edn-file! draft-path)
          _ (when-not (= [definition-id definition-version]
                         [(get draft-definition (:id-key definition-spec))
                          (get draft-definition (:version-key definition-spec))])
              (throw (ex-info "Draft file contents do not match the requested definition id/version"
                              {:definition-kind definition-kind
                               :definition/id definition-id
                               :definition/version definition-version
                               :draft-path draft-path
                               :draft-definition draft-definition})))
          {:keys [definitions]}
          (authoring.workspace/load-active-definitions definition-spec overlay-root)
          updated-active-definitions (authoring.workspace/sort-definitions
                                      definition-spec
                                      (conj (vec definitions) draft-definition))]
      (-> (defs.protocol/load-workflow-defs defs-repo)
          (authoring.workspace/add-definition-to-defs! definition-spec draft-definition "publish")
          authoring.workspace/validate-candidate-definitions!)
      {:definition-kind definition-kind
       :definition draft-definition
       :draft-path draft-path
       :published-path (authoring.workspace/write-active-definitions!
                        definition-spec
                        overlay-root
                        updated-active-definitions)})))

(defn publish-runtime-profile-draft!
  [defs-repo definition-ref]
  (publish-draft! defs-repo :runtime-profile definition-ref))

(defn publish-task-type-draft!
  [defs-repo definition-ref]
  (publish-draft! defs-repo :task-type definition-ref))

(defn list-definition-drafts
  [defs-repo definition-kind]
  (authoring.drafts/list-definition-drafts (overlay-root-for defs-repo)
                                           definition-kind))

(defn load-definition-draft
  [defs-repo definition-kind definition-ref]
  (authoring.drafts/load-definition-draft (overlay-root-for defs-repo)
                                          definition-kind
                                          definition-ref))
