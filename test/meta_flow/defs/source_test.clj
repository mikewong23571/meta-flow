(ns meta-flow.defs.source-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.source :as defs.source]))

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
             (:resource-policy/id (first (:resource-policies definitions))))))))
