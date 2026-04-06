(ns meta-flow.governance.runner-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.governance.core :as core]
            [meta-flow.governance.runner :as runner]))

(deftest overall-status-blocks-on-errors-and-warns-on-warnings
  (is (= :blocked
         (core/overall-status [{:status :pass}
                               {:status :warning}
                               {:status :error}])))
  (is (= :warning
         (core/overall-status [{:status :pass}
                               {:status :warning}])))
  (is (= :pass
         (core/overall-status [{:status :pass}
                               {:status :skipped}]))))

(deftest runner-normalizes-single-and-multi-gate-results
  (let [gates (runner/run-entries!
               [{:id :single
                 :label "single"
                 :run (fn []
                        {:status :pass
                         :headline "single gate passed"
                         :action "noop"})}
                {:id :multi
                 :label "multi"
                 :run (fn []
                        [{:gate :one
                          :status :warning
                          :headline "warning"
                          :action "inspect"}
                         {:gate :two
                          :status :pass
                          :headline "pass"
                          :action "noop"}])}])]
    (is (= [:single :one :two]
           (mapv :gate gates)))
    (is (= ["single" "multi" "multi"]
           (mapv :label gates)))))

(deftest runner-converts-entry-crashes-into-error-gates
  (let [[gate] (runner/run-entries!
                [{:id :boom
                  :label "boom"
                  :run (fn []
                         (throw (ex-info "explode" {})))}])]
    (is (= :error (:status gate)))
    (is (= :boom (:gate gate)))
    (is (= "explode" (:cause gate)))
    (is (= "boom" (:label gate)))))

(deftest runner-recovers-when-entry-produces-an-invalid-gate-shape
  (let [[gate] (runner/run-entries!
                [{:id :broken
                  :label "broken"
                  :run (fn []
                         {:headline "missing status"
                          :action "noop"})}])]
    (is (= :error (:status gate)))
    (is (= :broken (:gate gate)))
    (is (= "broken" (:label gate)))
    (is (= "governance entry `broken` failed before producing a result"
           (:headline gate)))))
