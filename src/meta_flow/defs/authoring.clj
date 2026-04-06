(ns meta-flow.defs.authoring
  (:require [clojure.string :as str]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.schema :as schema]))

(def ^:private publish-runtime-profile-first-message
  (str "Task-type draft requests may only reference published runtime profiles. "
       "Publish the runtime-profile draft into defs/runtime-profiles.edn before "
       "creating or validating the task-type draft."))

(def ^:private definition-kind-config
  {:runtime-profile
   {:kind-label "runtime profile"
    :expected-namespace "runtime-profile"
    :id-key :runtime-profile/id
    :version-key :runtime-profile/version
    :name-key :runtime-profile/name
    :request-schema schema/runtime-profile-draft-request-schema
    :supported-override-keys [:runtime-profile/web-search-enabled?
                              :runtime-profile/worker-prompt-path]
    :list-definitions (fn [defs-repo]
                        (->> (defs.protocol/load-workflow-defs defs-repo)
                             :runtime-profiles))
    :find-definition (fn [defs-repo definition-id version]
                       (defs.protocol/find-runtime-profile defs-repo definition-id version))}
   :task-type
   {:kind-label "task type"
    :expected-namespace "task-type"
    :id-key :task-type/id
    :version-key :task-type/version
    :name-key :task-type/name
    :request-schema schema/task-type-draft-request-schema
    :supported-override-keys [:task-type/runtime-profile-ref
                              :task-type/input-schema
                              :task-type/work-key-expr]
    :list-definitions (fn [defs-repo]
                        (defs.protocol/list-task-type-defs defs-repo))
    :find-definition (fn [defs-repo definition-id version]
                       (defs.protocol/find-task-type-def defs-repo definition-id version))}})

(defn supported-override-keys
  [definition-kind]
  (or (get-in definition-kind-config [definition-kind :supported-override-keys])
      (throw (ex-info (str "Unsupported definition kind " definition-kind)
                      {:definition-kind definition-kind
                       :supported-kinds (sort (keys definition-kind-config))}))))

(defn authoring-contract
  []
  {:runtime-profile
   {:request-shape {:required [:authoring/from-id
                               :authoring/new-id
                               :authoring/new-name]
                    :optional [:authoring/from-version
                               :authoring/new-version
                               :authoring/overrides]}
    :supported-override-keys (supported-override-keys :runtime-profile)}
   :task-type
   {:request-shape {:required [:authoring/from-id
                               :authoring/new-id
                               :authoring/new-name]
                    :optional [:authoring/from-version
                               :authoring/new-version
                               :authoring/overrides]}
    :supported-override-keys (supported-override-keys :task-type)}
   :publish-order-rules
   [{:rule/id :publish-order/task-type-runtime-profile
     :rule/message publish-runtime-profile-first-message}]})

(defn list-definition-templates
  [defs-repo definition-kind]
  (let [{:keys [id-key version-key name-key list-definitions kind-label]}
        (or (get definition-kind-config definition-kind)
            (throw (ex-info (str "Unsupported definition kind " definition-kind)
                            {:definition-kind definition-kind
                             :supported-kinds (sort (keys definition-kind-config))})))]
    {:definition-kind definition-kind
     :definition-kind/label kind-label
     :templates (->> (list-definitions defs-repo)
                     (sort-by (juxt #(str (get % id-key))
                                    #(get % version-key)))
                     (mapv (fn [definition]
                             {:definition/id (get definition id-key)
                              :definition/version (get definition version-key)
                              :definition/name (get definition name-key)})))}))

(defn- not-blank-string?
  [value]
  (and (string? value)
       (not (str/blank? value))))

(defn- require-definition-namespace!
  [definition-kind keyword-value label]
  (let [expected-namespace (get-in definition-kind-config [definition-kind :expected-namespace])]
    (when-not (= expected-namespace (namespace keyword-value))
      (throw (ex-info (str label " must use keyword namespace " expected-namespace)
                      {:definition-kind definition-kind
                       :label label
                       :expected-namespace expected-namespace
                       :value keyword-value})))))

(defn- template-summary
  [definition-kind definition]
  (let [{:keys [id-key version-key name-key]} (get definition-kind-config definition-kind)]
    {:definition/id (get definition id-key)
     :definition/version (get definition version-key)
     :definition/name (get definition name-key)}))

(defn- resolve-template!
  [defs-repo definition-kind from-id from-version]
  (let [{:keys [find-definition list-definitions version-key kind-label]}
        (get definition-kind-config definition-kind)]
    (if from-version
      (or (find-definition defs-repo from-id from-version)
          (throw (ex-info (str "Template " kind-label " not found")
                          {:definition-kind definition-kind
                           :definition/id from-id
                           :definition/version from-version})))
      (or (->> (list-definitions defs-repo)
               (filter #(= from-id (get % (get-in definition-kind-config [definition-kind :id-key]))))
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
    (throw (ex-info publish-runtime-profile-first-message
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
  (let [{:keys [request-schema]}
        (or (get definition-kind-config definition-kind)
            (throw (ex-info (str "Unsupported definition kind " definition-kind)
                            {:definition-kind definition-kind
                             :supported-kinds (sort (keys definition-kind-config))})))
        request-with-defaults (update request :authoring/overrides #(or % {}))]
    (schema/validate! (str (name definition-kind) " authoring request")
                      request-schema
                      request-with-defaults)
    (validate-request-ids! definition-kind request-with-defaults)
    (validate-supported-overrides! definition-kind (:authoring/overrides request-with-defaults))
    (let [template (->> (resolve-template! defs-repo
                                           definition-kind
                                           (:authoring/from-id request-with-defaults)
                                           (:authoring/from-version request-with-defaults))
                        (template-summary definition-kind))
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
                                :rule/message publish-runtime-profile-first-message}]
                              [])})))

(defn prepare-runtime-profile-draft-request!
  [defs-repo request]
  (validate-draft-request! defs-repo :runtime-profile request))

(defn prepare-task-type-draft-request!
  [defs-repo request]
  (validate-draft-request! defs-repo :task-type request))
