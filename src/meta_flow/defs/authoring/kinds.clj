(ns meta-flow.defs.authoring.kinds
  (:require [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.schema :as schema]))

(def ^:private publish-runtime-profile-first-message-text
  (str "Task-type draft requests may only reference published runtime profiles. "
       "Publish the runtime-profile draft into defs/runtime-profiles.edn before "
       "creating or validating the task-type draft."))

(def ^:private runtime-profile-key-order
  [:runtime-profile/id
   :runtime-profile/version
   :runtime-profile/name
   :runtime-profile/adapter-id
   :runtime-profile/dispatch-mode
   :runtime-profile/default-launch-mode
   :runtime-profile/codex-home-root
   :runtime-profile/allowed-mcp-servers
   :runtime-profile/web-search-enabled?
   :runtime-profile/worker-prompt-path
   :runtime-profile/helper-script-path
   :runtime-profile/artifact-contract-ref
   :runtime-profile/skills
   :runtime-profile/worker-timeout-seconds
   :runtime-profile/heartbeat-interval-seconds
   :runtime-profile/env-allowlist])

(def ^:private task-type-key-order
  [:task-type/id
   :task-type/version
   :task-type/name
   :task-type/description
   :task-type/task-fsm-ref
   :task-type/run-fsm-ref
   :task-type/runtime-profile-ref
   :task-type/artifact-contract-ref
   :task-type/validator-ref
   :task-type/resource-policy-ref
   :task-type/input-schema
   :task-type/work-key-expr])

(def ^:private definition-kind-config
  {:runtime-profile
   {:kind-label "runtime profile"
    :expected-namespace "runtime-profile"
    :definition-key :runtime-profiles
    :id-key :runtime-profile/id
    :version-key :runtime-profile/version
    :name-key :runtime-profile/name
    :top-level-key-order runtime-profile-key-order
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
    :definition-key :task-types
    :id-key :task-type/id
    :version-key :task-type/version
    :name-key :task-type/name
    :top-level-key-order task-type-key-order
    :request-schema schema/task-type-draft-request-schema
    :supported-override-keys [:task-type/runtime-profile-ref
                              :task-type/input-schema
                              :task-type/work-key-expr]
    :list-definitions (fn [defs-repo]
                        (defs.protocol/list-task-type-defs defs-repo))
    :find-definition (fn [defs-repo definition-id version]
                       (defs.protocol/find-task-type-def defs-repo definition-id version))}})

(defn definition-kind-settings
  [definition-kind]
  (or (get definition-kind-config definition-kind)
      (throw (ex-info (str "Unsupported definition kind " definition-kind)
                      {:definition-kind definition-kind
                       :supported-kinds (sort (keys definition-kind-config))}))))

(defn definition-spec
  [definition-kind]
  (assoc (definition-kind-settings definition-kind)
         :definition-kind definition-kind))

(defn supported-override-keys
  [definition-kind]
  (:supported-override-keys (definition-kind-settings definition-kind)))

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
     :rule/message publish-runtime-profile-first-message-text}]})

(defn list-definition-templates
  [defs-repo definition-kind]
  (let [{:keys [id-key version-key name-key list-definitions kind-label]}
        (definition-kind-settings definition-kind)]
    {:definition-kind definition-kind
     :definition-kind/label kind-label
     :templates (->> (list-definitions defs-repo)
                     (sort-by (juxt #(str (get % id-key))
                                    #(get % version-key)))
                     (mapv (fn [definition]
                             {:definition/id (get definition id-key)
                              :definition/version (get definition version-key)
                              :definition/name (get definition name-key)})))}))

(defn template-summary
  [definition-kind definition]
  (let [{:keys [id-key version-key name-key]} (definition-kind-settings definition-kind)]
    {:definition/id (get definition id-key)
     :definition/version (get definition version-key)
     :definition/name (get definition name-key)}))

(defn publish-runtime-profile-first-message
  []
  publish-runtime-profile-first-message-text)
