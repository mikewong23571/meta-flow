(ns meta-flow.schema
  (:require [malli.core :as m]
            [malli.error :as me]))

(def definition-ref-schema
  [:map
   [:definition/id keyword?]
   [:definition/version pos-int?]])

(def transition-schema
  [:map
   [:transition/event keyword?]
   [:transition/from keyword?]
   [:transition/to keyword?]])

(def workflow-schema
  [:map
   [:workflow/id keyword?]
   [:workflow/version pos-int?]
   [:workflow/name string?]
   [:workflow/default-task-type-ref definition-ref-schema]
   [:workflow/default-task-fsm-ref definition-ref-schema]
   [:workflow/default-run-fsm-ref definition-ref-schema]
   [:workflow/default-runtime-profile-ref definition-ref-schema]
   [:workflow/default-artifact-contract-ref definition-ref-schema]
   [:workflow/default-validator-ref definition-ref-schema]
   [:workflow/default-resource-policy-ref definition-ref-schema]])

(def input-field-schema
  [:map
   [:field/id keyword?]
   [:field/label string?]
   [:field/type keyword?]
   [:field/required? boolean?]
   [:field/placeholder {:optional true} string?]])

(def work-key-expr-schema
  [:multi {:dispatch :work-key/type}
   [:work-key.type/direct
    [:map
     [:work-key/type [:= :work-key.type/direct]]
     [:work-key/field keyword?]]]
   [:work-key.type/tuple
    [:map
     [:work-key/type [:= :work-key.type/tuple]]
     [:work-key/tag keyword?]
     [:work-key/fields [:vector keyword?]]]]])

(def task-type-schema
  [:map
   [:task-type/id keyword?]
   [:task-type/version pos-int?]
   [:task-type/name string?]
   [:task-type/description string?]
   [:task-type/task-fsm-ref definition-ref-schema]
   [:task-type/run-fsm-ref definition-ref-schema]
   [:task-type/runtime-profile-ref definition-ref-schema]
   [:task-type/artifact-contract-ref definition-ref-schema]
   [:task-type/validator-ref definition-ref-schema]
   [:task-type/resource-policy-ref definition-ref-schema]
   [:task-type/input-schema {:optional true} [:vector input-field-schema]]
   [:task-type/work-key-expr {:optional true} work-key-expr-schema]])

(def task-fsm-schema
  [:map
   [:task-fsm/id keyword?]
   [:task-fsm/version pos-int?]
   [:task-fsm/name string?]
   [:task-fsm/initial-state keyword?]
   [:task-fsm/states [:vector keyword?]]
   [:task-fsm/terminal-states [:set keyword?]]
   [:task-fsm/transitions [:vector transition-schema]]])

(def run-fsm-schema
  [:map
   [:run-fsm/id keyword?]
   [:run-fsm/version pos-int?]
   [:run-fsm/name string?]
   [:run-fsm/initial-state keyword?]
   [:run-fsm/states [:vector keyword?]]
   [:run-fsm/terminal-states [:set keyword?]]
   [:run-fsm/transitions [:vector transition-schema]]])

(def artifact-contract-schema
  [:map
   [:artifact-contract/id keyword?]
   [:artifact-contract/version pos-int?]
   [:artifact-contract/name string?]
   [:artifact-contract/required-paths [:vector string?]]
   [:artifact-contract/optional-paths [:vector string?]]])

(def validator-schema
  [:map
   [:validator/id keyword?]
   [:validator/version pos-int?]
   [:validator/name string?]
   [:validator/type keyword?]])

(def runtime-profile-schema
  [:multi {:dispatch :runtime-profile/adapter-id}
   [:runtime.adapter/mock
    [:map
     [:runtime-profile/id keyword?]
     [:runtime-profile/version pos-int?]
     [:runtime-profile/name string?]
     [:runtime-profile/adapter-id [:= :runtime.adapter/mock]]
     [:runtime-profile/dispatch-mode keyword?]]]
   [:runtime.adapter/codex
    [:map
     [:runtime-profile/id keyword?]
     [:runtime-profile/version pos-int?]
     [:runtime-profile/name string?]
     [:runtime-profile/adapter-id [:= :runtime.adapter/codex]]
     [:runtime-profile/dispatch-mode keyword?]
     [:runtime-profile/default-launch-mode {:optional true} keyword?]
     [:runtime-profile/codex-home-root string?]
     [:runtime-profile/allowed-mcp-servers [:vector keyword?]]
     [:runtime-profile/web-search-enabled? boolean?]
     [:runtime-profile/worker-prompt-path string?]
     [:runtime-profile/helper-script-path string?]
     [:runtime-profile/artifact-contract-ref definition-ref-schema]
     [:runtime-profile/worker-timeout-seconds pos-int?]
     [:runtime-profile/heartbeat-interval-seconds pos-int?]
     [:runtime-profile/env-allowlist [:vector string?]]]]])

(def resource-policy-schema
  [:map
   [:resource-policy/id keyword?]
   [:resource-policy/version pos-int?]
   [:resource-policy/name string?]
   [:resource-policy/max-active-runs pos-int?]
   [:resource-policy/max-attempts pos-int?]
   [:resource-policy/lease-duration-seconds pos-int?]
   [:resource-policy/heartbeat-timeout-seconds pos-int?]
   [:resource-policy/queue-order keyword?]])

(def workflow-definitions-schema
  [:map
   [:workflow workflow-schema]
   [:task-types [:vector task-type-schema]]
   [:task-fsms [:vector task-fsm-schema]]
   [:run-fsms [:vector run-fsm-schema]]
   [:artifact-contracts [:vector artifact-contract-schema]]
   [:validators [:vector validator-schema]]
   [:runtime-profiles [:vector runtime-profile-schema]]
   [:resource-policies [:vector resource-policy-schema]]])

(def runtime-profile-draft-overrides-schema
  [:map
   [:runtime-profile/web-search-enabled? {:optional true} boolean?]
   [:runtime-profile/worker-prompt-path {:optional true} string?]])

(def task-type-draft-overrides-schema
  [:map
   [:task-type/runtime-profile-ref {:optional true} definition-ref-schema]
   [:task-type/input-schema {:optional true} [:vector input-field-schema]]
   [:task-type/work-key-expr {:optional true} work-key-expr-schema]])

(def runtime-profile-draft-request-schema
  [:map
   [:authoring/from-id keyword?]
   [:authoring/from-version {:optional true} pos-int?]
   [:authoring/new-id keyword?]
   [:authoring/new-name string?]
   [:authoring/new-version {:optional true} pos-int?]
   [:authoring/overrides {:optional true} runtime-profile-draft-overrides-schema]])

(def task-type-draft-request-schema
  [:map
   [:authoring/from-id keyword?]
   [:authoring/from-version {:optional true} pos-int?]
   [:authoring/new-id keyword?]
   [:authoring/new-name string?]
   [:authoring/new-version {:optional true} pos-int?]
   [:authoring/overrides {:optional true} task-type-draft-overrides-schema]])

(defn explain
  [schema value]
  (when-let [explanation (m/explain schema value)]
    (me/humanize explanation)))

(defn validate!
  [label schema value]
  (when-let [errors (explain schema value)]
    (throw (ex-info (str "Schema validation failed for " label)
                    {:label label
                     :errors errors})))
  value)
