(ns meta-flow.defs-index-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.defs.index :as defs.index]))

(deftest index-definitions-builds-version-pinned-index-and-rejects-duplicates
  (let [definitions [{:task-type/id :task-type/a
                      :task-type/version 1
                      :name "A"}
                     {:task-type/id :task-type/a
                      :task-type/version 2
                      :name "A2"}]
        index (defs.index/index-definitions! "task-types"
                                             definitions
                                             :task-type/id
                                             :task-type/version)]
    (is (= "A" (:name (get index [:task-type/a 1]))))
    (is (= "A2" (:name (get index [:task-type/a 2]))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Duplicate definition version in task-types"
                          (defs.index/index-definitions! "task-types"
                                                         (conj definitions
                                                               {:task-type/id :task-type/a
                                                                :task-type/version 1})
                                                         :task-type/id
                                                         :task-type/version)))))

(deftest ref-key-and-require-ref-cover-present-and-missing-lookups
  (let [definition-ref {:definition/id :validator/required-paths
                        :definition/version 1}
        index {[:validator/required-paths 1] {:validator/id :validator/required-paths}}]
    (is (= [:validator/required-paths 1]
           (defs.index/ref-key definition-ref)))
    (is (nil? (defs.index/require-ref! "validator" definition-ref index)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing referenced definition for validator"
                          (defs.index/require-ref! "validator"
                                                   {:definition/id :validator/missing
                                                    :definition/version 1}
                                                   index)))))
