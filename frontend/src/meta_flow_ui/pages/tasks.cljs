(ns meta-flow-ui.pages.tasks
  (:require [clojure.string :as str]
            [meta-flow-ui.components :as components]
            [meta-flow-ui.icons :as icons]
            [meta-flow-ui.pages.tasks.create :as task-create]
            [meta-flow-ui.pages.tasks.detail :as task-detail]
            [meta-flow-ui.pages.tasks.state :as tasks-page-state]
            [meta-flow-ui.routes :as routes]
            [reagent.core :as r]))

(def primary-tabs
  [{:label "Scheduler" :route :scheduler}
   {:label "Tasks" :route :tasks}
   {:label "Defs" :route :defs}
   {:label "Preview" :route :preview}])

(defn task-state-label
  [value]
  (some-> value str (str/split #"/") last))

(defn short-id
  [value]
  (let [text (some-> value str)]
    (cond
      (nil? text) nil
      (<= (count text) 18) text
      :else (subs text 0 13))))

(defn compact-timestamp
  [value]
  (let [text (some-> value str)]
    (if (and text (>= (count text) 16))
      (str (subs text 0 10) " " (subs text 11 16))
      text)))

(defn distinct-options
  [items key-fn]
  (->> items
       (map key-fn)
       (remove nil?)
       distinct
       sort
       vec))

(defn filtered-items
  [{:keys [items filters]}]
  (let [{:keys [task-state task-type query]} filters
        query-text (some-> query str/lower-case str/trim)]
    (->> items
         (filter (fn [item]
                   (and (or (= "all" task-state)
                            (= task-state (str (:task/state item))))
                        (or (= "all" task-type)
                            (= task-type (str (:task/type-id item))))
                        (or (str/blank? query-text)
                            (some #(and %
                                        (str/includes? (str/lower-case %) query-text))
                                  [(:task/id item)
                                   (:task/work-key item)
                                   (:task/type-name item)
                                   (get-in item [:task/summary :primary])
                                   (get-in item [:task/summary :secondary])])))))
         vec)))

(defn task-row
  [item]
  [:tr {:className "scheduler-table-row"
        :key (:task/id item)
        :on-click #(tasks-page-state/load-detail! :task (:task/id item))
        :style {:cursor "pointer"}}
   [:td
    [:div {:className "table-link-button"
           :title (:task/id item)}
     (short-id (:task/id item))]
    [:div {:className "table-subtext table-subtext-mono"}
     (:task/work-key item)]]
   [:td
    [:div {:className "tasks-summary-primary"
           :title (get-in item [:task/summary :primary])}
     (get-in item [:task/summary :primary])]]
   [:td
    [components/badge
     (task-detail/status-tone (task-state-label (:task/state item)))
     (task-state-label (:task/state item))]]
   [:td {:className "table-subtext table-subtext-mono"
         :title (:task/updated-at item)}
    (compact-timestamp (:task/updated-at item))]])

(defn task-table
  [visible-items]
  [:table {:className "scheduler-table tasks-table"}
   [:colgroup
    [:col {:className "tasks-col-task"}]
    [:col {:className "tasks-col-summary"}]
    [:col {:className "tasks-col-state"}]
    [:col {:className "tasks-col-updated"}]]
   [:thead
    [:tr
     [:th "Task"]
     [:th "Summary"]
     [:th "State"]
     [:th "Updated"]]]
   (into
    [:tbody]
    (if (empty? visible-items)
      [[:tr
        [:td {:colSpan 4}
         [:span {:className "scheduler-empty"}
          "No tasks match the current filters."]]]]
      (map task-row visible-items)))])

(defn tasks-page-body []
  (let [page-state (tasks-page-state/tasks-state)
        {:keys [items loading? error filters create-dialog]} page-state
        visible-items (filtered-items page-state)
        task-type-options (distinct-options items #(str (:task/type-id %)))
        task-state-options (distinct-options items #(str (:task/state %)))]
    [:main {:className "app-shell"}
     [:section {:className "scheduler-topbar"}
      [:div {:className "scheduler-heading"}
       [:h1 {:className "scheduler-title"} "Tasks"]]
      [:div {:className "scheduler-topbar-actions"}
       [components/nav-tabs primary-tabs :tasks routes/navigate!]
       [:button {:className "button button-icon button-ghost"
                 :title "New Task"
                 :on-click tasks-page-state/open-create-dialog!}
        [icons/plus]]
       [:button {:className "button button-icon button-primary"
                 :title "Refresh"
                 :on-click tasks-page-state/load-items!}
        [icons/refresh]]]]
     [:section {:className "tasks-filter-bar"}
      [:label {:className "tasks-filter"}
       [:span {:className "stat-label"} "Task state"]
       [:select {:className "tasks-select"
                 :value (:task-state filters)
                 :on-change #(tasks-page-state/update-filter! :task-state (.. % -target -value))}
        [:option {:value "all"} "All"]
        (for [value task-state-options]
          ^{:key value}
          [:option {:value value} (task-state-label value)])]]
      [:label {:className "tasks-filter"}
       [:span {:className "stat-label"} "Task type"]
       [:select {:className "tasks-select"
                 :value (:task-type filters)
                 :on-change #(tasks-page-state/update-filter! :task-type (.. % -target -value))}
        [:option {:value "all"} "All"]
        (for [value task-type-options]
          ^{:key value}
          [:option {:value value} value])]]
      [:label {:className "tasks-filter tasks-filter-query"}
       [:span {:className "stat-label"} "Search"]
       [:input {:className "text-input"
                :value (:query filters)
                :placeholder "task id, work key, summary"
                :on-change #(tasks-page-state/update-filter! :query (.. % -target -value))}]]]
     (when error
       [:section {:className "scheduler-inline-error"}
        [:article {:className "panel scheduler-error-card"}
         [:p {:className "scheduler-error-copy"} error]]])
     [:section {:className "detail-layout"}
      [:section {:className "panel scheduler-table-panel"}
       [:div {:className "scheduler-table-header"}
        [:div {:className "tasks-table-status"}
         [:span {:className "tasks-visible-count"}
          (str (count visible-items) " / " (count items))]
         [:span {:className (str "poll-dot"
                                 (if loading? " poll-dot-loading" ""))}]]]
       [:div {:className "scheduler-table-wrap"}
        [task-table visible-items]]]
      [task-detail/detail-panel page-state tasks-page-state/clear-detail! task-state-label]]
     (when (:open? create-dialog)
       [task-create/create-dialog create-dialog])]))

(defn tasks-page []
  (r/with-let [_ (tasks-page-state/ensure-polling!)]
    [tasks-page-body]
    (finally
      (tasks-page-state/stop-polling!))))
