(ns meta-flow-ui.pages.defs
  (:require [clojure.string :as str]
            [meta-flow-ui.components :as components]
            [meta-flow-ui.icons :as icons]
            [meta-flow-ui.pages.defs.authoring.runtime-profile :as runtime-authoring]
            [meta-flow-ui.pages.defs.authoring.task-type :as task-authoring]
            [meta-flow-ui.pages.defs.detail :as defs-detail]
            [meta-flow-ui.pages.defs.list :as defs-list]
            [meta-flow-ui.pages.defs.state :as defs-state]
            [meta-flow-ui.routes :as routes]
            [reagent.core :as r]))

(def defs-tabs
  [{:label "Task Types" :route :defs}
   {:label "Runtime Profiles" :route :defs-runtimes}])

(defn- visible-task-types
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

(defn- visible-runtime-profiles
  [items query]
  (let [query-text (some-> query str/lower-case str/trim)]
    (if (str/blank? query-text)
      items
      (letfn [(matches? [value]
                (and value
                     (str/includes? (str/lower-case (str value)) query-text)))]
        (->> items
             (filter (fn [item]
                       (or (some matches?
                                 [(:runtime-profile/id item)
                                  (:runtime-profile/name item)
                                  (:runtime-profile/adapter-id item)
                                  (:runtime-profile/dispatch-mode item)
                                  (:runtime-profile/default-launch-mode item)
                                  (:runtime-profile/worker-prompt-path item)
                                  (some-> item :runtime-profile/artifact-contract :definition/name)])
                           (some (fn [task-type]
                                   (or (matches? (:task-type/id task-type))
                                       (matches? (:task-type/name task-type))))
                                 (:runtime-profile/task-types item)))))
             vec)))))

(defn- task-type-list-page-body
  []
  (let [search-text (r/atom "")]
    (fn []
      (let [{:keys [items loading? error runtime-items runtime-error authoring]} (defs-state/defs-state)
            filtered-items (visible-task-types items @search-text)
            table-rows (if (empty? filtered-items)
                         [[:tr
                           [:td {:colSpan 5}
                            [:span {:className "scheduler-empty"}
                             "No task types match the current search."]]]]
                         (map defs-list/task-type-row filtered-items))
            list-section (cond
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
                              (into [:tbody] table-rows)]]])]
        [components/page-shell
         {:active-route :defs
          :title "Defs"
          :subtitle "Task types, runtime profiles, and the workflow contracts bundled with this repo."
          :actions [:button {:className "button button-icon button-primary"
                             :title "Refresh"
                             :on-click defs-state/load-items!}
                    [icons/refresh]]
          :subnav [:div {:className "defs-subnav"}
                   [components/nav-tabs defs-tabs :defs routes/navigate! "Definition sections"]]}
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
         list-section
         [task-authoring/task-type-authoring-section authoring runtime-items runtime-error items]]))))

(defn- runtime-list-page-body
  []
  (let [search-text (r/atom "")]
    (fn []
      (let [{:keys [runtime-items runtime-loading? runtime-error authoring]} (defs-state/defs-state)
            filtered-items (visible-runtime-profiles runtime-items @search-text)
            table-rows (if (empty? filtered-items)
                         [[:tr
                           [:td {:colSpan 5}
                            [:span {:className "scheduler-empty"}
                             "No runtime profiles match the current search."]]]]
                         (map defs-list/runtime-profile-row filtered-items))
            list-section (cond
                           (and runtime-loading? (empty? runtime-items))
                           [:p {:className "scheduler-empty"} "Loading runtime profiles..."]

                           (and (not runtime-loading?) (empty? runtime-items))
                           [:p {:className "scheduler-empty"} "No runtime profiles found."]

                           :else
                           [:section {:className "panel scheduler-table-panel"}
                            [:div {:className "scheduler-table-header"}
                             [:div {:className "tasks-table-status"}
                              [:span {:className "tasks-visible-count"}
                               (str (count filtered-items) " / " (count runtime-items))]
                              [:span {:className (str "poll-dot"
                                                      (when runtime-loading? " poll-dot-loading"))}]]]
                            [:div {:className "scheduler-table-wrap"}
                             [:table {:className "scheduler-table defs-table defs-runtime-table"}
                              [:colgroup
                               [:col {:className "defs-col-runtime-name"}]
                               [:col {:className "defs-col-runtime-adapter"}]
                               [:col {:className "defs-col-runtime-launch"}]
                               [:col {:className "defs-col-runtime-artifact"}]
                               [:col {:className "defs-col-runtime-task-types"}]]
                              [:thead
                               [:tr
                                [:th "Runtime profile"]
                                [:th "Adapter"]
                                [:th "Launch"]
                                [:th "Artifacts"]
                                [:th "Task types"]]]
                              (into [:tbody] table-rows)]]])]
        [components/page-shell
         {:active-route :defs
          :title "Defs"
          :subtitle "Task types, runtime profiles, and the workflow contracts bundled with this repo."
          :actions [:button {:className "button button-icon button-primary"
                             :title "Refresh"
                             :on-click defs-state/load-runtime-items!}
                    [icons/refresh]]
          :subnav [:div {:className "defs-subnav"}
                   [components/nav-tabs defs-tabs :defs-runtimes routes/navigate! "Definition sections"]]}
         [:section {:className "tasks-filter-bar defs-filter-bar"}
          [:label {:className "tasks-filter tasks-filter-query defs-filter-query"}
           [:span {:className "stat-label"} "Search"]
           [:input {:className "text-input"
                    :value @search-text
                    :placeholder "name, id, adapter, prompt path, task type"
                    :on-change #(reset! search-text (.. % -target -value))}]]]
         (when runtime-error
           [:section {:className "scheduler-inline-error"}
            [:article {:className "panel scheduler-error-card"}
             [:p {:className "scheduler-error-copy"} runtime-error]]])
         list-section
         [runtime-authoring/runtime-profile-authoring-section authoring runtime-items]]))))

(defn- task-type-list-page
  []
  (r/with-let [_ (do (defs-state/load-items!)
                     (defs-state/load-runtime-items!)
                     (defs-state/load-authoring-contract!)
                     (defs-state/load-task-type-templates!)
                     (defs-state/load-task-type-drafts!))]
    [task-type-list-page-body]))

(defn- runtime-list-page
  []
  (r/with-let [_ (do (defs-state/load-runtime-items!)
                     (defs-state/load-authoring-contract!)
                     (defs-state/load-runtime-profile-templates!)
                     (defs-state/load-runtime-profile-drafts!))]
    [runtime-list-page-body]))

(defn- task-type-detail-page
  [route]
  (r/with-let [_ (defs-state/load-detail! (:task-type-id route)
                                          (:task-type-version route))]
    [defs-detail/task-type-detail-page route (defs-state/defs-state) defs-tabs]))

(defn- runtime-detail-page
  [route]
  (r/with-let [_ (defs-state/load-runtime-detail! (:runtime-profile-id route)
                                                  (:runtime-profile-version route))]
    [defs-detail/runtime-profile-detail-page route (defs-state/defs-state) defs-tabs]))

(defn defs-page
  [route]
  (case (:page route)
    :defs-detail [task-type-detail-page route]
    :defs-runtimes [runtime-list-page]
    :defs-runtime-detail [runtime-detail-page route]
    [task-type-list-page]))
