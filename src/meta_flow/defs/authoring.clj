(ns meta-flow.defs.authoring
  (:require [clojure.string :as str]
            [meta-flow.defs.authoring.drafts :as authoring.drafts]
            [meta-flow.defs.authoring.kinds :as authoring.kinds]
            [meta-flow.defs.authoring.workspace :as authoring.workspace]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.defs.source :as defs.source]
            [meta-flow.schema :as schema]))

(defn supported-override-keys
  [definition-kind]
  (authoring.kinds/supported-override-keys definition-kind))

(defn authoring-contract
  []
  (authoring.kinds/authoring-contract))

(defn list-definition-templates
  [defs-repo definition-kind]
  (authoring.kinds/list-definition-templates defs-repo definition-kind))

(defn- not-blank-string?
  [value]
  (and (string? value)
       (not (str/blank? value))))

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

(defn- resolve-template!
  [defs-repo definition-kind from-id from-version]
  (let [{:keys [find-definition list-definitions version-key kind-label id-key]}
        (authoring.kinds/definition-kind-settings definition-kind)]
    (if from-version
      (or (find-definition defs-repo from-id from-version)
          (throw (ex-info (str "Template " kind-label " not found")
                          {:definition-kind definition-kind
                           :definition/id from-id
                           :definition/version from-version})))
      (or (->> (list-definitions defs-repo)
               (filter #(= from-id (get % id-key)))
               (sort-by version-key >)
               first)
          (throw (ex-info (str "Template " kind-label " not found")
                          {:definition-kind definition-kind
                           :definition/id from-id}))))))

(defn- validate-request-ids!
  [definition-kind request]
  (require-definition-namespace! definition-kind (:authoring/from-id request) ":authoring/from-id")
  (require-definition-namespace! definition-kind (:authoring/new-id request) ":authoring/new-id")
  (when-not (not-blank-string? (:authoring/new-name request))
    (throw (ex-info ":authoring/new-name must be a non-blank string"
                    {:definition-kind definition-kind
                     :value (:authoring/new-name request)}))))

(defn- validate-task-type-runtime-profile-ref!
  [defs-repo runtime-profile-ref]
  (when-not (defs.protocol/find-runtime-profile defs-repo
                                                (:definition/id runtime-profile-ref)
                                                (:definition/version runtime-profile-ref))
    (throw (ex-info (authoring.kinds/publish-runtime-profile-first-message)
                    {:publish-order/rule :publish-order/task-type-runtime-profile
                     :definition-ref runtime-profile-ref}))))

(defn- validate-supported-overrides!
  [definition-kind overrides]
  (let [supported-keys (supported-override-keys definition-kind)
        supported (set supported-keys)
        unsupported (->> (keys overrides)
                         (remove supported)
                         sort
                         vec)]
    (when (seq unsupported)
      (throw (ex-info (str "Unsupported " (name definition-kind) " override keys")
                      {:definition-kind definition-kind
                       :unsupported-override-keys unsupported
                       :supported-override-keys supported-keys})))))

(defn- normalize-request
  [request template]
  (-> request
      (update :authoring/overrides #(or % {}))
      (assoc :authoring/from-version (:definition/version template))
      (update :authoring/new-version #(or % 1))))

(defn validate-draft-request!
  [defs-repo definition-kind request]
  (let [{:keys [request-schema]} (authoring.kinds/definition-kind-settings definition-kind)
        request-with-defaults (update request :authoring/overrides #(or % {}))]
    (schema/validate! (str (name definition-kind) " authoring request")
                      request-schema
                      request-with-defaults)
    (validate-request-ids! definition-kind request-with-defaults)
    (validate-supported-overrides! definition-kind (:authoring/overrides request-with-defaults))
    (let [template-definition (resolve-template! defs-repo
                                                 definition-kind
                                                 (:authoring/from-id request-with-defaults)
                                                 (:authoring/from-version request-with-defaults))
          template (authoring.kinds/template-summary definition-kind template-definition)
          normalized-request (normalize-request request-with-defaults template)]
      (when (= [(:definition/id template) (:definition/version template)]
               [(:authoring/new-id normalized-request) (:authoring/new-version normalized-request)])
        (throw (ex-info "Draft request must change id or version from the source template"
                        {:definition-kind definition-kind
                         :template template
                         :request normalized-request})))
      (when-let [runtime-profile-ref (get-in normalized-request
                                             [:authoring/overrides :task-type/runtime-profile-ref])]
        (validate-task-type-runtime-profile-ref! defs-repo runtime-profile-ref))
      {:definition-kind definition-kind
       :template template
       :request normalized-request
       :supported-override-keys (supported-override-keys definition-kind)
       :publish-order-rules (if (= definition-kind :task-type)
                              [{:rule/id :publish-order/task-type-runtime-profile
                                :rule/message (authoring.kinds/publish-runtime-profile-first-message)}]
                              [])})))

(defn prepare-runtime-profile-draft-request!
  [defs-repo request]
  (validate-draft-request! defs-repo :runtime-profile request))

(defn prepare-task-type-draft-request!
  [defs-repo request]
  (validate-draft-request! defs-repo :task-type request))

(defn- overlay-root-for
  [defs-repo]
  (or (:overlay-root defs-repo)
      defs.source/default-overlay-root))

(defn- build-draft-definition
  [definition-kind template-definition request]
  (let [{:keys [id-key version-key name-key]}
        (authoring.kinds/definition-kind-settings definition-kind)]
    (-> template-definition
        (assoc id-key (:authoring/new-id request)
               version-key (:authoring/new-version request)
               name-key (:authoring/new-name request))
        (merge (:authoring/overrides request)))))

(defn create-definition-draft!
  [defs-repo definition-kind request]
  (let [{:keys [template request]} (validate-draft-request! defs-repo definition-kind request)
        template-definition (resolve-template! defs-repo
                                               definition-kind
                                               (:authoring/from-id request)
                                               (:authoring/from-version request))
        definition-spec (authoring.kinds/definition-spec definition-kind)
        draft-definition (build-draft-definition definition-kind template-definition request)
        definitions (defs.protocol/load-workflow-defs defs-repo)
        overlay-root (overlay-root-for defs-repo)]
    (-> definitions
        (authoring.workspace/add-definition-to-defs! definition-spec draft-definition "create draft for")
        authoring.workspace/validate-candidate-definitions!)
    {:definition-kind definition-kind
     :definition draft-definition
     :draft-path (authoring.workspace/write-draft! definition-spec overlay-root draft-definition)
     :template template
     :request request
     :validation {:status :ok}}))

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
