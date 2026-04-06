(ns meta-flow.ui.http.middleware
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [reitit.coercion :as coercion])
  (:import (java.util UUID)))

(def ^:private json-content-type "application/json; charset=utf-8")

(def ^:private cors-headers
  {"Access-Control-Allow-Origin" "*"
   "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type, Accept, X-Request-Id"})

(def ^:private request-coercion-error-type :reitit.coercion/request-coercion)

(def ^:private response-coercion-error-type :reitit.coercion/response-coercion)

(defn- exception-type
  [throwable]
  (or (some-> throwable ex-data :error/type)
      (some-> throwable ex-data :type)))

(defn- root-cause-message
  [throwable]
  (or (.getMessage throwable)
      (some-> throwable ex-data :error/message)
      (str throwable)))

(defn- ex-info-status
  [throwable]
  (let [message (or (.getMessage throwable) "")
        error-type (exception-type throwable)]
    (cond
      (= :request/invalid-json error-type) 400
      (= request-coercion-error-type error-type) 400
      (= response-coercion-error-type error-type) 500
      (str/starts-with? message "Task not found:") 404
      (str/starts-with? message "Run not found:") 404
      (str/starts-with? message "Task type not found:") 404
      (str/starts-with? message "Runtime profile not found:") 404
      (str/starts-with? message "Template task type not found") 404
      (str/starts-with? message "Template runtime profile not found") 404
      (str/starts-with? message "Draft task type not found") 404
      (str/starts-with? message "Draft runtime profile not found") 404
      (str/starts-with? message "Schema validation failed for") 400
      (str/starts-with? message "Unsupported task-type override keys") 400
      (str/starts-with? message "Unsupported runtime-profile override keys") 400
      (str/starts-with? message "Draft request must change id or version") 400
      (str/starts-with? message ":authoring/from-id must use keyword namespace") 400
      (str/starts-with? message ":authoring/new-id must use keyword namespace") 400
      (str/starts-with? message ":definition/id must use keyword namespace") 400
      (str/starts-with? message ":authoring/new-name must be a non-blank string") 400
      (str/starts-with? message "Required task input fields cannot be blank:") 400
      (str/starts-with? message "Work key cannot be blank") 400
      (str/starts-with? message "Cannot create draft for") 409
      (str/starts-with? message "Cannot publish") 409
      (str/starts-with? message "Draft file contents do not match") 409
      (str/starts-with? message "Task-type draft requests may only reference published runtime profiles.") 409
      (str/starts-with? message "Task type missing work-key-expr") 500
      :else 500)))

(defn- malformed-json-ex
  [throwable]
  (ex-info "Malformed JSON request body"
           {:error/type :request/invalid-json}
           throwable))

(defn- serializable-ex-data
  [throwable]
  (when-let [data (ex-data throwable)]
    (let [error-type (exception-type throwable)]
      (or (when (#{request-coercion-error-type response-coercion-error-type} error-type)
            (coercion/encode-error data))
          (try
            (json/generate-string data)
            data
            (catch Throwable _
              (cond-> {}
                error-type (assoc :error/type error-type))))))))

(defn- request-id
  [request]
  (or (get-in request [:headers "x-request-id"])
      (str (UUID/randomUUID))))

(defn- json-request?
  [request]
  (some-> (get-in request [:headers "content-type"])
          str/lower-case
          (str/starts-with? "application/json")))

(defn- json-response?
  [body]
  (or (map? body)
      (vector? body)
      (seq? body)))

(defn- log-request!
  [request response latency-ms]
  (log/info (str "ui-api request-id=" (:request-id request)
                 " method=" (-> request :request-method name str/upper-case)
                 " path=" (:uri request)
                 " status=" (:status response)
                 " latency-ms=" latency-ms)))

(defn- exception-response
  [request throwable]
  (let [status (if (instance? clojure.lang.ExceptionInfo throwable)
                 (ex-info-status throwable)
                 500)
        error-data (when (instance? clojure.lang.ExceptionInfo throwable)
                     (serializable-ex-data throwable))
        body (cond-> {:error (root-cause-message throwable)}
               error-data
               (assoc :data error-data))]
    (log/error throwable
               (str "ui-api error request-id=" (:request-id request)
                    " method=" (-> request :request-method name str/upper-case)
                    " path=" (:uri request)
                    " status=" status))
    {:status status
     :body body}))

(defn- wrap-request-id
  [handler]
  (fn [request]
    (let [request-id (request-id request)
          response (handler (assoc request :request-id request-id))]
      (update response :headers assoc "X-Request-Id" request-id))))

(defn- wrap-json-body
  [handler]
  (fn [request]
    (if (json-request? request)
      (let [body-text (slurp (:body request))]
        (handler
         (assoc request
                :body-params (if (str/blank? body-text)
                               {}
                               (try
                                 (json/parse-string body-text true)
                                 (catch Throwable throwable
                                   (throw (malformed-json-ex throwable))))))))
      (handler request))))

(defn- wrap-exception-handling
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Throwable throwable
        (exception-response request throwable)))))

(defn- wrap-json-response
  [handler]
  (fn [request]
    (let [{:keys [body] :as response} (handler request)]
      (cond-> response
        (json-response? body)
        (-> (assoc :body (json/generate-string body))
            (update :headers assoc "Content-Type" json-content-type))))))

(defn- wrap-cors
  [handler]
  (fn [request]
    (if (= :options (:request-method request))
      {:status 204
       :headers cors-headers}
      (update (handler request) :headers merge cors-headers))))

(defn- wrap-request-logging
  [handler]
  (fn [request]
    (let [started-at (System/nanoTime)
          response (handler request)
          latency-ms (/ (double (- (System/nanoTime) started-at)) 1000000.0)]
      (log-request! request response (format "%.1f" latency-ms))
      response)))

(defn wrap-app
  [handler]
  (-> handler
      wrap-json-body
      wrap-exception-handling
      wrap-json-response
      wrap-cors
      wrap-request-logging
      wrap-request-id))
