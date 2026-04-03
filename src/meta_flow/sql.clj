(ns meta-flow.sql
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [meta-flow.db :as db]))

(def ^:private max-transaction-attempts
  5)

(def ^:private transaction-retry-sleep-ms
  25)

(defn utc-now
  []
  (.toString (java.time.Instant/now)))

(defn canonicalize-edn
  [value]
  (walk/postwalk (fn [node]
                   (if (instance? java.time.Instant node)
                     (str node)
                     node))
                 value))

(defn edn->text
  [value]
  (pr-str (canonicalize-edn value)))

(defn text->edn
  [value]
  (some-> value (#(edn/read-string {:readers {}
                                    :default tagged-literal}
                                   %))))

(defn sql-param
  [value]
  (cond
    (keyword? value) (db/keyword-text value)
    (boolean? value) (if value 1 0)
    (instance? java.time.Instant value) (str value)
    :else value))

(defn- set-params!
  [statement params]
  (doseq [[idx value] (map-indexed vector params)]
    (.setObject statement (inc idx) (sql-param value)))
  statement)

(defn execute-update!
  [connection sql params]
  (with-open [statement (.prepareStatement connection sql)]
    (set-params! statement params)
    (.executeUpdate statement)))

(defn query-rows
  [connection sql params]
  (with-open [statement (.prepareStatement connection sql)]
    (set-params! statement params)
    (with-open [result-set (.executeQuery statement)]
      (let [metadata (.getMetaData result-set)
            column-count (.getColumnCount metadata)
            columns (mapv (fn [idx]
                            (keyword (str/lower-case (.getColumnLabel metadata idx))))
                          (range 1 (inc column-count)))]
        (loop [rows []]
          (if (.next result-set)
            (recur (conj rows
                         (zipmap columns
                                 (map #(.getObject result-set %)
                                      (range 1 (inc column-count))))))
            rows))))))

(defn query-one
  [connection sql params]
  (first (query-rows connection sql params)))

(defn retryable-write-exception?
  [throwable]
  (let [message (some-> throwable .getMessage str/lower-case)]
    (boolean (and (instance? java.sql.SQLException throwable)
                  (or (and message (str/includes? message "database is locked"))
                      (and message (str/includes? message "busy")))))))

(defn with-connection
  [db-path f]
  (with-open [connection (db/open-connection db-path)]
    (f connection)))

(defn- with-transaction-once
  [db-path f]
  (with-open [connection (db/open-connection db-path)]
    (.setAutoCommit connection false)
    (try
      (let [result (f connection)]
        (.commit connection)
        result)
      (catch Throwable throwable
        (.rollback connection)
        (throw throwable)))))

(defn with-transaction
  [db-path f]
  (loop [attempt 1]
    (let [result (try
                   {:status :ok
                    :value (with-transaction-once db-path f)}
                   (catch java.sql.SQLException throwable
                     {:status :error
                      :throwable throwable}))]
      (cond
        (= :ok (:status result))
        (:value result)

        (and (< attempt max-transaction-attempts)
             (retryable-write-exception? (:throwable result)))
        (do
          (Thread/sleep transaction-retry-sleep-ms)
          (recur (inc attempt)))

        :else
        (throw (:throwable result))))))

(defn with-read-transaction
  [db-path f]
  (with-open [connection (db/open-connection db-path)]
    (.setAutoCommit connection false)
    (try
      (let [result (f connection)]
        (.rollback connection)
        result)
      (catch Throwable throwable
        (.rollback connection)
        (throw throwable)))))
