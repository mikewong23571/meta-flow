(ns meta-flow-ui.pages.home
  (:require [meta-flow-ui.components :as components]
            [meta-flow-ui.routes :as routes]))

(defn landing-card [title copy action-label route tone]
  [:article {:className (str "panel component-card stack landing-card landing-card-" tone)}
   [:div
    [:h2 {:className "component-title"} title]
    [:p {:className "component-copy"} copy]]
   [:div {:className "button-row"}
    [:button {:className "button button-secondary"
              :on-click #(routes/navigate! route)}
     action-label]]])

(defn home-hero []
  [:section {:className "hero"}
   [:article {:className "panel panel-strong hero-copy"}
    [:span {:className "eyebrow"} "Frontend Sandbox" "Entry"]
    [:h1 {:className "hero-title"} "Meta-Flow UI sandbox"]
    [:p {:className "hero-subtitle"}
     "This root page is intentionally not the eventual product home. It exists to keep "
     "frontend experimentation inside a clear, isolated boundary while the real product "
     "surfaces are still undefined."]
    [:div {:className "button-row hero-actions"}
     [:button {:className "button button-primary"
               :on-click #(routes/navigate! :preview)}
      "Open component preview"]]]
   [:aside {:className "hero-sidebar"}
    [components/stat-card "Purpose" "Sandbox" "A controlled place for style, layout, and component work"]
    [components/stat-card "Route" "#/preview" "The showcase page lives separately from the sandbox entry"]]])

(defn home-page []
  [:main {:className "app-shell"}
   [home-hero]
   [:section {:className "grid"}
    [:div {:style {:gridColumn "span 12"}}
     [:h2 {:className "section-title"} "Available surfaces"]
     [:p {:className "section-copy"}
      "The sandbox root stays lightweight. Dedicated preview surfaces hang off of it so "
      "they do not implicitly become the application's homepage."]]
    [:div {:className "component-grid" :style {:gridColumn "span 12"}}
     [landing-card "Component preview"
      "Open the current example interface for tokens, base controls, and Radix-backed interaction patterns."
      "Go to preview"
      :preview
      "accent"]
     [landing-card "Boundary reminder"
      "This area is for examples only. It is not connected to any business API, task list, run state, or scheduler behavior."
      "Stay on entry"
      :home
      "muted"]]
    [:div {:style {:gridColumn "span 12"}}
     [:pre {:className "code-block"} "Routes\n  #/          sandbox entry\n  #/preview  example interface and component showcase"]]]])
