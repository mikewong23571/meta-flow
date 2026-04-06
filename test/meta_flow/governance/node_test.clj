(ns meta-flow.governance.node-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [meta-flow.governance.node :as node]))

(deftest normalize-node-payload-produces-mounted-gate
  (let [gate (node/normalize-node-payload
              #:node{:id :ui
                     :label "mounted-ui-governance"
                     :status :warning
                     :headline "ui governance reported warnings"
                     :summary "8 ui gate(s) evaluated"
                     :evidence ["frontend-style-governance: 1 warning"]
                     :action "Run `cd ui && bb governance` for gate-by-gate detail."})]
    (is (= :warning (:status gate)))
    (is (= "mounted-ui-governance" (:label gate)))
    (is (= ["8 ui gate(s) evaluated"
            "frontend-style-governance: 1 warning"]
           (:evidence gate)))))

(deftest normalize-node-payload-rejects-invalid-status
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"unsupported status"
       (node/normalize-node-payload
        #:node{:id :ui
               :label "mounted-ui-governance"
               :status :blocked
               :headline "bad"
               :summary "summary"
               :evidence []
               :action "Run something"}))))

(deftest run-mounted-ui-node-converts-blank-stdout-into-error-gate
  (with-redefs [shell/sh (fn [& _] {:exit 1 :out "" :err "boom"})]
    (let [gate (node/run-mounted-ui-node!)]
      (is (= :error (:status gate)))
      (is (= "mounted UI node failed before emitting a payload" (:headline gate)))
      (is (re-find #"exit 1" (:cause gate))))))
