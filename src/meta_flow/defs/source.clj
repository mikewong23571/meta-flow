(ns meta-flow.defs.source
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def default-resource-base "meta_flow/defs")

(def definition-files
  {:workflow "workflow.edn"
   :task-types "task-types.edn"
   :task-fsms "task-fsms.edn"
   :run-fsms "run-fsms.edn"
   :artifact-contracts "artifact-contracts.edn"
   :validators "validators.edn"
   :runtime-profiles "runtime-profiles.edn"
   :resource-policies "resource-policies.edn"})

(defn load-edn-resource!
  [resource-path]
  (if-let [resource (io/resource resource-path)]
    (edn/read-string (slurp resource))
    (throw (ex-info (str "Missing resource: " resource-path)
                    {:resource-path resource-path}))))

(defn load-definition-data
  [resource-base]
  (into {}
        (map (fn [[definition-key filename]]
               [definition-key
                (load-edn-resource! (str resource-base "/" filename))]))
        definition-files))
