(ns meta-flow.sql
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [meta-flow.db :as db]))

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
  (some-> value edn/read-string))

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

(defn with-connection
  [db-path f]
  (with-open [connection (db/open-connection db-path)]
    (f connection)))

(defn with-transaction
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
