(ns meta-flow.service.validation-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.service.validation :as service.validation]))

(def repo-arch-contract
  {:artifact-contract/required-paths ["architecture.md" "manifest.json" "run.log" "email-receipt.edn"]})

(def repo-arch-validator
  {:validator/id :validator/repo-arch
   :validator/type :validator.type/repo-arch-delivery})

(deftest repo-arch-validation-rejects-failed-email-receipts
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-repo-arch-validation"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        artifact-root (.getCanonicalPath root)]
    (doseq [[path content] {"architecture.md" "# report\n"
                            "manifest.json" "{\"status\":\"completed\"}\n"
                            "run.log" "log\n"
                            "email-receipt.edn" "{:email/status :failed :email/error \"smtp timeout\"}\n"}]
      (spit (io/file root path) content))
    (let [outcome (service.validation/assess-artifact artifact-root
                                                      repo-arch-contract
                                                      repo-arch-validator)]
      (is (= :assessment/rejected
             (:assessment/outcome outcome)))
      (is (= []
             (:assessment/missing-paths outcome)))
      (is (= :failed
             (get-in outcome [:assessment/checks :email/status])))
      (is (.contains (:assessment/notes outcome)
                     "Email receipt did not confirm delivery")))))

(deftest repo-arch-validation-accepts-sent-email-receipts
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-repo-arch-validation"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        artifact-root (.getCanonicalPath root)]
    (doseq [[path content] {"architecture.md" "# report\n"
                            "manifest.json" "{\"status\":\"completed\"}\n"
                            "run.log" "log\n"
                            "email-receipt.edn" "{:email/status :sent}\n"}]
      (spit (io/file root path) content))
    (let [outcome (service.validation/assess-artifact artifact-root
                                                      repo-arch-contract
                                                      repo-arch-validator)]
      (is (= :assessment/accepted
             (:assessment/outcome outcome)))
      (is (= :sent
             (get-in outcome [:assessment/checks :email/status]))))))

(deftest repo-arch-validation-still-reports-missing-required-paths-first
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-repo-arch-validation"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        artifact-root (.getCanonicalPath root)]
    (spit (io/file root "architecture.md") "# report\n")
    (spit (io/file root "run.log") "log\n")
    (testing "missing files are handled by the required-path check before receipt parsing"
      (let [outcome (service.validation/assess-artifact artifact-root
                                                        repo-arch-contract
                                                        repo-arch-validator)]
        (is (= :assessment/rejected
               (:assessment/outcome outcome)))
        (is (= ["manifest.json" "email-receipt.edn"]
               (:assessment/missing-paths outcome)))
        (is (.contains (:assessment/notes outcome)
                       "Missing required artifact files"))))))
