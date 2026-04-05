(ns meta-flow-ui.app
  (:require [meta-flow-ui.pages.home :as home]
            [meta-flow-ui.pages.preview :as preview]
            [meta-flow-ui.routes :as routes]
            [meta-flow-ui.state :as state]
            [reagent.core :as r]
            ["react-dom/client" :as react-dom-client]))

(defn app []
  (case @routes/route-state
    :preview [preview/preview-page]
    [home/home-page]))

(defn mount! []
  (let [container (.getElementById js/document "app")]
    (when-not @state/react-root
      (reset! state/react-root (.createRoot react-dom-client container)))
    (.render @state/react-root (r/as-element [app]))))

(defn ^:dev/after-load reload! []
  (mount!))

(defn init []
  (routes/ensure-routing!)
  (mount!))
