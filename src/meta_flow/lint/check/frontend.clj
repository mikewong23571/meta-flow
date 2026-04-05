(ns meta-flow.lint.check.frontend
  (:gen-class)
  (:require [meta-flow.lint.check.frontend.build :as build]
            [meta-flow.lint.check.frontend.style :as style]
            [meta-flow.lint.check.report :as report]))

(def frontend-source-roots
  ["frontend/src"])

(def frontend-style-roots
  style/frontend-style-roots)

(def build-bootstrap-status
  build/build-bootstrap-status)

(def frontend-style-gate
  style/frontend-style-gate)

(def frontend-build-gate
  build/frontend-build-gate)

(defn frontend-gates
  []
  [(frontend-style-gate)
   (frontend-build-gate)])

(defn finish-process!
  [exit-code]
  (shutdown-agents)
  (when (some? exit-code)
    (System/exit exit-code)))

(defn -main
  [& _]
  (let [gates (frontend-gates)
        status (report/overall-status gates)]
    (report/print-report! gates)
    (finish-process! (when (= :blocked status)
                       1))))
