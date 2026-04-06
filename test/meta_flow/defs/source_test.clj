(ns meta-flow.defs.source-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.source :as defs.source]))

(defn- temp-overlay-root
  []
  (.getPath (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-defs-overlay"
                                                              (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest load-edn-resource-covers-bundled-and-missing-resources
  (let [workflow (defs.source/load-edn-resource! "meta_flow/defs/workflow.edn")]
    (is (= :workflow/meta-flow (:workflow/id workflow)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing resource: meta_flow/defs/missing.edn"
                          (defs.source/load-edn-resource! "meta_flow/defs/missing.edn")))))

(deftest load-definition-data-reads-the-whole-definition-bundle
  (let [definitions (defs.source/load-definition-data defs.source/default-resource-base)]
    (testing "all expected definition files are loaded"
      (is (= (set (keys defs.source/definition-files))
             (set (keys definitions)))))
    (testing "representative records are available from the parsed bundle"
      (is (= :task-type/default
             (:task-type/id (first (:task-types definitions)))))
      (is (= :run-fsm/default
             (:run-fsm/id (first (:run-fsms definitions)))))
      (is (= :resource-policy/default
             (:resource-policy/id (first (:resource-policies definitions)))))
      (is (= 1800
             (:resource-policy/lease-duration-seconds
              (first (:resource-policies definitions))))))))

(deftest load-definition-data-merges-top-level-overlay-files-only
  (let [overlay-root (temp-overlay-root)
        active-path (io/file overlay-root "runtime-profiles.edn")
        draft-path (io/file overlay-root "drafts" "runtime-profiles.edn")]
    (.mkdirs (.getParentFile draft-path))
    (spit active-path "[{:runtime-profile/id :runtime-profile/overlay-worker\n  :runtime-profile/version 1\n  :runtime-profile/name \"Overlay worker\"\n  :runtime-profile/adapter-id :runtime.adapter/mock\n  :runtime-profile/dispatch-mode :dispatch.mode/inline}]\n")
    (spit draft-path "[{:runtime-profile/id :runtime-profile/draft-only\n  :runtime-profile/version 1\n  :runtime-profile/name \"Draft only\"\n  :runtime-profile/adapter-id :runtime.adapter/mock\n  :runtime-profile/dispatch-mode :dispatch.mode/inline}]\n")
    (let [definitions (defs.source/load-definition-data defs.source/default-resource-base
                                                        {:overlay-root overlay-root})
          runtime-profile-ids (set (map :runtime-profile/id (:runtime-profiles definitions)))
          overlay-profile (some #(when (= :runtime-profile/overlay-worker
                                          (:runtime-profile/id %))
                                   %)
                                (:runtime-profiles definitions))]
      (is (contains? runtime-profile-ids :runtime-profile/overlay-worker))
      (is (not (contains? runtime-profile-ids :runtime-profile/draft-only)))
      (is (= {:definition/key :runtime-profiles
              :definition/layer :overlay
              :definition/location (.getPath active-path)}
             (defs.source/definition-source overlay-profile))))))

(deftest load-definition-data-surfaces-overlay-parse-errors-with-file-location
  (let [overlay-root (temp-overlay-root)
        active-path (io/file overlay-root "runtime-profiles.edn")]
    (.mkdirs (.getParentFile active-path))
    (spit active-path "[{:runtime-profile/id :runtime-profile/bad}")
    (try
      (defs.source/load-definition-data defs.source/default-resource-base
                                        {:overlay-root overlay-root})
      (is false "expected overlay parse failure")
      (catch clojure.lang.ExceptionInfo ex
        (is (re-find #"Failed to parse EDN for file at"
                     (.getMessage ex)))
        (is (= "file"
               (:label (ex-data ex))))
        (is (= (.getPath active-path)
               (:location (ex-data ex))))))))
