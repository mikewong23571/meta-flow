(ns meta-flow-ui.pages.defs.authoring.task-type.dialog
  (:require [meta-flow-ui.components :as components]
            [meta-flow-ui.pages.defs.authoring.task-type.shared :as shared]
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

(defn- runtime-selector
  [runtime-items selected-value on-change]
  [:<>
   [:select {:className "tasks-select tasks-select-full"
             :value (or selected-value "")
             :on-change #(on-change (.. % -target -value))}
    [:option {:value ""} "Keep the template runtime profile"]
    (for [runtime-item runtime-items]
      ^{:key (shared/format-runtime-value runtime-item)}
      [:option {:value (shared/format-runtime-value runtime-item)}
       (shared/runtime-option-label runtime-item)])]
   [:span {:className "defs-authoring-copy"}
    "Only published runtime profiles appear here. Leave this blank to keep the template runtime profile."]])

(defn- dialog-field
  [label input error]
  [:label {:className "form-field"}
   [:span {:className "stat-label"} label]
   input
   (when error
     [:span {:className "form-error"} error])])

(defn- update-dialog-field!
  [dialog-state key value]
  (defs-state/reset-task-type-validation-preview!)
  (swap! dialog-state assoc key value))

(defn- validate-preview
  [task-authoring]
  (cond
    (:validation-loading? task-authoring)
    [:p {:className "defs-authoring-copy"} "Validating draft request…"]

    (:validation-error task-authoring)
    [:p {:className "form-submit-error"} (:validation-error task-authoring)]

    (:validation-result task-authoring)
    (let [{:keys [template request]} (:validation-result task-authoring)]
      [:div {:className "defs-authoring-validation"}
       [:div {:className "defs-authoring-validation-meta"}
        [components/detail-row "Template"
         (shared/definition-ref-label template)]
        [components/detail-row "Resolved request id"
         (:authoring/new-id request)]
        [components/detail-row "Resolved version"
         (:authoring/new-version request)]
        [components/detail-row "Runtime profile override"
         (or (some-> request :authoring/overrides :task-type/runtime-profile-ref shared/definition-ref-label)
             "template value")]]
       [:pre {:className "defs-authoring-code"} (shared/edn-block request)]])

    :else
    [:p {:className "defs-authoring-copy"}
     "Validation shows the normalized backend request without writing a draft."]))

(defn task-type-dialog
  [dialog-state task-authoring runtime-items]
  (let [templates (:templates task-authoring)
        current-state @dialog-state
        template (shared/selected-template templates (:selected-template-value current-state))
        field-errors (shared/build-form-errors current-state template)
        request (when (empty? field-errors)
                  (shared/build-request current-state template))]
    (when (:open? current-state)
      [:div {:className "dialog-overlay"
             :on-click (fn [event]
                         (when (= (.-target event) (.-currentTarget event))
                           (defs-state/reset-task-type-authoring-feedback!)
                           (swap! dialog-state assoc :open? false)))}
       [:div {:className "panel panel-strong dialog defs-authoring-dialog"}
        [:div {:className "dialog-header"}
         [:div
          [:h2 {:className "component-title"} "New Task Type"]
          [:p {:className "defs-authoring-copy"}
           "Clone-first authoring against the existing task-type templates."]]]
        [:div {:className "dialog-body"}
         (when (:templates-error task-authoring)
           [:p {:className "form-submit-error"} (:templates-error task-authoring)])
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
                   :placeholder "task-type/repo-review-mock"
                   :on-change #(update-dialog-field! dialog-state :new-id (.. % -target -value))}]
          (:new-id field-errors)]
         [dialog-field
          "New name"
          [:input {:className (str "text-input text-input-full"
                                   (when (:new-name field-errors) " text-input-error"))
                   :value (:new-name current-state)
                   :placeholder "Repo review mock"
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
           "Description override"
           [:textarea {:className "text-input text-input-full"
                       :rows 4
                       :value (:description current-state)
                       :placeholder "Authored mock-backed repo review task"
                       :on-change #(update-dialog-field! dialog-state :description (.. % -target -value))}]
           nil]
          [dialog-field
           "Runtime profile override"
           [runtime-selector runtime-items
            (:selected-runtime-profile-value current-state)
            #(update-dialog-field! dialog-state :selected-runtime-profile-value %)]
           nil]]
         [:p {:className "defs-authoring-copy"}
          "Only published runtime profiles appear in this selector. To switch away from the template runtime, publish the runtime-profile first and then pick it here."]
         (when (:submit-error task-authoring)
           [:p {:className "form-submit-error"} (:submit-error task-authoring)])
         [:section {:className "panel defs-authoring-preview-panel"}
          [:div {:className "defs-authoring-panel-head"}
           [:div
            [:h3 {:className "component-title"} "Validation Preview"]
            [:p {:className "defs-authoring-copy"}
             "Useful for checking the normalized request before you write a draft."]]]
          [validate-preview task-authoring]]]
        [:div {:className "dialog-footer"}
         [:button {:className "button button-ghost"
                   :on-click (fn []
                               (defs-state/reset-task-type-authoring-feedback!)
                               (swap! dialog-state assoc :open? false))
                   :disabled (:submitting? task-authoring)}
          "Cancel"]
         [:button {:className "button button-secondary"
                   :on-click #(when request
                                (defs-state/validate-task-type-draft! request))
                   :disabled (or (:validation-loading? task-authoring)
                                 (:submitting? task-authoring)
                                 (seq field-errors))}
          (if (:validation-loading? task-authoring) "Validating…" "Validate")]
         [:button {:className "button button-primary"
                   :on-click #(when request
                                (-> (defs-state/create-task-type-draft! request)
                                    (.then (fn [payload]
                                             (when payload
                                               (reset! dialog-state
                                                       (shared/blank-dialog-state template)))))))
                   :disabled (or (:submitting? task-authoring)
                                 (:templates-loading? task-authoring)
                                 (seq field-errors))}
          (if (:submitting? task-authoring) "Creating…" "Create Draft")]]]])))
