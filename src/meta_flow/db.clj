(ns meta-flow.db
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-db-path "var/meta-flow.sqlite3")

(def default-sql-resource-base "meta_flow/sql")

(def runtime-directories
  ["var/artifacts" "var/runs" "var/codex-home"])

(def sqlite-pragmas
  {:journal_mode "WAL"
   :foreign_keys "ON"
   :busy_timeout 5000})

(def schema-migrations-table-sql
  (str "CREATE TABLE IF NOT EXISTS schema_migrations ("
       "migration_id TEXT PRIMARY KEY, "
       "description TEXT NOT NULL, "
       "applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)"))

(declare apply-pragmas!)

(defn ensure-runtime-directories!
  []
  (doseq [directory runtime-directories]
    (.mkdirs (io/file directory)))
  runtime-directories)

(defn- jdbc-url
  [db-path]
  (str "jdbc:sqlite:" (.getPath (io/file db-path))))

(defn- format-pragma-value
  [value]
  (if (string? value)
    value
    (str value)))

(defn- execute-sql!
  [connection sql]
  (with-open [statement (.createStatement connection)]
    (.execute statement sql)))

(defn open-connection
  ([] (open-connection default-db-path))
  ([db-path]
   (Class/forName "org.sqlite.JDBC")
   (let [connection (java.sql.DriverManager/getConnection (jdbc-url db-path))]
     (try
       (apply-pragmas! connection)
       connection
       (catch Throwable throwable
         (.close connection)
         (throw throwable))))))

(defn keyword-text
  [value]
  (cond
    (keyword? value) (str value)
    (nil? value) nil
    :else (str value)))

(defn- resource-directory!
  [resource-base]
  (if-let [resource (io/resource resource-base)]
    (io/file resource)
    (throw (ex-info (str "Missing SQL resource directory " resource-base)
                    {:resource-base resource-base}))))

(defn- migration-resource-paths
  []
  (->> (.listFiles (resource-directory! default-sql-resource-base))
       (filter #(.isFile %))
       (map #(.getName %))
       (filter #(str/ends-with? % ".sql"))
       sort
       (map #(str default-sql-resource-base "/" %))))

(defn- migration-id
  [resource-path]
  (subs (last (str/split resource-path #"/")) 0 (- (count (last (str/split resource-path #"/"))) 4)))

(defn- migration-statements!
  [resource-path]
  (let [resource (or (io/resource resource-path)
                     (throw (ex-info (str "Missing SQL resource " resource-path)
                                     {:resource-path resource-path})))]
    (->> (str/split (slurp resource) #";\s*(?:\r?\n|$)")
         (map str/trim)
         (remove str/blank?))))

(defn- apply-pragmas!
  [connection]
  (doseq [[pragma value] sqlite-pragmas]
    (execute-sql! connection (str "PRAGMA " (name pragma) "=" (format-pragma-value value)))))

(defn- query-single-column
  [connection sql]
  (with-open [statement (.createStatement connection)
              result-set (.executeQuery statement sql)]
    (loop [values []]
      (if (.next result-set)
        (recur (conj values (.getString result-set 1)))
        values))))

(defn- ensure-migrations-table!
  [connection]
  (execute-sql! connection schema-migrations-table-sql))

(defn- apply-pending-migrations!
  [connection]
  (ensure-migrations-table! connection)
  (let [applied-migration-ids (set (query-single-column connection "SELECT migration_id FROM schema_migrations"))]
    (doseq [resource-path (migration-resource-paths)
            :when (not (contains? applied-migration-ids (migration-id resource-path)))]
      (doseq [statement (migration-statements! resource-path)]
        (execute-sql! connection statement)))))

(defn initialize-database!
  ([] (initialize-database! default-db-path))
  ([db-path]
   (when-let [parent (.getParentFile (io/file db-path))]
     (.mkdirs parent))
   (with-open [connection (open-connection db-path)]
     (.setAutoCommit connection false)
     (try
       (apply-pending-migrations! connection)
       (.commit connection)
       (catch Throwable throwable
         (.rollback connection)
         (throw throwable))))
   {:db-path db-path
    :pragmas sqlite-pragmas}))
