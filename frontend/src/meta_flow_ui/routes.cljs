(ns meta-flow-ui.routes
  (:require [clojure.string :as str]
            [reagent.core :as r]))

(defonce route-state (r/atom {:page :scheduler}))

(defonce routing-installed? (atom false))

(defn- parse-int
  [value]
  (let [parsed (js/parseInt value 10)]
    (when-not (js/isNaN parsed)
      parsed)))

(defn parse-route []
  (let [hash-value (or (.-hash js/location) "")
        route-text (if (str/blank? hash-value) "/scheduler" (subs hash-value 1))
        [path query-string] (str/split route-text #"\?" 2)
        params (js/URLSearchParams. (or query-string ""))]
    (case path
      "/home" {:page :home}
      "/preview" {:page :preview}
      "/tasks" {:page :tasks}
      "/defs" {:page :defs}
      "/defs/detail"
      (let [task-type-id (.get params "task-type-id")
            task-type-version (parse-int (.get params "task-type-version"))]
        (if (and task-type-id task-type-version)
          {:page :defs-detail
           :task-type-id task-type-id
           :task-type-version task-type-version}
          {:page :defs}))
      "/defs/runtimes" {:page :defs-runtimes}
      "/defs/runtime-detail"
      (let [runtime-profile-id (.get params "runtime-profile-id")
            runtime-profile-version (parse-int (.get params "runtime-profile-version"))]
        (if (and runtime-profile-id runtime-profile-version)
          {:page :defs-runtime-detail
           :runtime-profile-id runtime-profile-id
           :runtime-profile-version runtime-profile-version}
          {:page :defs-runtimes}))
      "/scheduler" {:page :scheduler}
      {:page :scheduler})))

(defn sync-route! []
  (reset! route-state (parse-route)))

(defn route->hash
  [route]
  (let [{:keys [page task-type-id task-type-version runtime-profile-id runtime-profile-version]}
        (if (keyword? route) {:page route} route)]
    (case page
      :home "/home"
      :preview "/preview"
      :tasks "/tasks"
      :defs "/defs"
      :defs-detail (str "/defs/detail?task-type-id="
                        (js/encodeURIComponent task-type-id)
                        "&task-type-version="
                        task-type-version)
      :defs-runtimes "/defs/runtimes"
      :defs-runtime-detail (str "/defs/runtime-detail?runtime-profile-id="
                                (js/encodeURIComponent runtime-profile-id)
                                "&runtime-profile-version="
                                runtime-profile-version)
      :scheduler "/scheduler"
      "/scheduler")))

(defn navigate! [route]
  (set! (.-hash js/location) (route->hash route)))

(defn current-page
  []
  (:page @route-state))

(defn navigate-to-task-type!
  [task-type-id task-type-version]
  (navigate! {:page :defs-detail
              :task-type-id task-type-id
              :task-type-version task-type-version}))

(defn navigate-to-runtime-profile!
  [runtime-profile-id runtime-profile-version]
  (navigate! {:page :defs-runtime-detail
              :runtime-profile-id runtime-profile-id
              :runtime-profile-version runtime-profile-version}))

(defn ensure-routing! []
  (sync-route!)
  (when-not @routing-installed?
    (reset! routing-installed? true)
    (.addEventListener js/window "hashchange" sync-route!)))
