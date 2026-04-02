(ns meta-flow.sql-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.sql :as sql]))

(deftest text->edn-reads-tagged-literals-safely
  (testing "tagged literals are returned as data, not resolved via readers"
    (let [value (sql/text->edn "#foo/bar {:a 1}")]
      (is (= 'foo/bar (:tag value)))
      (is (= {:a 1} (:form value))))))
