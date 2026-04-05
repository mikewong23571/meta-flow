(ns meta-flow-ui.pages.defs.list
  (:require [meta-flow-ui.pages.defs.presenter :as presenter]
            [meta-flow-ui.routes :as routes]))

(defn task-type-row
  [item]
  [:tr {:className "scheduler-table-row defs-table-row"
        :key (str (:task-type/id item) ":" (:task-type/version item))
        :on-click #(routes/navigate-to-task-type! (:task-type/id item)
                                                  (:task-type/version item))
        :style {:cursor "pointer"}}
   [:td
    [:div {:className "tasks-summary-primary"
           :title (:task-type/name item)}
     (:task-type/name item)]
    [:div {:className "table-subtext table-subtext-mono"}
     (str (:task-type/id item) " v" (:task-type/version item))]]
   [:td
    [:div {:className "tasks-summary-primary"
           :title (presenter/input-preview item)}
     (presenter/input-preview item)]
    [:div {:className "table-subtext"}
     (str (:task-type/input-count item)
          " field"
          (when (not= 1 (:task-type/input-count item)) "s"))]]
   [:td
    [:div {:className "tasks-summary-primary"}
     (presenter/work-key-label (:task-type/work-key item))]
    [:div {:className "table-subtext table-subtext-mono"}
     (case (:work-key/type (:task-type/work-key item))
       "work-key.type/direct" (some-> item :task-type/work-key :work-key/field presenter/seg)
       "work-key.type/tuple" (str (some-> item :task-type/work-key :work-key/tag presenter/seg)
                                  " · "
                                  (count (get-in item [:task-type/work-key :work-key/fields]))
                                  " fields")
       "n/a")]]
   [:td
    [:div {:className "tasks-summary-primary"}
     (some-> item :task-type/runtime-profile :runtime-profile/adapter-id presenter/seg)]
    [:div {:className "table-subtext table-subtext-mono"}
     (some-> item :task-type/runtime-profile :runtime-profile/dispatch-mode presenter/seg)]]
   [:td
    [:div {:className "tasks-summary-primary"}
     (some-> item :task-type/resource-policy :definition/name)]
    [:div {:className "table-subtext table-subtext-mono"}
     (str (or (some-> item :task-type/resource-policy :resource-policy/max-active-runs) "n/a")
          " active / "
          (or (some-> item :task-type/resource-policy :resource-policy/max-attempts) "n/a")
          " attempts")]]])
