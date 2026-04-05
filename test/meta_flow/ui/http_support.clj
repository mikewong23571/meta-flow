(ns meta-flow.ui.http-support
  (:require [cheshire.core :as json])
  (:import (java.io ByteArrayInputStream)
           (java.net HttpURLConnection URL)))

(def ^:private http-get-retry-count 10)

(def ^:private http-get-retry-delay-ms 50)

(defn http-get
  [port path]
  (let [url (URL. (str "http://localhost:" port path))]
    (loop [attempt 1]
      (let [connection ^HttpURLConnection (.openConnection url)
            _ (.setRequestMethod connection "GET")
            _ (.setConnectTimeout connection 1000)
            _ (.setReadTimeout connection 1000)
            result (try
                     (let [status (.getResponseCode connection)
                           stream (or (if (>= status 400)
                                        (.getErrorStream connection)
                                        (.getInputStream connection))
                                      (ByteArrayInputStream. (byte-array 0)))
                           body (with-open [in stream]
                                  (slurp in :encoding "UTF-8"))]
                       {:status status
                        :body body})
                     (catch java.io.IOException exception
                       exception))]
        (.disconnect connection)
        (if (instance? java.io.IOException result)
          (if (< attempt http-get-retry-count)
            (do
              ;; The embedded server can briefly accept-and-close during startup under coverage instrumentation.
              (Thread/sleep http-get-retry-delay-ms)
              (recur (inc attempt)))
            (throw result))
          result)))))

(defn http-post-json
  [port path payload]
  (let [url (URL. (str "http://localhost:" port path))
        connection ^HttpURLConnection (.openConnection url)
        body (json/generate-string payload)]
    (try
      (.setRequestMethod connection "POST")
      (.setDoOutput connection true)
      (.setConnectTimeout connection 1000)
      (.setReadTimeout connection 1000)
      (.setRequestProperty connection "Content-Type" "application/json")
      (.setRequestProperty connection "Accept" "application/json")
      (with-open [out (.getOutputStream connection)]
        (.write out (.getBytes body "UTF-8")))
      (let [status (.getResponseCode connection)
            stream (or (if (>= status 400)
                         (.getErrorStream connection)
                         (.getInputStream connection))
                       (ByteArrayInputStream. (byte-array 0)))
            response-body (with-open [in stream]
                            (slurp in :encoding "UTF-8"))]
        {:status status
         :body response-body})
      (finally
        (.disconnect connection)))))
