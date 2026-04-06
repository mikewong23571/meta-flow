(ns meta-flow.lint.check.frontend.shared-ui
  (:require [meta-flow.lint.check.frontend.shared-ui-support :as support]))

(def frontend-ui-root
  "src/meta_flow_ui")

(def shared-ui-root
  (str frontend-ui-root "/ui"))

(def shared-component-facade-file
  (str frontend-ui-root "/components.cljs"))

(def shared-component-facade-warning-threshold 80)
(def shared-component-facade-error-threshold 120)

(defn- options
  []
  {:shared-ui-root shared-ui-root
   :shared-component-facade-file shared-component-facade-file
   :shared-component-facade-warning-threshold shared-component-facade-warning-threshold
   :shared-component-facade-error-threshold shared-component-facade-error-threshold})

(defn frontend-shared-component-placement-gate
  []
  (let [findings (support/placement-findings (options))]
    {:gate :frontend-shared-component-placement-governance
     :label "frontend-shared-component-placement-governance"
     :status (if (seq findings) :error :pass)
     :headline (if (seq findings)
                 (str (count findings) " shared component placement finding(s) require ui/layout, ui/patterns, or ui/primitives")
                 "shared component implementation lives under ui/layout, ui/patterns, or ui/primitives")
     :findings (support/sorted-findings findings 8)
     :action "Place shared UI implementation in ui/layout, ui/patterns, or ui/primitives, and keep namespace prefixes aligned with that placement."}))

(defn frontend-shared-component-facade-gate
  []
  (let [opts (options)
        findings (support/invalid-facade-findings opts)
        issues (support/facade-issues opts)
        error-count (count (filter #(= :error (:level %)) issues))]
    {:gate :frontend-shared-component-facade-governance
     :label "frontend-shared-component-facade-governance"
     :status (cond
               (seq findings) :error
               (pos? error-count) :error
               (seq issues) :warning
               :else :pass)
     :headline (cond
                 (seq findings)
                 (str (count findings) " components facade finding(s) require moving implementation out of components.cljs")
                 (seq issues)
                 (str (count issues) " components facade issue(s) require a thinner facade")
                 :else
                 "components.cljs is a thin shared-ui facade")
     :findings (support/sorted-findings findings 5)
     :issues (support/sorted-issues issues)
     :action "Keep components.cljs as a thin re-export facade only; move shared implementation into ui/layout, ui/patterns, or ui/primitives."}))

(defn frontend-ui-layering-gate
  []
  (let [findings (support/layering-findings (options))]
    {:gate :frontend-ui-layering-governance
     :label "frontend-ui-layering-governance"
     :status (if (seq findings) :error :pass)
     :headline (if (seq findings)
                 (str (count findings) " shared ui layering finding(s) require dependency cleanup")
                 "shared ui layers respect page/state dependency boundaries")
     :findings (support/sorted-findings findings 8)
     :action "Keep ui/primitives, ui/layout, and ui/patterns independent from page namespaces, and keep ui/layout independent from frontend state namespaces."}))
