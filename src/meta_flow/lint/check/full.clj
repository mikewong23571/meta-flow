(ns meta-flow.lint.check.full
  (:gen-class)
  (:require [meta-flow.governance.core :as governance]
            [meta-flow.governance.node :as node]
            [meta-flow.lint.check :as check]
            [meta-flow.lint.check.report :as report]))

(def overall-status governance/overall-status)

(def print-report! report/print-report!)

(defn check-gates
  []
  (let [backend-gates* (future (check/backend-gates))
        mounted-ui-gate* (future (node/run-mounted-ui-node!))
        execution-gates* (future (check/execution-gates))]
    (into (conj @backend-gates* @mounted-ui-gate*)
          @execution-gates*)))

(defn finish-process!
  [exit-code]
  (shutdown-agents)
  (when (some? exit-code)
    (System/exit exit-code)))

(defn -main
  [& _]
  (let [gates (check-gates)]
    (print-report! gates)
    (finish-process! (governance/exit-code gates))))
