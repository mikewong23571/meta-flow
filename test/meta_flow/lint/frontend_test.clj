(ns meta-flow.lint.frontend-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.lint.check :as check]
            [meta-flow.lint.check.frontend :as frontend]
            [meta-flow.lint.check.frontend.architecture :as frontend-architecture]
            [meta-flow.lint.check.frontend.build :as frontend-build]
            [meta-flow.lint.check.frontend.shared-ui :as frontend-shared-ui]
            [meta-flow.lint.check.frontend.style :as frontend-style]
            [meta-flow.lint.check.shared :as shared]
            [meta-flow.lint.coverage :as coverage])
  (:import (java.nio.file Files Path)))

(defn temp-dir-path
  []
  (Files/createTempDirectory "meta-flow-frontend-governance" (make-array java.nio.file.attribute.FileAttribute 0)))

(defn delete-tree!
  [^Path path]
  (let [file (.toFile path)]
    (when (.exists file)
      (doseq [entry (reverse (file-seq file))]
        (.delete ^java.io.File entry)))))

(defn write-file!
  [^Path root relative-path content]
  (let [target (.resolve root relative-path)]
    (Files/createDirectories (.getParent target) (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (.toFile target) content)
    (.toString target)))

(deftest frontend-style-gate-allows-token-definition-files
  (let [root (temp-dir-path)]
    (try
      (write-file! root "styles/tokens.css" ":root { --color-brand: #ffffff; }\n")
      (write-file! root "styles/theme.css" ":root { --color-surface: rgba(0, 0, 0, 0.1); }\n")
      (let [gate (frontend-style/frontend-style-gate [(.toString root)])]
        (is (= :pass (:status gate)))
        (is (= "no frontend style governance findings" (:headline gate))))
      (finally
        (delete-tree! root)))))

(deftest frontend-style-gate-detects-raw-colors-outside-token-files
  (let [root (temp-dir-path)]
    (try
      (write-file! root "styles/components.css"
                   ".button { color: #fff; background: rgba(0, 0, 0, 0.4); }\n")
      (let [gate (frontend-style/frontend-style-gate [(.toString root)])]
        (is (= :error (:status gate)))
        (is (= 2 (count (:findings gate))))
        (is (= :raw-style-literal
               (:type (first (:findings gate))))))
      (finally
        (delete-tree! root)))))

(deftest frontend-style-gate-warns-on-oversized-shared-css-files
  (let [root (temp-dir-path)]
    (try
      (write-file! root "styles/components.css"
                   (str ".button {\n"
                        (apply str (repeat 321 "  padding: var(--space-md);\n"))
                        "}\n"))
      (let [gate (frontend-style/frontend-style-gate [(.toString root)])]
        (is (= :warning (:status gate)))
        (is (= 1 (count (:issues gate))))
        (is (= :css-file-length (:kind (first (:issues gate))))))
      (finally
        (delete-tree! root)))))

(deftest frontend-style-gate-warns-on-wide-style-directories
  (let [root (temp-dir-path)]
    (try
      (doseq [idx (range 7)]
        (write-file! root (format "styles/part_%s.css" idx)
                     ".part { color: var(--color-text-primary); }\n"))
      (let [gate (frontend-style/frontend-style-gate [(.toString root)])]
        (is (= :warning (:status gate)))
        (is (some #(= :css-directory-width (:kind %)) (:issues gate))))
      (finally
        (delete-tree! root)))))

(deftest frontend-architecture-gate-detects-page-specific-names-in-shared-layers
  (let [root (temp-dir-path)]
    (try
      (let [shared-source (write-file! root "components.cljs"
                                       "(ns demo.components)\n(defn shell [] [:div {:className \"scheduler-title\"}])\n")
            shared-style (write-file! root "components.css"
                                      ".scheduler-title { color: var(--color-text-primary); }\n")]
        (with-redefs [frontend-architecture/shared-frontend-source-files [shared-source]
                      frontend-architecture/shared-frontend-source-roots []
                      frontend-architecture/shared-frontend-style-files [shared-style]]
          (let [gate (frontend-architecture/frontend-architecture-gate)]
            (is (= :error (:status gate)))
            (is (= 2 (count (:findings gate))))
            (is (= #{:shared-ui-page-specific-class
                     :shared-style-page-specific-class}
                   (into #{} (map :type (:findings gate))))))))
      (finally
        (delete-tree! root)))))

(deftest frontend-architecture-gate-allows-neutral-shared-names
  (let [root (temp-dir-path)]
    (try
      (let [shared-source (write-file! root "components.cljs"
                                       "(ns demo.components)\n(defn shell [] [:div {:className \"page-title\"}])\n")
            shared-style (write-file! root "components.css"
                                      ".page-title { color: var(--color-text-primary); }\n")]
        (with-redefs [frontend-architecture/shared-frontend-source-files [shared-source]
                      frontend-architecture/shared-frontend-source-roots []
                      frontend-architecture/shared-frontend-style-files [shared-style]]
          (let [gate (frontend-architecture/frontend-architecture-gate)]
            (is (= :pass (:status gate)))
            (is (= "shared frontend layers use neutral, reusable naming"
                   (:headline gate))))))
      (finally
        (delete-tree! root)))))

(deftest frontend-shared-component-placement-gate-detects-misplaced-shared-ui-files
  (let [root (temp-dir-path)
        shared-ui-root (.toString (.resolve root "frontend/src/meta_flow_ui/ui"))]
    (try
      (write-file! root "frontend/src/meta_flow_ui/ui/shared.cljs"
                   "(ns meta-flow-ui.ui.shared)\n(defn shared-panel [] [:section])\n")
      (with-redefs [frontend-shared-ui/shared-ui-root shared-ui-root]
        (let [gate (frontend-shared-ui/frontend-shared-component-placement-gate)]
          (is (= :error (:status gate)))
          (is (= :shared-ui-placement
                 (:type (first (:findings gate)))))))
      (finally
        (delete-tree! root)))))

(deftest frontend-shared-component-facade-gate-detects-implementation
  (let [root (temp-dir-path)
        facade-file (write-file! root "frontend/src/meta_flow_ui/components.cljs"
                                 "(ns meta-flow-ui.components)\n(defn badge [] [:span])\n")]
    (try
      (with-redefs [frontend-shared-ui/shared-component-facade-file facade-file]
        (let [gate (frontend-shared-ui/frontend-shared-component-facade-gate)]
          (is (= :error (:status gate)))
          (is (= :shared-component-facade-implementation
                 (:type (first (:findings gate)))))))
      (finally
        (delete-tree! root)))))

(deftest frontend-ui-layering-gate-detects-page-and-state-dependencies
  (let [root (temp-dir-path)
        shared-ui-root (.toString (.resolve root "frontend/src/meta_flow_ui/ui"))]
    (try
      (write-file! root "frontend/src/meta_flow_ui/ui/layout.cljs"
                   "(ns meta-flow-ui.ui.layout\n  (:require [meta-flow-ui.pages.scheduler :as scheduler]\n            [meta-flow-ui.state :as state]))\n(defn shell [] [:main])\n")
      (write-file! root "frontend/src/meta_flow_ui/ui/patterns.cljs"
                   "(ns meta-flow-ui.ui.patterns\n  (:require [meta-flow-ui.pages.tasks :as tasks]))\n(defn detail-row [] [:div])\n")
      (with-redefs [frontend-shared-ui/shared-ui-root shared-ui-root]
        (let [gate (frontend-shared-ui/frontend-ui-layering-gate)]
          (is (= :error (:status gate)))
          (is (= 3 (count (:findings gate))))
          (is (every? #(= :shared-ui-layering (:type %)) (:findings gate)))))
      (finally
        (delete-tree! root)))))

(deftest frontend-build-gate-reports-pass-and-failure
  (with-redefs [shared/run-command!
                (fn [_]
                  {:exit 0
                   :combined "[:app] Build completed."})]
    (with-redefs [frontend-build/build-bootstrap-status (fn [] {:state :ready})]
      (let [gate (frontend-build/frontend-build-gate)]
        (is (= :pass (:status gate)))
        (is (= "frontend build check passed" (:headline gate))))))
  (with-redefs [shared/run-command!
                (fn [_]
                  {:exit 1
                   :combined "Execution error (ExceptionInfo) at shadow-cljs\nboom"})]
    (with-redefs [frontend-build/build-bootstrap-status (fn [] {:state :ready})]
      (let [gate (frontend-build/frontend-build-gate)]
        (is (= :error (:status gate)))
        (is (= "frontend build check failed" (:headline gate)))
        (is (= "Execution error (ExceptionInfo) at shadow-cljs" (:cause gate)))))))

(deftest frontend-build-gate-fails-when-npm-bootstrap-is-missing
  (with-redefs [frontend-build/build-bootstrap-status
                (fn []
                  {:state :missing-node-modules
                   :headline "frontend bootstrap is incomplete because npm dependencies are not installed"
                   :action "Run `bb ui:install` and rerun `bb check`."})]
    (let [gate (frontend-build/frontend-build-gate)]
      (is (= :error (:status gate)))
      (is (= "frontend bootstrap is incomplete because npm dependencies are not installed" (:headline gate)))
      (is (= "Run `bb ui:install` and rerun `bb check`." (:action gate))))))

(deftest build-bootstrap-status-prefers-missing-npm-over-missing-node-modules
  (let [status (frontend-build/build-bootstrap-status-from
                {:package-json? true
                 :npm-available? false
                 :node-modules? false
                 :shadow-cljs-package? false})]
    (is (= :missing-npm (:state status)))
    (is (= "frontend bootstrap is incomplete because npm is not available in PATH"
           (:headline status)))))

(deftest check-gates-include-frontend-governance
  (let [calls (atom [])]
    (with-redefs [check/run-format-check! (fn [] {:label "format-hygiene" :status :pass})
                  check/run-static-analysis! (fn [] {:label "static-analysis" :status :pass})
                  check/run-structure-governance! (fn [] {:label "structure-governance" :status :pass})
                  check/frontend-architecture-gate (fn [] {:label "frontend-architecture-governance" :status :pass})
                  check/frontend-shared-component-placement-gate (fn [] {:label "frontend-shared-component-placement-governance" :status :pass})
                  check/frontend-shared-component-facade-gate (fn [] {:label "frontend-shared-component-facade-governance" :status :pass})
                  check/frontend-ui-layering-gate (fn [] {:label "frontend-ui-layering-governance" :status :pass})
                  check/frontend-style-gate (fn [] {:label "frontend-style-governance" :status :pass})
                  check/frontend-build-gate (fn [] {:label "frontend-build" :status :pass})
                  coverage/evaluate-coverage
                  (fn
                    ([] (swap! calls conj :default)
                        {:exit 0
                         :counts {:tests 1}
                         :combined ""
                         :summary {:line-coverage 90.0
                                   :level nil
                                   :lowest-namespaces []}})
                    ([opts]
                     (swap! calls conj opts)
                     {:exit 0
                      :counts {:tests 1}
                      :combined ""
                      :summary {:line-coverage 90.0
                                :level nil
                                :lowest-namespaces []}}))]
      (let [labels (mapv :label (check/check-gates))]
        (is (= ["format-hygiene"
                "static-analysis"
                "structure-governance"
                "frontend-architecture-governance"
                "frontend-shared-component-placement-governance"
                "frontend-shared-component-facade-governance"
                "frontend-ui-layering-governance"
                "frontend-style-governance"
                "frontend-build"
                "executable-correctness"
                "coverage-governance"]
               labels))
        (is (= [:default] @calls))))))

(deftest frontend-gates-return-all-frontend-governance-gates
  (with-redefs [frontend/frontend-architecture-gate (fn [] {:label "frontend-architecture-governance" :status :pass})
                frontend/frontend-shared-component-placement-gate (fn [] {:label "frontend-shared-component-placement-governance" :status :pass})
                frontend/frontend-shared-component-facade-gate (fn [] {:label "frontend-shared-component-facade-governance" :status :pass})
                frontend/frontend-ui-layering-gate (fn [] {:label "frontend-ui-layering-governance" :status :pass})
                frontend/frontend-style-gate (fn [] {:label "frontend-style-governance" :status :pass})
                frontend/frontend-build-gate (fn [] {:label "frontend-build" :status :skipped})]
    (is (= ["frontend-architecture-governance"
            "frontend-shared-component-placement-governance"
            "frontend-shared-component-facade-governance"
            "frontend-ui-layering-governance"
            "frontend-style-governance"
            "frontend-build"]
           (mapv :label (frontend/frontend-gates))))))
