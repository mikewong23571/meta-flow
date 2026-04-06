(ns meta-flow.lint.frontend-node-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.lint.check.frontend-node :as frontend-node]))

(deftest frontend-node-payload-summarizes-non-pass-gates
  (let [payload (frontend-node/node-payload
                 [{:label "frontend-style-governance"
                   :status :warning
                   :headline "1 frontend style finding(s) require tokenization"}
                  {:label "frontend-build"
                   :status :pass
                   :headline "frontend build check passed"}
                  {:label "frontend-semantics-governance"
                   :status :error
                   :headline "1 semantic issue requires attention"
                   :cause "button icon contains text"}])]
    (is (= :error (:node/status payload)))
    (is (= "ui governance reported blocking failures" (:node/headline payload)))
    (is (= "3 ui gate(s) evaluated" (:node/summary payload)))
    (is (= ["frontend-semantics-governance: 1 semantic issue requires attention (button icon contains text)"
            "frontend-style-governance: 1 frontend style finding(s) require tokenization"]
           (:node/evidence payload)))))
