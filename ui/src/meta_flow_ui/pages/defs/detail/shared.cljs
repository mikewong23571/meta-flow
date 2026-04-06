(ns meta-flow-ui.pages.defs.detail.shared
  (:require [meta-flow-ui.components :as components]
            [meta-flow-ui.routes :as routes]))

(defn section
  [title body]
  [:section {:className "panel defs-detail-section"}
   [:div {:className "defs-detail-section-head"}
    [:h2 {:className "component-title"} title]]
   [:div {:className "defs-detail-section-body"}
    body]])

(defn chips
  [values item-class render-fn]
  [:div {:className "defs-inline-list"}
   (for [value values]
     ^{:key (str value)}
     [:span {:className item-class}
      (render-fn value)])])

(defn task-type-links
  [task-types]
  (if (seq task-types)
    [:div {:className "defs-linked-list"}
     (for [task-type task-types]
       ^{:key (str (:task-type/id task-type) ":" (:task-type/version task-type))}
       [:button {:className "button button-ghost defs-link-chip"
                 :on-click #(routes/navigate-to-task-type! (:task-type/id task-type)
                                                           (:task-type/version task-type))}
        (or (:task-type/name task-type)
            (str (:task-type/id task-type) " v" (:task-type/version task-type)))])]
    [:p {:className "scheduler-empty"} "No task types reference this runtime profile."]))

(defn detail-topbar
  [{:keys [title subtitle back-label back-route refresh! defs-tabs active-defs-route]} & body]
  (into
   [components/page-shell
    {:active-route :defs}
    [:section {:className "defs-detail-header"}
     [:div {:className "defs-detail-header-main"}
      [:button {:className "button button-ghost defs-detail-back-link"
                :on-click #(routes/navigate! back-route)}
       back-label]
      [:div {:className "defs-detail-heading"}
       [:h1 {:className "defs-detail-title"} title]
       (when subtitle
         [:p {:className "defs-detail-subtitle"} subtitle])]]
     [:div {:className "defs-detail-toolbar"}
      [:button {:className "button button-primary"
                :on-click refresh!}
       "Refresh"]]]
    [:section {:className "page-subnav"}
     [:div {:className "defs-subnav"}
      [components/nav-tabs defs-tabs active-defs-route routes/navigate! "Definition sections"]]]]
   body))

(defn detail-columns
  [main side]
  [:section {:className "defs-detail-layout"}
   [:div {:className "defs-detail-main-column"}
    main]
   [:aside {:className "defs-detail-side-column"}
    side]])
