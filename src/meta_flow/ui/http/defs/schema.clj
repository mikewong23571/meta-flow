(ns meta-flow.ui.http.defs.schema)

(def definition-draft-detail-query-params
  [:map
   [:definition-id :string]
   [:definition-version :int]])

(def definition-ref-body
  [:map
   [:definition/id :string]
   [:definition/version :int]])

(def ^:private input-field-body
  [:map
   [:field/id :string]
   [:field/label :string]
   [:field/type :string]
   [:field/required? :boolean]
   [:field/placeholder {:optional true} :string]])

(def ^:private work-key-expr-body
  [:multi {:dispatch :work-key/type}
   ["work-key.type/direct"
    [:map
     [:work-key/type [:= "work-key.type/direct"]]
     [:work-key/field :string]]]
   ["work-key.type/tuple"
    [:map
     [:work-key/type [:= "work-key.type/tuple"]]
     [:work-key/tag :string]
     [:work-key/fields [:vector :string]]]]])

(def runtime-profile-draft-request-body
  [:map
   [:authoring/from-id :string]
   [:authoring/from-version {:optional true} :int]
   [:authoring/new-id :string]
   [:authoring/new-name :string]
   [:authoring/new-version {:optional true} :int]
   [:authoring/overrides {:optional true}
    [:map
     [:runtime-profile/web-search-enabled? {:optional true} :boolean]
     [:runtime-profile/worker-prompt-path {:optional true} :string]]]])

(def task-type-draft-request-body
  [:map
   [:authoring/from-id :string]
   [:authoring/from-version {:optional true} :int]
   [:authoring/new-id :string]
   [:authoring/new-name :string]
   [:authoring/new-version {:optional true} :int]
   [:authoring/overrides {:optional true}
    [:map
     [:task-type/description {:optional true} :string]
     [:task-type/runtime-profile-ref {:optional true} definition-ref-body]
     [:task-type/input-schema {:optional true} [:vector input-field-body]]
     [:task-type/work-key-expr {:optional true} work-key-expr-body]]]])

(def task-type-generation-request-body
  [:map
   [:generation/description :string]
   [:generation/task-type-template-id {:optional true} :string]
   [:generation/task-type-template-version {:optional true} :int]
   [:generation/runtime-profile-template-id {:optional true} :string]
   [:generation/runtime-profile-template-version {:optional true} :int]
   [:generation/task-type-id {:optional true} :string]
   [:generation/task-type-name {:optional true} :string]])
