(ns meta-flow.lint.check.frontend
  (:gen-class)
  (:require [meta-flow.lint.check.frontend.architecture :as architecture]
            [meta-flow.lint.check.frontend.build :as build]
            [meta-flow.lint.check.frontend.page-roles :as page-roles]
            [meta-flow.lint.check.frontend.shared-ui :as shared-ui]
            [meta-flow.lint.check.frontend.style :as style]
            [meta-flow.lint.check.report :as report]))

(def frontend-source-roots
  ["frontend/src"])

(def frontend-style-roots
  style/frontend-style-roots)

(def build-bootstrap-status
  build/build-bootstrap-status)

(def frontend-architecture-gate
  architecture/frontend-architecture-gate)

(def frontend-shared-component-placement-gate
  shared-ui/frontend-shared-component-placement-gate)

(def frontend-shared-component-facade-gate
  shared-ui/frontend-shared-component-facade-gate)

(def frontend-ui-layering-gate
  shared-ui/frontend-ui-layering-gate)

(def frontend-page-role-gate
  page-roles/frontend-page-role-gate)

(def frontend-style-gate
  style/frontend-style-gate)

(def frontend-build-gate
  build/frontend-build-gate)

(defn frontend-gates
  []
  [(frontend-architecture-gate)
   (frontend-shared-component-placement-gate)
   (frontend-shared-component-facade-gate)
   (frontend-ui-layering-gate)
   (frontend-page-role-gate)
   (frontend-style-gate)
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
