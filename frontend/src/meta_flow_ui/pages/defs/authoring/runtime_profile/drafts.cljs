(ns meta-flow-ui.pages.defs.authoring.runtime-profile.drafts
  (:require [meta-flow-ui.components :as components]
            [meta-flow-ui.pages.defs.authoring.runtime-profile.shared :as shared]
            [meta-flow-ui.pages.defs.state :as defs-state]))

(defn- draft-list-row
  [draft runtime-authoring]
  (let [selected-ref (some-> runtime-authoring :draft-detail :definition shared/runtime-definition-ref)
        draft-ref-value (shared/draft-ref draft)
        publishing? (= draft-ref-value (:publishing-ref runtime-authoring))]
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
                :on-click #(defs-state/load-runtime-profile-draft-detail!
                            (:definition/id draft-ref-value)
                            (:definition/version draft-ref-value))}
       "Inspect"]
      [:button {:className "button button-primary"
                :on-click #(defs-state/publish-runtime-profile-draft! draft-ref-value)
                :disabled publishing?}
       (if publishing? "Publishing…" "Publish")]]]))

(defn draft-list-panel
  [runtime-authoring]
  [:section {:className "panel defs-authoring-panel defs-authoring-drafts-panel"}
   [:div {:className "defs-authoring-panel-head"}
    [:div
     [:h2 {:className "component-title"} "Runtime Drafts"]
     [:p {:className "defs-authoring-copy"}
      "Inspect or publish any saved runtime-profile draft from the overlay workspace."]]]
   (cond
     (and (:drafts-loading? runtime-authoring) (empty? (:drafts runtime-authoring)))
     [:p {:className "scheduler-empty"} "Loading runtime drafts..."]

     (:drafts-error runtime-authoring)
     [:p {:className "form-submit-error"} (:drafts-error runtime-authoring)]

     (empty? (:drafts runtime-authoring))
     [:p {:className "scheduler-empty"}
      "No runtime-profile drafts yet. Create one from the dialog to inspect and publish it here."]

     :else
     [:div {:className "defs-authoring-draft-list"}
      (for [draft (:drafts runtime-authoring)]
        ^{:key (shared/definition-ref-label (shared/draft-ref draft))}
        [draft-list-row draft runtime-authoring])])])

(defn draft-detail-panel
  [runtime-authoring]
  (let [detail (:draft-detail runtime-authoring)
        definition (:definition detail)]
    [:section {:className "panel defs-authoring-panel defs-authoring-detail-panel"}
     [:div {:className "defs-authoring-panel-head"}
      [:div
       [:h2 {:className "component-title"} "Draft Inspection"]
       [:p {:className "defs-authoring-copy"}
        "The selected draft is rendered directly from the authoring overlay."]]]
     (cond
       (:draft-detail-loading? runtime-authoring)
       [:p {:className "scheduler-empty"} "Loading draft detail..."]

       (:draft-detail-error runtime-authoring)
       [:p {:className "form-submit-error"} (:draft-detail-error runtime-authoring)]

       (nil? detail)
       [:p {:className "scheduler-empty"}
        "Select a runtime draft to inspect its resolved EDN before publish."]

       :else
       [:div {:className "defs-authoring-detail"}
        [:dl {:className "detail-list"}
         [components/detail-row "Runtime profile" (shared/current-draft-label definition)]
         [components/detail-row "Adapter" (:runtime-profile/adapter-id definition)]
         [components/detail-row "Dispatch" (:runtime-profile/dispatch-mode definition)]
         [components/detail-row "Launch mode" (:runtime-profile/default-launch-mode definition)]
         [components/detail-row "Web search"
          (if (= true (:runtime-profile/web-search-enabled? definition)) "on" "off")]
         [components/detail-row "Worker prompt" (or (:runtime-profile/worker-prompt-path definition) "n/a")]
         [components/detail-row "Helper script" (or (:runtime-profile/helper-script-path definition) "n/a")]
         [components/detail-row "Draft path" (:draft-path detail)]]
        [:pre {:className "defs-authoring-code"} (shared/edn-block definition)]])]))
