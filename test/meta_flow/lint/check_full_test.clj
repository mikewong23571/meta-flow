(ns meta-flow.lint.check-full-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.governance.node :as node]
            [meta-flow.lint.check :as check]
            [meta-flow.lint.check.full :as full]))

(deftest check-gates-mount-a-single-ui-node
  (with-redefs [check/backend-gates (fn [] [{:label "format-hygiene" :status :pass}
                                            {:label "static-analysis" :status :pass}])
                node/run-mounted-ui-node! (fn [] {:label "mounted-ui-governance" :status :warning})
                check/execution-gates (fn [] [{:label "coverage-governance" :status :pass}])]
    (is (= ["format-hygiene"
            "static-analysis"
            "mounted-ui-governance"
            "coverage-governance"]
           (mapv :label (full/check-gates))))))

(deftest mounted-ui-node-failures-stay-recoverable
  (with-redefs [check/backend-gates (fn [] [{:label "format-hygiene" :status :pass}])
                node/run-mounted-ui-node! (fn [] {:label "mounted-ui-governance"
                                                  :status :error
                                                  :headline "mounted UI node failed before emitting a payload"})
                check/execution-gates (fn [] [])]
    (let [gates (full/check-gates)]
      (is (= ["format-hygiene" "mounted-ui-governance"]
             (mapv :label gates)))
      (is (= :error (:status (last gates)))))))
