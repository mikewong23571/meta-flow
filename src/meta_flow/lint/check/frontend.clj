(ns meta-flow.lint.check.frontend
  (:gen-class)
  (:require [meta-flow.lint.check.frontend.architecture :as architecture]
            [meta-flow.lint.check.frontend.build :as build]
            [meta-flow.lint.check.frontend.page-roles :as page-roles]
            [meta-flow.lint.check.frontend.semantics :as semantics]
            [meta-flow.lint.check.frontend.shared-ui :as shared-ui]
            [meta-flow.lint.check.frontend.style :as style]
            [meta-flow.governance.core :as governance]
            [meta-flow.governance.runner :as runner]
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

(def frontend-semantics-gate
  semantics/frontend-semantics-gate)

(def frontend-style-gate
  style/frontend-style-gate)

(def frontend-build-gate
  build/frontend-build-gate)

(defn frontend-gate-entries
  []
  [{:id :frontend-architecture
    :label "frontend-architecture"
    :run frontend-architecture-gate}
   {:id :frontend-shared-component-placement
    :label "frontend-shared-component-placement"
    :run frontend-shared-component-placement-gate}
   {:id :frontend-shared-component-facade
    :label "frontend-shared-component-facade"
    :run frontend-shared-component-facade-gate}
   {:id :frontend-ui-layering
    :label "frontend-ui-layering"
    :run frontend-ui-layering-gate}
   {:id :frontend-page-role
    :label "frontend-page-role"
    :run frontend-page-role-gate}
   {:id :frontend-semantics
    :label "frontend-semantics"
    :run frontend-semantics-gate}
   {:id :frontend-style
    :label "frontend-style"
    :run frontend-style-gate}
   {:id :frontend-build
    :label "frontend-build"
    :run frontend-build-gate}])

(defn frontend-gates
  []
  (runner/run-entries! (frontend-gate-entries)))

(defn finish-process!
  [exit-code]
  (shutdown-agents)
  (when (some? exit-code)
    (System/exit exit-code)))

(defn -main
  [& _]
  (let [gates (frontend-gates)]
    (report/print-report! gates)
    (finish-process! (governance/exit-code gates))))
