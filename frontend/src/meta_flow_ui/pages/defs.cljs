(ns meta-flow-ui.pages.defs
  (:require [clojure.string :as str]
            [meta-flow-ui.components :as components]
            [meta-flow-ui.icons :as icons]
            [meta-flow-ui.pages.defs.detail :as defs-detail]
            [meta-flow-ui.pages.defs.list :as defs-list]
            [meta-flow-ui.pages.defs.state :as defs-state]
            [meta-flow-ui.routes :as routes]
            [reagent.core :as r]))

(def primary-tabs
  [{:label "Scheduler" :route :scheduler}
   {:label "Tasks" :route :tasks}
   {:label "Defs" :route :defs}
   {:label "Preview" :route :preview}])

(defn- visible-items
  [items query]
  (let [query-text (some-> query str/lower-case str/trim)]
    (if (str/blank? query-text)
      items
      (->> items
           (filter (fn [item]
                     (some #(and %
                                 (str/includes? (str/lower-case (str %)) query-text))
                           [(:task-type/id item)
                            (:task-type/name item)
                            (:task-type/description item)
                            (some-> item :task-type/runtime-profile :definition/name)
                            (some-> item :task-type/resource-policy :definition/name)])))
           vec))))

(defn- defs-list-page-body []
  (let [search-text (r/atom "")]
    (fn []
      (let [{:keys [items loading? error]} (defs-state/defs-state)
            filtered-items (visible-items items @search-text)
            table-rows (if (empty? filtered-items)
                         [[:tr
                           [:td {:colSpan 5}
                            [:span {:className "scheduler-empty"}
                             "No task types match the current search."]]]]
                         (map defs-list/task-type-row filtered-items))]
        [:main {:className "app-shell"}
         [:section {:className "scheduler-topbar"}
          [:div {:className "scheduler-heading"}
           [:h1 {:className "scheduler-title"} "Task Types"]
           [:p {:className "scheduler-subtitle"}
            "Use the list view to compare stable signals. Open a task type to inspect the full definition surface."]]
          [:div {:className "scheduler-topbar-actions"}
           [components/nav-tabs primary-tabs :defs routes/navigate!]
           [:button {:className "button button-icon button-primary"
                     :title "Refresh"
                     :on-click defs-state/load-items!}
            [icons/refresh]]]]
         [:section {:className "tasks-filter-bar defs-filter-bar"}
          [:label {:className "tasks-filter tasks-filter-query defs-filter-query"}
           [:span {:className "stat-label"} "Search"]
           [:input {:className "text-input"
                    :value @search-text
                    :placeholder "name, id, description, runtime, policy"
                    :on-change #(reset! search-text (.. % -target -value))}]]]
         (when error
           [:section {:className "scheduler-inline-error"}
            [:article {:className "panel scheduler-error-card"}
             [:p {:className "scheduler-error-copy"} error]]])
         (cond
           (and loading? (empty? items))
           [:p {:className "scheduler-empty"} "Loading task types..."]

           (and (not loading?) (empty? items))
           [:p {:className "scheduler-empty"} "No task types found."]

           :else
           [:section {:className "panel scheduler-table-panel"}
            [:div {:className "scheduler-table-header"}
             [:div {:className "tasks-table-status"}
              [:span {:className "tasks-visible-count"}
               (str (count filtered-items) " / " (count items))]
              [:span {:className (str "poll-dot"
                                      (when loading? " poll-dot-loading"))}]]]
            [:div {:className "scheduler-table-wrap"}
             [:table {:className "scheduler-table defs-table"}
              [:colgroup
               [:col {:className "defs-col-name"}]
               [:col {:className "defs-col-input"}]
               [:col {:className "defs-col-work-key"}]
               [:col {:className "defs-col-runtime"}]
               [:col {:className "defs-col-policy"}]]
              [:thead
               [:tr
                [:th "Task type"]
                [:th "Input"]
                [:th "Work key"]
                [:th "Runtime"]
                [:th "Policy"]]]
              (into [:tbody] table-rows)]]])]))))

(defn- defs-list-page []
  (r/with-let [_ (defs-state/load-items!)]
    [defs-list-page-body]))

(defn- defs-detail-page
  [route]
  (r/with-let [_ (defs-state/load-detail! (:task-type-id route)
                                          (:task-type-version route))]
    [defs-detail/detail-page route (defs-state/defs-state) primary-tabs]))

(defn defs-page
  [route]
  (case (:page route)
    :defs-detail [defs-detail-page route]
    [defs-list-page]))
