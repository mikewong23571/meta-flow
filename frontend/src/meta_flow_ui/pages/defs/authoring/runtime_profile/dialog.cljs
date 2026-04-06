(ns meta-flow-ui.pages.defs.authoring.runtime-profile.dialog
  (:require [meta-flow-ui.components :as components]
            [meta-flow-ui.pages.defs.authoring.runtime-profile.shared :as shared]
            [meta-flow-ui.pages.defs.state :as defs-state]))

(defn- template-selector
  [templates selected-value on-change]
  [:label {:className "form-field"}
   [:span {:className "stat-label"} "Template"]
   [:select {:className "tasks-select tasks-select-full"
             :value (or selected-value "")
             :on-change #(on-change (.. % -target -value))}
    (for [template templates]
      ^{:key (shared/format-template-value template)}
      [:option {:value (shared/format-template-value template)}
       (str (:definition/name template)
            "  ["
            (:definition/id template)
            " v"
            (:definition/version template)
            "]")])]])

(defn- dialog-field
  [label input error]
  [:label {:className "form-field"}
   [:span {:className "stat-label"} label]
   input
   (when error
     [:span {:className "form-error"} error])])

(defn- update-dialog-field!
  [dialog-state key value]
  (defs-state/reset-runtime-profile-validation-preview!)
  (swap! dialog-state assoc key value))

(defn- validate-preview
  [runtime-authoring]
  (cond
    (:validation-loading? runtime-authoring)
    [:p {:className "defs-authoring-copy"} "Validating draft request…"]

    (:validation-error runtime-authoring)
    [:p {:className "form-submit-error"} (:validation-error runtime-authoring)]

    (:validation-result runtime-authoring)
    (let [{:keys [template request]} (:validation-result runtime-authoring)]
      [:div {:className "defs-authoring-validation"}
       [:div {:className "defs-authoring-validation-meta"}
        [components/detail-row "Template"
         (shared/definition-ref-label template)]
        [components/detail-row "Resolved request id"
         (:authoring/new-id request)]
        [components/detail-row "Resolved version"
         (:authoring/new-version request)]]
       [:pre {:className "defs-authoring-code"} (shared/edn-block request)]])

    :else
    [:p {:className "defs-authoring-copy"}
     "Validation shows the normalized backend request without writing a draft."]))

(defn runtime-profile-dialog
  [dialog-state runtime-authoring]
  (let [templates (:templates runtime-authoring)
        current-state @dialog-state
        template (shared/selected-template templates (:selected-template-value current-state))
        field-errors (shared/build-form-errors current-state template)
        request (when (empty? field-errors)
                  (shared/build-request current-state template))]
    (when (:open? current-state)
      [:div {:className "dialog-overlay"
             :on-click (fn [event]
                         (when (= (.-target event) (.-currentTarget event))
                           (defs-state/reset-runtime-profile-authoring-feedback!)
                           (swap! dialog-state assoc :open? false)))}
       [:div {:className "panel panel-strong dialog defs-authoring-dialog"}
        [:div {:className "dialog-header"}
         [:div
          [:h2 {:className "component-title"} "New Runtime Profile"]
          [:p {:className "defs-authoring-copy"}
           "Clone-first authoring against the existing runtime-profile templates."]]]
        [:div {:className "dialog-body"}
         (when (:templates-error runtime-authoring)
           [:p {:className "form-submit-error"} (:templates-error runtime-authoring)])
         (when-let [template-error (:template field-errors)]
           [:p {:className "form-submit-error"} template-error])
         (when (seq templates)
           [template-selector templates
            (or (:selected-template-value current-state)
                (when template (shared/format-template-value template)))
            #(update-dialog-field! dialog-state :selected-template-value %)])
         [dialog-field
          "New id"
          [:input {:className (str "text-input text-input-full"
                                   (when (:new-id field-errors) " text-input-error"))
                   :value (:new-id current-state)
                   :placeholder "runtime-profile/repo-review-mock"
                   :on-change #(update-dialog-field! dialog-state :new-id (.. % -target -value))}]
          (:new-id field-errors)]
         [dialog-field
          "New name"
          [:input {:className (str "text-input text-input-full"
                                   (when (:new-name field-errors) " text-input-error"))
                   :value (:new-name current-state)
                   :placeholder "Repo review mock worker"
                   :on-change #(update-dialog-field! dialog-state :new-name (.. % -target -value))}]
          (:new-name field-errors)]
         [dialog-field
          "New version"
          [:input {:className (str "text-input text-input-full"
                                   (when (:new-version field-errors) " text-input-error"))
                   :value (:new-version current-state)
                   :placeholder "1"
                   :on-change #(update-dialog-field! dialog-state :new-version (.. % -target -value))}]
          (:new-version field-errors)]
         [:div {:className "defs-authoring-form-grid"}
          [dialog-field
           "Web search"
           [:select {:className "tasks-select tasks-select-full"
                     :value (:web-search-mode current-state)
                     :on-change #(update-dialog-field! dialog-state :web-search-mode (.. % -target -value))}
            [:option {:value "inherit"} "Use template value"]
            [:option {:value "enabled"} "Force enabled"]
            [:option {:value "disabled"} "Force disabled"]]
           nil]
          [dialog-field
           "Worker prompt path"
           [:input {:className "text-input text-input-full"
                    :value (:worker-prompt-path current-state)
                    :placeholder "meta_flow/prompts/worker.md"
                    :on-change #(update-dialog-field! dialog-state :worker-prompt-path (.. % -target -value))}]
           nil]]
         (when (:submit-error runtime-authoring)
           [:p {:className "form-submit-error"} (:submit-error runtime-authoring)])
         [:section {:className "panel defs-authoring-preview-panel"}
          [:div {:className "defs-authoring-panel-head"}
           [:div
            [:h3 {:className "component-title"} "Validation Preview"]
            [:p {:className "defs-authoring-copy"}
             "Useful for checking the normalized request before you write a draft."]]]
          [validate-preview runtime-authoring]]]
        [:div {:className "dialog-footer"}
         [:button {:className "button button-ghost"
                   :on-click (fn []
                               (defs-state/reset-runtime-profile-authoring-feedback!)
                               (swap! dialog-state assoc :open? false))
                   :disabled (:submitting? runtime-authoring)}
          "Cancel"]
         [:button {:className "button button-secondary"
                   :on-click #(when request
                                (defs-state/validate-runtime-profile-draft! request))
                   :disabled (or (:validation-loading? runtime-authoring)
                                 (:submitting? runtime-authoring)
                                 (seq field-errors))}
          (if (:validation-loading? runtime-authoring) "Validating…" "Validate")]
         [:button {:className "button button-primary"
                   :on-click #(when request
                                (-> (defs-state/create-runtime-profile-draft! request)
                                    (.then (fn [payload]
                                             (when payload
                                               (reset! dialog-state
                                                       (shared/blank-dialog-state template)))))))
                   :disabled (or (:submitting? runtime-authoring)
                                 (:templates-loading? runtime-authoring)
                                 (seq field-errors))}
          (if (:submitting? runtime-authoring) "Creating…" "Create Draft")]]]])))
