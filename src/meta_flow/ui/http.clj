(ns meta-flow.ui.http
  (:require [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.ui.http.defs :as http.defs]
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

(defn- tasks-handler
  [db-path defs-repo _]
  {:status 200
   :body {:items (ui.tasks/list-tasks defs-repo db-path 200)}})

(defn- task-detail-handler
  [db-path {{{:keys [task-id]} :path} :parameters}]
  {:status 200
   :body (ui.scheduler/load-task db-path task-id)})

(defn- run-detail-handler
  [db-path {{{:keys [run-id]} :path} :parameters}]
  {:status 200
   :body (ui.scheduler/load-run db-path run-id)})

(defn- create-task-handler
  [db-path defs-repo {{{:keys [task-type-id task-type-version input]} :body} :parameters}]
  {:status 201
   :body (ui.tasks/create-task! defs-repo
                                db-path
                                (keyword task-type-id)
                                (int (or task-type-version 1))
                                (or input {}))})

(defn- app
  [db-path defs-repo]
  (http.middleware/wrap-app
   (ring/ring-handler
    (ring/router
     [["/healthz"
       {:get {:handler healthz-handler}}]
      (into ["/api"
             ["/scheduler/overview"
              {:get {:handler (partial scheduler-overview-handler db-path)}}]]
            (concat (http.defs/routes defs-repo)
                    [["/tasks"
                      {:get {:handler (partial tasks-handler db-path defs-repo)}
                       :post {:parameters {:body create-task-body}
                              :handler (partial create-task-handler db-path defs-repo)}}]
                     ["/tasks/:task-id"
                      {:get {:parameters {:path task-id-path-params}
                             :handler (partial task-detail-handler db-path)}}]
                     ["/runs/:run-id"
                      {:get {:parameters {:path run-id-path-params}
                             :handler (partial run-detail-handler db-path)}}]]))]
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
  ([{:keys [db-path defs-repo port]
     :or {db-path db/default-db-path
          port 8788}}]
   (let [defs-repo (or defs-repo
                       (defs.loader/filesystem-definition-repository))
         server (http-kit/run-server (app db-path defs-repo)
                                     {:port (int port)
                                      :legacy-return-value? false})]
     {:server server
      :port (:port (bean server))
      :db-path db-path
      :defs-repo defs-repo})))

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
