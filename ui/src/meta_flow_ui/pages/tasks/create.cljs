(ns meta-flow-ui.pages.tasks.create
  (:require [meta-flow-ui.pages.tasks.state :as tasks-state]))

(defn- field-input-type [field-type]
  (if (= field-type "field.type/email") "email" "text"))

(defn- render-field
  [{field-id    :field/id
    field-label :field/label
    field-type  :field/type
    placeholder :field/placeholder
    required?   :field/required?}
   form-values
   form-errors]
  (let [value (get form-values field-id "")
        error (get form-errors field-id)]
    [:label {:className "form-field" :key (str field-id)}
     [:span {:className "stat-label"} field-label]
     [:input {:className (str "text-input text-input-full"
                              (when error " text-input-error"))
              :type (field-input-type field-type)
              :value value
              :placeholder (or placeholder "")
              :required required?
              :on-change #(tasks-state/set-create-field! field-id (.. % -target -value))}]
     (when error
       [:span {:className "form-error"} error])]))

(defn- type-selector
  [task-types selected-type-id]
  [:label {:className "form-field"}
   [:span {:className "stat-label"} "Task Type"]
   [:select {:className "tasks-select tasks-select-full"
             :value (or selected-type-id "")
             :on-change #(tasks-state/set-create-type! (.. % -target -value))}
    (for [tt task-types]
      ^{:key (:task-type/id tt)}
      [:option {:value (:task-type/id tt)} (:task-type/name tt)])]])

(defn- selected-schema
  [task-types selected-type-id]
  (->> task-types
       (filter #(= (:task-type/id %) selected-type-id))
       first
       :task-type/input-schema))

(defn create-dialog
  [{:keys [task-types task-types-loading? selected-type-id
           form-values form-errors submitting? submit-error]}]
  [:div {:className "dialog-overlay"
         :on-click (fn [e]
                     (when (= (.-target e) (.-currentTarget e))
                       (tasks-state/close-create-dialog!)))}
   [:div {:className "panel panel-strong dialog"}
    [:div {:className "dialog-header"}
     [:h2 {:className "component-title"} "New Task"]]
    [:div {:className "dialog-body"}
     (cond
       task-types-loading?
       [:p {:className "scheduler-empty"} "Loading task types..."]

       (empty? task-types)
       [:p {:className "scheduler-error-copy"} "No task types available."]

       :else
       (let [schema (selected-schema task-types selected-type-id)]
         [:<>
          [type-selector task-types selected-type-id]
          (for [field schema]
            (render-field field form-values form-errors))]))
     (when submit-error
       [:p {:className "form-submit-error"} submit-error])]
    [:div {:className "dialog-footer"}
     [:button {:className "button button-ghost"
               :on-click tasks-state/close-create-dialog!
               :disabled submitting?}
      "Cancel"]
     [:button {:className "button button-primary"
               :on-click tasks-state/submit-create-task!
               :disabled (or submitting? task-types-loading? (empty? task-types))}
      (if submitting? "Creating…" "Create")]]]])
