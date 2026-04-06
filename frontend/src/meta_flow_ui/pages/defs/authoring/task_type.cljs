(ns meta-flow-ui.pages.defs.authoring.task-type
  (:require [meta-flow-ui.pages.defs.authoring.task-type.dialog :as dialog]
            [meta-flow-ui.pages.defs.authoring.task-type.drafts :as drafts]
            [meta-flow-ui.pages.defs.authoring.task-type.shared :as shared]
            [meta-flow-ui.pages.defs.state :as defs-state]
            [reagent.core :as r]))

(defn task-type-authoring-section
  [authoring runtime-items runtime-error task-items]
  (r/with-let [dialog-state (r/atom (shared/blank-dialog-state nil))]
    (let [task-authoring (:task-type authoring)
          first-template (first (:templates task-authoring))]
      [:<>
       [dialog/task-type-dialog dialog-state task-authoring runtime-items]
       [shared/summary-panel authoring task-authoring runtime-items runtime-error task-items]
       [:section {:className "defs-authoring-drafts-layout"}
        [drafts/draft-list-panel task-authoring]
        [drafts/draft-detail-panel task-authoring]]
       [:div {:className "defs-authoring-footer-actions"}
        [:button {:className "button button-icon button-primary"
                  :on-click (fn []
                              (defs-state/reset-task-type-authoring-feedback!)
                              (reset! dialog-state
                                      (assoc (shared/blank-dialog-state first-template)
                                             :open? true)))
                  :disabled (or (:templates-loading? task-authoring)
                                (empty? (:templates task-authoring)))}
         "New Task Type"]]])))
