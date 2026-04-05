(ns meta-flow-ui.routes
  (:require [reagent.core :as r]))

(defonce route-state (r/atom :scheduler))
(defonce routing-installed? (atom false))

(defn parse-route []
  (case (.-hash js/location)
    "#/home" :home
    "#/preview" :preview
    "#/tasks" :tasks
    "#/scheduler" :scheduler
    :scheduler))

(defn sync-route! []
  (reset! route-state (parse-route)))

(defn navigate! [route]
  (set! (.-hash js/location)
        (case route
          :home "/home"
          :preview "/preview"
          :tasks "/tasks"
          :scheduler "/scheduler"
          "/scheduler")))

(defn ensure-routing! []
  (sync-route!)
  (when-not @routing-installed?
    (reset! routing-installed? true)
    (.addEventListener js/window "hashchange" sync-route!)))
