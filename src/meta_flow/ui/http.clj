(ns meta-flow.ui.http
  (:require [meta-flow.db :as db]
            [meta-flow.ui.defs :as ui.defs]
            [meta-flow.ui.http.middleware :as http.middleware]
            [meta-flow.ui.scheduler :as ui.scheduler]
            [meta-flow.ui.tasks :as ui.tasks]
            [org.httpkit.server :as http-kit]
            [reitit.coercion.malli :as coercion.malli]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as ring.coercion]
            [reitit.ring.middleware.parameters :as parameters]))

(def ^:private task-id-path-params
  [:map [:task-id :string]])

(def ^:private run-id-path-params
  [:map [:run-id :string]])

(def ^:private task-type-detail-query-params
  [:map
   [:task-type-id :string]
   [:task-type-version :int]])

(def ^:private runtime-profile-detail-query-params
  [:map
   [:runtime-profile-id :string]
   [:runtime-profile-version :int]])

(def ^:private create-task-body
  [:map
   [:task-type-id :string]
   [:task-type-version {:optional true} :int]
   [:input {:optional true} [:map-of keyword? any?]]])

(defn- healthz-handler
  [_]
  {:status 200
   :body {:ok true}})

(defn- scheduler-overview-handler
  [db-path _]
  {:status 200
   :body (ui.scheduler/load-overview db-path)})

(defn- task-types-handler
  [_]
  {:status 200
   :body {:items (ui.defs/list-task-types)}})

(defn- task-type-create-options-handler
  [_]
  {:status 200
   :body {:items (ui.defs/list-task-type-create-options)}})

(defn- task-type-detail-handler
  [{{{:keys [task-type-id task-type-version]} :query} :parameters}]
  {:status 200
   :body (ui.defs/load-task-type-detail (keyword task-type-id)
                                        task-type-version)})

(defn- runtime-profiles-handler
  [_]
  {:status 200
   :body {:items (ui.defs/list-runtime-profiles)}})

(defn- runtime-profile-detail-handler
  [{{{:keys [runtime-profile-id runtime-profile-version]} :query} :parameters}]
  {:status 200
   :body (ui.defs/load-runtime-profile-detail (keyword runtime-profile-id)
                                              runtime-profile-version)})

(defn- tasks-handler
  [db-path _]
  {:status 200
   :body {:items (ui.tasks/list-tasks db-path)}})

(defn- task-detail-handler
  [db-path {{{:keys [task-id]} :path} :parameters}]
  {:status 200
   :body (ui.scheduler/load-task db-path task-id)})

(defn- run-detail-handler
  [db-path {{{:keys [run-id]} :path} :parameters}]
  {:status 200
   :body (ui.scheduler/load-run db-path run-id)})

(defn- create-task-handler
  [db-path {{{:keys [task-type-id task-type-version input]} :body} :parameters}]
  {:status 201
   :body (ui.tasks/create-task! db-path
                                (keyword task-type-id)
                                (int (or task-type-version 1))
                                (or input {}))})

(defn- app
  [db-path]
  (http.middleware/wrap-app
   (ring/ring-handler
    (ring/router
     [["/healthz"
       {:get {:handler healthz-handler}}]
      ["/api"
       ["/scheduler/overview"
        {:get {:handler (partial scheduler-overview-handler db-path)}}]
       ["/task-types"
        {:get {:handler task-types-handler}}]
       ["/task-types/create-options"
        {:get {:handler task-type-create-options-handler}}]
       ["/task-types/detail"
        {:get {:parameters {:query task-type-detail-query-params}
               :handler task-type-detail-handler}}]
       ["/runtime-profiles"
        {:get {:handler runtime-profiles-handler}}]
       ["/runtime-profiles/detail"
        {:get {:parameters {:query runtime-profile-detail-query-params}
               :handler runtime-profile-detail-handler}}]
       ["/tasks"
        {:get {:handler (partial tasks-handler db-path)}
         :post {:parameters {:body create-task-body}
                :handler (partial create-task-handler db-path)}}]
       ["/tasks/:task-id"
        {:get {:parameters {:path task-id-path-params}
               :handler (partial task-detail-handler db-path)}}]
       ["/runs/:run-id"
        {:get {:parameters {:path run-id-path-params}
               :handler (partial run-detail-handler db-path)}}]]]
     {:data {:coercion coercion.malli/coercion
             :middleware [parameters/parameters-middleware
                          ring.coercion/coerce-request-middleware]}})
    (ring/create-default-handler
     {:not-found (fn [_] {:status 404
                          :body {:error "Not found"}})
      :method-not-allowed (fn [_] {:status 405
                                   :body {:error "Method not allowed"}})}))))

(defn start-server!
  ([] (start-server! {}))
  ([{:keys [db-path port]
     :or {db-path db/default-db-path
          port 8788}}]
   (let [server (http-kit/run-server (app db-path)
                                     {:port (int port)
                                      :legacy-return-value? false})]
     {:server server
      :port (:port (bean server))
      :db-path db-path})))

(defn stop-server!
  [{:keys [server]}]
  (when server
    (http-kit/server-stop! server)
    true))

(defn block-forever!
  []
  (loop []
    (Thread/sleep 60000)
    (recur)))
