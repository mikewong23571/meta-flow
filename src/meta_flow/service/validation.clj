(ns meta-flow.service.validation
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

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

(defn- read-edn-file
  [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (edn/read {:eof nil} reader)))

(defn assess-repo-arch-delivery
  [artifact-root artifact-contract]
  (let [base-outcome (assess-required-paths artifact-root artifact-contract)]
    (if (= :assessment/rejected (:assessment/outcome base-outcome))
      base-outcome
      (let [receipt-path (artifact-path artifact-root "email-receipt.edn")
            receipt (try
                      (read-edn-file receipt-path)
                      (catch Exception ex
                        ex))
            email-status (when (map? receipt)
                           (:email/status receipt))
            email-error (when (map? receipt)
                          (:email/error receipt))]
        (cond
          (instance? Exception receipt)
          {:assessment/outcome :assessment/rejected
           :assessment/missing-paths []
           :assessment/checks {:artifact-root artifact-root
                               :required-paths (:artifact-contract/required-paths artifact-contract)
                               :receipt-path (.getCanonicalPath receipt-path)}
           :assessment/notes (str "email-receipt.edn could not be parsed: "
                                  (.getMessage ^Exception receipt))}

          (= :sent email-status)
          {:assessment/outcome :assessment/accepted
           :assessment/missing-paths []
           :assessment/checks {:artifact-root artifact-root
                               :required-paths (:artifact-contract/required-paths artifact-contract)
                               :receipt-path (.getCanonicalPath receipt-path)
                               :email/status email-status}
           :assessment/notes "All required artifact paths were present and email delivery succeeded"}

          :else
          {:assessment/outcome :assessment/rejected
           :assessment/missing-paths []
           :assessment/checks {:artifact-root artifact-root
                               :required-paths (:artifact-contract/required-paths artifact-contract)
                               :receipt-path (.getCanonicalPath receipt-path)
                               :email/status email-status
                               :email/error email-error}
           :assessment/notes (str "Email receipt did not confirm delivery: "
                                  (pr-str {:email/status email-status
                                           :email/error email-error}))})))))

(defn assess-artifact
  [artifact-root artifact-contract validator]
  (case (:validator/type validator)
    :validator.type/required-paths
    (assess-required-paths artifact-root artifact-contract)

    :validator.type/repo-arch-delivery
    (assess-repo-arch-delivery artifact-root artifact-contract)

    (throw (ex-info "Unsupported validator type"
                    {:validator/type (:validator/type validator)
                     :validator validator}))))
