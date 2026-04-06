(ns meta-flow.ui.http.defs.catalog
  (:require [meta-flow.ui.defs :as ui.defs]))

(def ^:private task-type-detail-query-params
  [:map
   [:task-type-id :string]
   [:task-type-version :int]])

(def ^:private runtime-profile-detail-query-params
  [:map
   [:runtime-profile-id :string]
   [:runtime-profile-version :int]])

(defn- task-types-handler
  [defs-repo _]
  {:status 200
   :body {:items (ui.defs/list-task-types defs-repo)}})

(defn- task-type-create-options-handler
  [defs-repo _]
  {:status 200
   :body {:items (ui.defs/list-task-type-create-options defs-repo)}})

(defn- task-type-detail-handler
  [defs-repo {{{:keys [task-type-id task-type-version]} :query} :parameters}]
  {:status 200
   :body (ui.defs/load-task-type-detail defs-repo
                                        (keyword task-type-id)
                                        task-type-version)})

(defn- runtime-profiles-handler
  [defs-repo _]
  {:status 200
   :body {:items (ui.defs/list-runtime-profiles defs-repo)}})

(defn- runtime-profile-detail-handler
  [defs-repo {{{:keys [runtime-profile-id runtime-profile-version]} :query} :parameters}]
  {:status 200
   :body (ui.defs/load-runtime-profile-detail defs-repo
                                              (keyword runtime-profile-id)
                                              runtime-profile-version)})

(defn routes
  [defs-repo]
  [["/task-types"
    {:get {:handler (partial task-types-handler defs-repo)}}]
   ["/task-types/create-options"
    {:get {:handler (partial task-type-create-options-handler defs-repo)}}]
   ["/task-types/detail"
    {:get {:parameters {:query task-type-detail-query-params}
           :handler (partial task-type-detail-handler defs-repo)}}]
   ["/runtime-profiles"
    {:get {:handler (partial runtime-profiles-handler defs-repo)}}]
   ["/runtime-profiles/detail"
    {:get {:parameters {:query runtime-profile-detail-query-params}
           :handler (partial runtime-profile-detail-handler defs-repo)}}]])
