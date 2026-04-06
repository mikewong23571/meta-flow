(ns meta-flow-ui.pages.defs.authoring.task-type.drafts
  (:require [meta-flow-ui.components :as components]
            [meta-flow-ui.pages.defs.authoring.task-type.shared :as shared]
            [meta-flow-ui.pages.defs.presenter :as presenter]
            [meta-flow-ui.pages.defs.state :as defs-state]))

(defn- draft-list-row
  [draft task-authoring]
  (let [selected-ref (some-> task-authoring :draft-detail :definition shared/task-definition-ref)
        draft-ref-value (shared/draft-ref draft)
        publishing? (= draft-ref-value (:publishing-ref task-authoring))]
    [:article {:className (str "defs-authoring-draft-row"
                               (when (= draft-ref-value selected-ref)
                                 " defs-authoring-draft-row-active"))}
     [:div {:className "defs-authoring-draft-copy"}
      [:h3 {:className "defs-authoring-draft-title"}
       (or (:definition/name draft) (:definition/id draft))]
      [:p {:className "defs-authoring-meta"}
       (shared/definition-ref-label draft-ref-value)]
      [:p {:className "defs-authoring-draft-path"} (:draft-path draft)]]
     [:div {:className "defs-authoring-draft-actions"}
      [:button {:className "button button-ghost"
                :on-click #(defs-state/load-task-type-draft-detail!
                            (:definition/id draft-ref-value)
                            (:definition/version draft-ref-value))}
       "Inspect"]
      [:button {:className "button button-primary"
                :on-click #(defs-state/publish-task-type-draft! draft-ref-value)
                :disabled publishing?}
       (if publishing? "Publishing…" "Publish")]]]))

(defn draft-list-panel
  [task-authoring]
  [:section {:className "panel defs-authoring-panel defs-authoring-drafts-panel"}
   [:div {:className "defs-authoring-panel-head"}
    [:div
     [:h2 {:className "component-title"} "Task-Type Drafts"]
     [:p {:className "defs-authoring-copy"}
      "Inspect or publish any saved task-type draft from the overlay workspace."]]]
   (cond
     (and (:drafts-loading? task-authoring) (empty? (:drafts task-authoring)))
     [:p {:className "scheduler-empty"} "Loading task-type drafts..."]

     (:drafts-error task-authoring)
     [:p {:className "form-submit-error"} (:drafts-error task-authoring)]

     (empty? (:drafts task-authoring))
     [:p {:className "scheduler-empty"}
      "No task-type drafts yet. Create one from the dialog to inspect and publish it here."]

     :else
     [:div {:className "defs-authoring-draft-list"}
      (for [draft (:drafts task-authoring)]
        ^{:key (shared/definition-ref-label (shared/draft-ref draft))}
        [draft-list-row draft task-authoring])])])

(defn draft-detail-panel
  [task-authoring]
  (let [detail (:draft-detail task-authoring)
        definition (:definition detail)]
    [:section {:className "panel defs-authoring-panel defs-authoring-detail-panel"}
     [:div {:className "defs-authoring-panel-head"}
      [:div
       [:h2 {:className "component-title"} "Draft Inspection"]
       [:p {:className "defs-authoring-copy"}
        "The selected draft is rendered directly from the authoring overlay."]]]
     (cond
       (:draft-detail-loading? task-authoring)
       [:p {:className "scheduler-empty"} "Loading draft detail..."]

       (:draft-detail-error task-authoring)
       [:p {:className "form-submit-error"} (:draft-detail-error task-authoring)]

       (nil? detail)
       [:p {:className "scheduler-empty"}
        "Select a task-type draft to inspect its resolved EDN before publish."]

       :else
       [:div {:className "defs-authoring-detail"}
        [:dl {:className "detail-list"}
         [components/detail-row "Task type" (shared/current-draft-label definition)]
         [components/detail-row "Runtime profile"
          (or (some-> definition :task-type/runtime-profile-ref presenter/ref-label) "template value")]
         [components/detail-row "Description"
          (or (:task-type/description definition) "n/a")]
         [components/detail-row "Input schema"
          (str (count (:task-type/input-schema definition)) " field"
               (when (not= 1 (count (:task-type/input-schema definition))) "s"))]
         [components/detail-row "Work key"
          (presenter/work-key-label (:task-type/work-key-expr definition))]
         [components/detail-row "Resource policy"
          (or (some-> definition :task-type/resource-policy-ref presenter/ref-label) "n/a")]
         [components/detail-row "Validator"
          (or (some-> definition :task-type/validator-ref presenter/ref-label) "n/a")]
         [components/detail-row "Draft path" (:draft-path detail)]]
        [:pre {:className "defs-authoring-code"} (shared/edn-block definition)]])]))
