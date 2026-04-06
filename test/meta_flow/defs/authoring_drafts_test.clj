(ns meta-flow.defs.authoring-drafts-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.authoring.drafts :as authoring.drafts]))

(defn- temp-overlay-root
  []
  (.getPath
   (.toFile
    (java.nio.file.Files/createTempDirectory "meta-flow-authoring-drafts"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn- write-draft!
  [overlay-root relative-path content]
  (let [file (io/file overlay-root relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    (.getPath file)))

(deftest list-definition-drafts-returns-sorted-summaries
  (let [overlay-root (temp-overlay-root)
        alpha-path (write-draft! overlay-root
                                 "drafts/task-types/task-type_alpha_v1.edn"
                                 "{:task-type/id :task-type/alpha\n :task-type/version 1\n :task-type/name \"Alpha\"}\n")
        beta-path (write-draft! overlay-root
                                "drafts/task-types/task-type_beta_v2.edn"
                                "{:task-type/id :task-type/beta\n :task-type/version 2\n :task-type/name \"Beta\"}\n")]
    (write-draft! overlay-root "drafts/task-types/ignored.txt" "not edn")
    (let [result (authoring.drafts/list-definition-drafts overlay-root :task-type)]
      (is (= :task-type (:definition-kind result)))
      (is (= "task type" (:definition-kind/label result)))
      (is (= [{:definition-kind :task-type
               :definition/id :task-type/alpha
               :definition/version 1
               :definition/name "Alpha"
               :draft-path alpha-path}
              {:definition-kind :task-type
               :definition/id :task-type/beta
               :definition/version 2
               :definition/name "Beta"
               :draft-path beta-path}]
             (:items result))))))

(deftest load-definition-draft-returns-draft-contents-and-path
  (let [overlay-root (temp-overlay-root)
        draft-path (write-draft! overlay-root
                                 "drafts/runtime-profiles/runtime-profile_repo-review_v1.edn"
                                 "{:runtime-profile/id :runtime-profile/repo-review\n :runtime-profile/version 1\n :runtime-profile/name \"Repo review\"}\n")
        result (authoring.drafts/load-definition-draft overlay-root
                                                       :runtime-profile
                                                       {:definition/id :runtime-profile/repo-review
                                                        :definition/version 1})]
    (is (= :runtime-profile (:definition-kind result)))
    (is (= draft-path (:draft-path result)))
    (is (= {:runtime-profile/id :runtime-profile/repo-review
            :runtime-profile/version 1
            :runtime-profile/name "Repo review"}
           (:definition result)))))

(deftest load-definition-draft-rejects-missing-namespaced-and-mismatched-drafts
  (let [overlay-root (temp-overlay-root)]
    (testing "definition refs must use the expected keyword namespace"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":definition/id must use keyword namespace runtime-profile"
                            (authoring.drafts/load-definition-draft overlay-root
                                                                    :runtime-profile
                                                                    {:definition/id :task-type/wrong
                                                                     :definition/version 1}))))
    (testing "missing drafts report the resolved path"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Draft runtime profile not found at .*runtime-profile_missing_v1\.edn"
                            (authoring.drafts/load-definition-draft overlay-root
                                                                    :runtime-profile
                                                                    {:definition/id :runtime-profile/missing
                                                                     :definition/version 1}))))
    (testing "draft contents must match the requested id and version"
      (write-draft! overlay-root
                    "drafts/runtime-profiles/runtime-profile_repo-review_v1.edn"
                    "{:runtime-profile/id :runtime-profile/repo-review\n :runtime-profile/version 2\n :runtime-profile/name \"Wrong version\"}\n")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Draft file contents do not match the requested definition id/version"
                            (authoring.drafts/load-definition-draft overlay-root
                                                                    :runtime-profile
                                                                    {:definition/id :runtime-profile/repo-review
                                                                     :definition/version 1}))))))
