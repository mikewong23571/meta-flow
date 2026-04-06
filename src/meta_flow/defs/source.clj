(ns meta-flow.defs.source
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def default-resource-base "meta_flow/defs")
(def default-overlay-root "defs")

(def definition-order
  [:workflow
   :task-types
   :task-fsms
   :run-fsms
   :artifact-contracts
   :validators
   :runtime-profiles
   :resource-policies])

(def definition-files
  (zipmap definition-order
          ["workflow.edn"
           "task-types.edn"
           "task-fsms.edn"
           "run-fsms.edn"
           "artifact-contracts.edn"
           "validators.edn"
           "runtime-profiles.edn"
           "resource-policies.edn"]))

(def additive-definition-keys
  (vec (remove #{:workflow} definition-order)))

(def definition-source-key ::definition-source)

(defn definition-file
  [definition-key]
  (or (get definition-files definition-key)
      (throw (ex-info (str "Unknown definition key " definition-key)
                      {:definition-key definition-key}))))

(defn definition-source
  [definition]
  (-> definition meta definition-source-key))

(defn- read-edn-string!
  [label location content]
  (try
    (edn/read-string content)
    (catch Throwable throwable
      (throw (ex-info (str "Failed to parse EDN for " label " at " location)
                      {:label label
                       :location location}
                      throwable)))))

(defn- attach-source-metadata
  [definition-key source value]
  (cond
    (= definition-key :workflow)
    (if (map? value)
      (vary-meta value assoc definition-source-key source)
      value)

    (vector? value)
    (mapv (fn [definition]
            (if (map? definition)
              (vary-meta definition assoc definition-source-key source)
              definition))
          value)

    :else
    value))

(defn load-edn-resource!
  [resource-path]
  (if-let [resource (io/resource resource-path)]
    (read-edn-string! "resource"
                      resource-path
                      (slurp resource))
    (throw (ex-info (str "Missing resource: " resource-path)
                    {:resource-path resource-path}))))

(defn load-edn-file!
  [file-path]
  (let [file (io/file file-path)]
    (when-not (.isFile file)
      (throw (ex-info (str "Missing file: " file-path)
                      {:file-path file-path})))
    (read-edn-string! "file"
                      (.getPath file)
                      (slurp file))))

(defn- load-definition-layer
  [loader-fn path-builder source-fn]
  (into {}
        (map (fn [definition-key]
               (let [location (path-builder definition-key)
                     loaded (loader-fn location)
                     source (source-fn definition-key location)]
                 [definition-key
                  (attach-source-metadata definition-key source loaded)])))
        definition-order))

(defn- load-bundled-definition-data
  [resource-base]
  (load-definition-layer #(load-edn-resource! %)
                         #(str resource-base "/" (definition-file %))
                         (fn [definition-key resource-path]
                           {:definition/key definition-key
                            :definition/layer :bundled
                            :definition/location resource-path})))

(defn- load-overlay-definition-data
  [overlay-root]
  (into {}
        (keep (fn [definition-key]
                (let [file (io/file overlay-root (definition-file definition-key))]
                  (when (.isFile file)
                    [definition-key
                     (attach-source-metadata definition-key
                                             {:definition/key definition-key
                                              :definition/layer :overlay
                                              :definition/location (.getPath file)}
                                             (load-edn-file! (.getPath file)))]))))
        definition-order))

(defn- merge-definition-data
  [bundled overlay]
  (reduce (fn [merged definition-key]
            (let [bundled-value (get bundled definition-key)
                  overlay-value (get overlay definition-key)]
              (assoc merged
                     definition-key
                     (cond
                       (= definition-key :workflow) (or overlay-value bundled-value)
                       (nil? overlay-value) bundled-value
                       :else (into (vec bundled-value) overlay-value)))))
          {}
          definition-order))

(defn load-definition-data
  ([resource-base]
   (load-definition-data resource-base {:overlay-root default-overlay-root}))
  ([resource-base {:keys [overlay-root]
                   :or {overlay-root default-overlay-root}}]
   (let [bundled (load-bundled-definition-data resource-base)
         overlay (if overlay-root
                   (load-overlay-definition-data overlay-root)
                   {})]
     (merge-definition-data bundled overlay))))
