(ns meta-flow-ui.pages.defs.authoring.task-type.generation-result
  (:require [meta-flow-ui.components :as components]
            [meta-flow-ui.pages.defs.authoring.task-type.shared :as shared]
            [meta-flow-ui.pages.defs.state :as defs-state]
            [meta-flow-ui.routes :as routes]))

(defn- result-card
  [tone title body]
  [:article {:className (str "panel defs-authoring-status defs-authoring-status-" tone)}
   [:div {:className "defs-authoring-status-head"}
    [components/badge tone title]]
   [:div {:className "defs-authoring-status-body"}
    body]])

(defn- runtime-definition-ref
  [definition]
  {:definition/id (:runtime-profile/id definition)
   :definition/version (:runtime-profile/version definition)})

(defn- current-runtime-label
  [definition]
  (shared/definition-ref-label (runtime-definition-ref definition)))

(defn- generated-runtime-card
  [runtime-result]
  [result-card
   "warning"
   "Runtime Draft Required"
   [:<>
    [:p {:className "defs-authoring-copy"}
     "Generation created a runtime-profile draft that must be published before the task type."]
    [:p {:className "defs-authoring-meta"}
     (current-runtime-label (:definition runtime-result))]
    [:p {:className "defs-authoring-draft-path"} (:draft-path runtime-result)]
    [:button {:className "button button-secondary"
              :on-click #(do
                           (defs-state/load-runtime-profile-draft-detail!
                            (:runtime-profile/id (:definition runtime-result))
                            (:runtime-profile/version (:definition runtime-result)))
                           (routes/navigate! :defs-runtimes))}
     "Open Runtime Drafts"]]])

(defn- generated-task-card
  [task-result]
  [result-card
   "success"
   "Task-Type Draft Ready"
   [:<>
    [:p {:className "defs-authoring-copy"}
     "The generated task-type draft is ready for inspection in the draft panel below."]
    [:p {:className "defs-authoring-meta"}
     (shared/current-draft-label (:definition task-result))]
    [:p {:className "defs-authoring-draft-path"} (:draft-path task-result)]
    [:button {:className "button button-secondary"
              :on-click #(defs-state/load-task-type-draft-detail!
                          (:task-type/id (:definition task-result))
                          (:task-type/version (:definition task-result)))}
     "Inspect Generated Draft"]]])

(defn generation-result-panel
  [generation-state]
  (when (or (:result generation-state)
            (:submit-error generation-state)
            (:submitting? generation-state))
    [:section {:className "panel defs-authoring-panel defs-authoring-generation-panel"}
     [:div {:className "defs-authoring-panel-head"}
      [:div
       [:h2 {:className "component-title"} "Description Generation"]
       [:p {:className "defs-authoring-copy"}
        "Generated results stay as drafts until you publish them. The browser keeps draft inspection and publish as separate steps."]]]
     (cond
       (:submitting? generation-state)
       [:p {:className "scheduler-empty"} "Generating drafts from the description..."]

       (:submit-error generation-state)
       [result-card
        "danger"
        "Generation Error"
        [:p {:className "defs-authoring-copy"} (:submit-error generation-state)]]

       :else
       (let [{:keys [generation/description runtime-profile task-type notes]} (:result generation-state)]
         [:div {:className "defs-authoring-generation-grid"}
          [:div {:className "defs-authoring-support-block"}
           [:p {:className "stat-label"} "Description"]
           [:p {:className "defs-authoring-copy"} description]]
          (when runtime-profile
            [generated-runtime-card runtime-profile])
          (when task-type
            [generated-task-card task-type])
          (when (seq notes)
            [:div {:className "defs-authoring-support-block"}
             [:p {:className "stat-label"} "Returned guidance"]
             [:ul {:className "defs-authoring-note-list"}
              (for [note notes]
                ^{:key note}
                [:li {:className "defs-authoring-copy"} note])]])]))]))
