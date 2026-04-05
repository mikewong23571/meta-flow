(ns meta-flow-ui.http
  (:require [clojure.string :as str]))

(defn- dev-api-origin
  []
  (str (.-protocol js/location) "//" (.-hostname js/location) ":8788"))

(defn api-base-url
  []
  (let [configured (some-> (.-META_FLOW_API_BASE_URL js/window) str/trim not-empty)
        current-origin (.-origin js/location)]
    (cond
      configured configured
      (= "8787" (.-port js/location)) (dev-api-origin)
      :else current-origin)))

(defn api-url
  [path]
  (str (api-base-url) path))

(defn- parse-json
  [text]
  (when-let [json-text (some-> text str/trim not-empty)]
    (js->clj (.parse js/JSON json-text) :keywordize-keys true)))

(defn post-json
  [path body]
  (-> (js/fetch (api-url path)
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"
                                   "Accept" "application/json"}
                     :body (.stringify js/JSON (clj->js body))})
      (.then (fn [response]
               (-> (.text response)
                   (.then (fn [text]
                            (let [payload (or (parse-json text) {})]
                              (if (.-ok response)
                                payload
                                (throw (ex-info (or (:error payload)
                                                    (str "Request failed with status " (.-status response)))
                                                {:status (.-status response)
                                                 :payload payload})))))))))))

(defn fetch-json
  [path]
  (-> (js/fetch (api-url path)
                #js {:headers #js {"Accept" "application/json"}})
      (.then (fn [response]
               (-> (.text response)
                   (.then (fn [text]
                            (let [payload (or (parse-json text) {})]
                              (if (.-ok response)
                                payload
                                (throw (ex-info (or (:error payload)
                                                    (str "Request failed with status " (.-status response)))
                                                {:status (.-status response)
                                                 :payload payload})))))))))))
