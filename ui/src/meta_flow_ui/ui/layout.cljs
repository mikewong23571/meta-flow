(ns meta-flow-ui.ui.layout
  (:require [meta-flow-ui.routes :as routes]))

(def primary-tabs
  [{:label "Scheduler" :route :scheduler}
   {:label "Tasks" :route :tasks}
   {:label "Defs" :route :defs}
   {:label "Preview" :route :preview}])

(defn nav-tabs
  ([items active-route on-navigate]
   (nav-tabs items active-route on-navigate "Primary navigation"))
  ([items active-route on-navigate aria-label]
   [:nav {:className "nav-tabs" :aria-label aria-label}
    (for [{:keys [label route]} items]
      ^{:key (name route)}
      [:button {:className (str "nav-tab"
                                (when (= active-route route)
                                  " nav-tab-active"))
                :aria-current (when (= active-route route) "page")
                :on-click #(on-navigate route)}
       label])]))

(defn page-shell
  [{:keys [active-route title subtitle before-title actions subnav]} & body]
  (into
   [:main {:className "app-shell"}
    [:header {:className "app-chrome"}
     [:div {:className "app-masthead"}
      [nav-tabs primary-tabs active-route routes/navigate! "Primary navigation"]]
     (when (or title actions)
       (into
        [:section {:className (str "page-header"
                                   (when-not actions " page-header-no-actions")
                                   (when-not title " page-header-no-title"))}
         (when title
           [:div {:className "page-heading"}
            (when before-title
              [:div {:className "page-heading-prefix"} before-title])
            [:h1 {:className "page-title"} title]
            (when subtitle
              [:p {:className "page-subtitle"} subtitle])])]
        (when actions
          [[:div {:className "page-header-actions"} actions]])))
     (when subnav
       [:section {:className "page-subnav"} subnav])]]
   body))
