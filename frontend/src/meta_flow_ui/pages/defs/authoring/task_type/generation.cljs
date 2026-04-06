(ns meta-flow-ui.pages.defs.authoring.task-type.generation
  (:require [clojure.string :as str]
            [meta-flow-ui.components :as components]
            [meta-flow-ui.pages.defs.authoring.task-type.shared :as shared]
            [meta-flow-ui.pages.defs.state :as defs-state]
            [meta-flow-ui.routes :as routes]))

(defn blank-dialog-state
  []
  {:open? false
   :description ""
   :selected-task-template-value ""
   :selected-runtime-template-value ""
   :new-task-type-id ""
   :new-task-type-name ""})

(defn- optional-template-selector
  [empty-label options selected-value format-value option-label on-change]
  [:select {:className "tasks-select tasks-select-full"
            :value (or selected-value "")
            :on-change #(on-change (.. % -target -value))}
   [:option {:value ""} empty-label]
   (for [option options]
     ^{:key (format-value option)}
     [:option {:value (format-value option)}
      (option-label option)])])

(defn- dialog-field
  [label input error]
  [:label {:className "form-field"}
   [:span {:className "stat-label"} label]
   input
   (when error
     [:span {:className "form-error"} error])])

(defn- runtime-definition-ref
  [definition]
  {:definition/id (:runtime-profile/id definition)
   :definition/version (:runtime-profile/version definition)})

(defn- current-runtime-label
  [definition]
  (shared/definition-ref-label (runtime-definition-ref definition)))

(defn- build-form-errors
  [dialog-state]
  (let [description (some-> (:description dialog-state) str/trim)
        task-type-id (some-> (:new-task-type-id dialog-state) str/trim)]
    (cond-> {}
      (str/blank? description)
      (assoc :description "Enter a natural-language task description before generating drafts.")

      (and (not (str/blank? task-type-id))
           (not (str/starts-with? task-type-id "task-type/")))
      (assoc :new-task-type-id "Use the task-type/... keyword namespace when overriding the generated id."))))

(defn- build-request
  [dialog-state]
  (let [description (some-> (:description dialog-state) str/trim)
        task-template (some-> (:selected-task-template-value dialog-state) str/trim not-empty shared/parse-template-value)
        runtime-template (some-> (:selected-runtime-template-value dialog-state) str/trim not-empty shared/parse-runtime-value)
        task-type-id (some-> (:new-task-type-id dialog-state) str/trim not-empty)
        task-type-name (some-> (:new-task-type-name dialog-state) str/trim not-empty)]
    (cond-> {:generation/description description}
      task-template
      (assoc :generation/task-type-template-id (:definition/id task-template)
             :generation/task-type-template-version (:definition/version task-template))

      runtime-template
      (assoc :generation/runtime-profile-template-id (:definition/id runtime-template)
             :generation/runtime-profile-template-version (:definition/version runtime-template))

      task-type-id
      (assoc :generation/task-type-id task-type-id)

      task-type-name
      (assoc :generation/task-type-name task-type-name))))

(defn generation-dialog
  [dialog-state task-authoring runtime-items generation-state]
  (let [current-state @dialog-state
        field-errors (build-form-errors current-state)
        request (when (empty? field-errors)
                  (build-request current-state))]
    (when (:open? current-state)
      [:div {:className "dialog-overlay"
             :on-click (fn [event]
                         (when (= (.-target event) (.-currentTarget event))
                           (defs-state/reset-task-type-generation-feedback!)
                           (swap! dialog-state assoc :open? false)))}
       [:div {:className "panel panel-strong dialog defs-authoring-dialog"}
        [:div {:className "dialog-header"}
         [:div
          [:h2 {:className "component-title"} "Generate Task Type From Description"]
          [:p {:className "defs-authoring-copy"}
           "Turn a plain-language request into one task-type draft, or a linked runtime-profile plus task-type draft pair."]]]
        [:div {:className "dialog-body"}
         [dialog-field
          "Description"
          [:textarea {:className (str "text-input text-input-full"
                                      (when (:description field-errors) " text-input-error"))
                      :rows 6
                      :value (:description current-state)
                      :placeholder "Create a repo review task that uses Codex, disables web search, and emits a markdown report."
                      :on-change #(swap! dialog-state assoc :description (.. % -target -value))}]
          (:description field-errors)]
         [:div {:className "defs-authoring-form-grid"}
          [dialog-field
           "Task-type template"
           [optional-template-selector
            "Infer from description"
            (:templates task-authoring)
            (:selected-task-template-value current-state)
            shared/format-template-value
            (fn [template]
              (str (:definition/name template)
                   "  ["
                   (:definition/id template)
                   " v"
                   (:definition/version template)
                   "]"))
            #(swap! dialog-state assoc :selected-task-template-value %)]
           nil]
          [dialog-field
           "Runtime template"
           [optional-template-selector
            "Infer from description"
            runtime-items
            (:selected-runtime-template-value current-state)
            shared/format-runtime-value
            shared/runtime-option-label
            #(swap! dialog-state assoc :selected-runtime-template-value %)]
           nil]]
         [:div {:className "defs-authoring-form-grid"}
          [dialog-field
           "Generated task-type id"
           [:input {:className (str "text-input text-input-full"
                                    (when (:new-task-type-id field-errors) " text-input-error"))
                    :value (:new-task-type-id current-state)
                    :placeholder "task-type/repo-review-generated"
                    :on-change #(swap! dialog-state assoc :new-task-type-id (.. % -target -value))}]
           (:new-task-type-id field-errors)]
          [dialog-field
           "Generated task-type name"
           [:input {:className "text-input text-input-full"
                    :value (:new-task-type-name current-state)
                    :placeholder "Repo review generated"
                    :on-change #(swap! dialog-state assoc :new-task-type-name (.. % -target -value))}]
           nil]]
         [:p {:className "defs-authoring-copy"}
          "Generation still writes drafts into the overlay workspace. Inspect the resulting drafts and publish them in the order described below."]
         (when (:submit-error generation-state)
           [:p {:className "form-submit-error"} (:submit-error generation-state)])]
        [:div {:className "dialog-footer"}
         [:button {:className "button button-ghost"
                   :on-click (fn []
                               (defs-state/reset-task-type-generation-feedback!)
                               (swap! dialog-state assoc :open? false))
                   :disabled (:submitting? generation-state)}
          "Cancel"]
         [:button {:className "button button-primary"
                   :on-click #(when request
                                (-> (defs-state/generate-task-type-draft! request)
                                    (.then (fn [payload]
                                             (when payload
                                               (reset! dialog-state (blank-dialog-state))
                                               (swap! dialog-state assoc :open? false))))))
                   :disabled (or (:submitting? generation-state)
                                 (seq field-errors))}
          (if (:submitting? generation-state) "Generating…" "Generate Drafts")]]]])))

(defn- result-card
  [tone title body]
  [:article {:className (str "panel defs-authoring-status defs-authoring-status-" tone)}
   [:div {:className "defs-authoring-status-head"}
    [components/badge tone title]]
   [:div {:className "defs-authoring-status-body"}
    body]])

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
