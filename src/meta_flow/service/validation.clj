(ns meta-flow.service.validation
  (:require [clojure.java.io :as io]))

(defn artifact-path
  [contract-root required-path]
  (io/file contract-root required-path))

(defn assess-required-paths
  [artifact-root artifact-contract]
  (let [required-paths (:artifact-contract/required-paths artifact-contract)
        missing-paths (vec (remove #(-> artifact-root
                                        (artifact-path %)
                                        .exists)
                                   required-paths))]
    {:assessment/outcome (if (empty? missing-paths)
                           :assessment/accepted
                           :assessment/rejected)
     :assessment/missing-paths missing-paths
     :assessment/checks {:artifact-root artifact-root
                         :required-paths required-paths}
     :assessment/notes (if (empty? missing-paths)
                         "All required artifact paths were present"
                         (str "Missing required artifact files: " (pr-str missing-paths)))}))
