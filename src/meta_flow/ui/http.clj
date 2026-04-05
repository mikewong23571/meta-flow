(ns meta-flow.ui.http
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [meta-flow.db :as db]
            [meta-flow.ui.defs :as ui.defs]
            [meta-flow.ui.scheduler :as ui.scheduler]
            [meta-flow.ui.tasks :as ui.tasks])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URLDecoder)
           (java.nio.charset StandardCharsets)))

(def ^:private json-content-type "application/json; charset=utf-8")

(defn- decode-path-segment
  [value]
  (URLDecoder/decode value (str StandardCharsets/UTF_8)))

(defn- split-path
  [^HttpExchange exchange]
  (let [path (.. exchange getRequestURI getPath)]
    (->> (str/split path #"/")
         (remove str/blank?)
         (mapv decode-path-segment))))

(defn- query-params
  [^HttpExchange exchange]
  (let [raw-query (some-> exchange .getRequestURI .getRawQuery)]
    (if (str/blank? raw-query)
      {}
      (->> (str/split raw-query #"&")
           (map #(str/split % #"=" 2))
           (reduce (fn [params [k v]]
                     (assoc params
                            (keyword k)
                            (decode-path-segment (or v ""))))
                   {})))))

(defn- exchange-method
  [^HttpExchange exchange]
  (.getRequestMethod exchange))

(defn- response-bytes
  [body]
  (.getBytes (json/generate-string body) StandardCharsets/UTF_8))

(defn- add-common-headers!
  [^HttpExchange exchange]
  (doto (.getResponseHeaders exchange)
    (.add "Content-Type" json-content-type)
    (.add "Access-Control-Allow-Origin" "*")
    (.add "Access-Control-Allow-Methods" "GET, POST, OPTIONS")
    (.add "Access-Control-Allow-Headers" "Content-Type, Accept")))

(defn- send-json!
  [^HttpExchange exchange status body]
  (let [payload (response-bytes body)]
    (add-common-headers! exchange)
    (.sendResponseHeaders exchange status (long (alength payload)))
    (with-open [out (.getResponseBody exchange)]
      (.write out payload))))

(defn- send-empty!
  [^HttpExchange exchange status]
  (add-common-headers! exchange)
  (.sendResponseHeaders exchange status -1)
  (.close exchange))

(defn- method-not-allowed!
  [^HttpExchange exchange]
  (send-json! exchange 405 {:error "Method not allowed"}))

(defn- root-cause-message
  [throwable]
  (or (.getMessage throwable)
      (some-> throwable ex-data :error/message)
      (str throwable)))

(defn- read-body!
  [^HttpExchange exchange]
  (with-open [in (.getRequestBody exchange)]
    (slurp in)))

(defn- ex-info-status
  [throwable]
  (let [message (or (.getMessage throwable) "")]
    (cond
      (str/starts-with? message "Task not found:") 404
      (str/starts-with? message "Run not found:") 404
      (str/starts-with? message "Task type not found:") 404
      (str/starts-with? message "Required task input fields cannot be blank:") 400
      (str/starts-with? message "Work key cannot be blank") 400
      (str/starts-with? message "Task type missing work-key-expr") 500
      :else 500)))

(defn- handle-get
  [db-path path-parts query]
  (cond
    (= ["api" "scheduler" "overview"] path-parts)
    {:status 200
     :body (ui.scheduler/load-overview db-path)}

    (= ["api" "task-types"] path-parts)
    {:status 200
     :body {:items (ui.defs/list-task-types)}}

    (= ["api" "task-types" "create-options"] path-parts)
    {:status 200
     :body {:items (ui.defs/list-task-type-create-options)}}

    (= ["api" "task-types" "detail"] path-parts)
    {:status 200
     :body (ui.defs/load-task-type-detail (keyword (:task-type-id query))
                                          (Integer/parseInt (:task-type-version query)))}

    (= ["api" "tasks"] path-parts)
    {:status 200
     :body {:items (ui.tasks/list-tasks db-path)}}

    (and (= 3 (count path-parts))
         (= ["api" "tasks"] (subvec path-parts 0 2)))
    {:status 200
     :body (ui.scheduler/load-task db-path (nth path-parts 2))}

    (and (= 3 (count path-parts))
         (= ["api" "runs"] (subvec path-parts 0 2)))
    {:status 200
     :body (ui.scheduler/load-run db-path (nth path-parts 2))}

    :else
    {:status 404
     :body {:error "Not found"}}))

(defn- http-handler
  [db-path]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (case (exchange-method exchange)
          "OPTIONS"
          (send-empty! exchange 204)

          "GET"
          (let [{:keys [status body]} (handle-get db-path
                                                  (split-path exchange)
                                                  (query-params exchange))]
            (send-json! exchange status body))

          "POST"
          (let [body-str (read-body! exchange)
                body (json/parse-string body-str true)
                path-parts (split-path exchange)]
            (cond
              (= ["api" "tasks"] path-parts)
              (let [result (ui.tasks/create-task! db-path
                                                  (keyword (:task-type-id body))
                                                  (int (:task-type-version body 1))
                                                  (or (:input body) {}))]
                (send-json! exchange 201 result))
              :else
              (send-json! exchange 404 {:error "Not found"})))

          (method-not-allowed! exchange))
        (catch clojure.lang.ExceptionInfo throwable
          (send-json! exchange
                      (ex-info-status throwable)
                      {:error (root-cause-message throwable)
                       :data (ex-data throwable)}))
        (catch Throwable throwable
          (send-json! exchange
                      500
                      {:error (root-cause-message throwable)}))))))

(defn start-server!
  ([] (start-server! {}))
  ([{:keys [db-path port]
     :or {db-path db/default-db-path
          port 8788}}]
   (let [server (HttpServer/create (InetSocketAddress. (int port)) 0)]
     (.createContext server "/" (http-handler db-path))
     (.setExecutor server nil)
     (.start server)
     {:server server
      :port (.getPort (.getAddress server))
      :db-path db-path})))

(defn stop-server!
  [{:keys [server]}]
  (when server
    (.stop ^HttpServer server 0)
    true))

(defn block-forever!
  []
  (loop []
    (Thread/sleep 60000)
    (recur)))
