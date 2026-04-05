(ns meta-flow-ui.routes
  (:require [reagent.core :as r]))

(defonce route-state (r/atom :home))
(defonce routing-installed? (atom false))

(defn parse-route []
  (case (.-hash js/location)
    "#/preview" :preview
    :home))

(defn sync-route! []
  (reset! route-state (parse-route)))

(defn navigate! [route]
  (set! (.-hash js/location)
        (case route
          :preview "/preview"
          "/")))

(defn ensure-routing! []
  (sync-route!)
  (when-not @routing-installed?
    (reset! routing-installed? true)
    (.addEventListener js/window "hashchange" sync-route!)))
