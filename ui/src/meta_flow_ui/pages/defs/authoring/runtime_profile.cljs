(ns meta-flow-ui.pages.defs.authoring.runtime-profile
  (:require [meta-flow-ui.pages.defs.authoring.runtime-profile.dialog :as dialog]
            [meta-flow-ui.pages.defs.authoring.runtime-profile.drafts :as drafts]
            [meta-flow-ui.pages.defs.authoring.runtime-profile.shared :as shared]
            [meta-flow-ui.pages.defs.state :as defs-state]
            [reagent.core :as r]))

(defn- show-draft-detail-panel?
  [runtime-authoring]
  (or (:draft-detail-loading? runtime-authoring)
      (:draft-detail-error runtime-authoring)
      (:draft-detail runtime-authoring)
      (seq (:drafts runtime-authoring))))

(defn runtime-profile-authoring-section
  [authoring runtime-items]
  (r/with-let [dialog-state (r/atom (shared/blank-dialog-state nil))]
    (let [runtime-authoring (:runtime-profile authoring)
          first-template (first (:templates runtime-authoring))]
      [:<>
       [dialog/runtime-profile-dialog dialog-state runtime-authoring]
       [shared/summary-panel authoring runtime-authoring runtime-items]
       [:div {:className "defs-authoring-footer-actions"}
        [:button {:className "button button-primary"
                  :on-click (fn []
                              (defs-state/reset-runtime-profile-authoring-feedback!)
                              (reset! dialog-state
                                      (assoc (shared/blank-dialog-state first-template)
                                             :open? true)))
                  :disabled (or (:templates-loading? runtime-authoring)
                                (empty? (:templates runtime-authoring)))}
         "New Runtime Profile"]]
       (if (show-draft-detail-panel? runtime-authoring)
         [:section {:className "defs-authoring-drafts-layout"}
          [drafts/draft-list-panel runtime-authoring]
          [drafts/draft-detail-panel runtime-authoring]]
         [drafts/draft-list-panel runtime-authoring])])))
