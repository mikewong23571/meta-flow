(ns meta-flow-ui.pages.home
  (:require [meta-flow-ui.components :as components]
            [meta-flow-ui.icons :as icons]
            [meta-flow-ui.routes :as routes]))

(defn nav-card [title route tone]
  [:article {:className (str "panel component-card landing-card landing-card-" tone)
             :role "button"
             :tabIndex 0
             :on-click #(routes/navigate! route)
             :on-key-down #(when (= (.-key %) "Enter") (routes/navigate! route))}
   [:div {:className "nav-card-inner"}
    [:h2 {:className "component-title"} title]
    [:span {:className "nav-card-arrow"} [icons/arrow-right]]]])

(defn home-page []
  [:main {:className "app-shell"}
   [:section {:className "hero"}
    [:article {:className "panel panel-strong hero-copy"}
     [:h1 {:className "hero-title"} "Meta-Flow"]
     [:div {:className "button-row hero-actions"}
      [:button {:className "button button-primary"
                :on-click #(routes/navigate! :scheduler)}
       [icons/arrow-right] "Scheduler"]
      [:button {:className "button button-secondary"
                :on-click #(routes/navigate! :preview)}
       "Preview"]]]
    [:aside {:className "hero-sidebar"}
     [components/stat-card "Scheduler" "#/scheduler" nil]
     [components/stat-card "Tasks" "#/tasks" nil]]]
   [:div {:className "component-grid"}
    [nav-card "Scheduler" :scheduler "accent"]
    [nav-card "Tasks" :tasks "muted"]
    [nav-card "Preview" :preview "muted"]]])
