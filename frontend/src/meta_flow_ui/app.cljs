(ns meta-flow-ui.app
  (:require [meta-flow-ui.pages.defs :as defs]
            [meta-flow-ui.pages.home :as home]
            [meta-flow-ui.pages.preview :as preview]
            [meta-flow-ui.pages.scheduler :as scheduler]
            [meta-flow-ui.pages.tasks :as tasks]
            [meta-flow-ui.routes :as routes]
            [meta-flow-ui.state :as state]
            [reagent.core :as r]
            ["react-dom/client" :as react-dom-client]))

(defn app []
  (let [route @routes/route-state]
    (case (:page route)
      :home [home/home-page]
      :preview [preview/preview-page]
      :tasks [tasks/tasks-page]
      :defs [defs/defs-page route]
      :defs-detail ^{:key (str (:task-type-id route) ":" (:task-type-version route))}
      [defs/defs-page route]
      [scheduler/scheduler-page])))

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
