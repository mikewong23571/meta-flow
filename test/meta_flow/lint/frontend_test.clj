(ns meta-flow.lint.frontend-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.lint.check :as check]
            [meta-flow.lint.check.frontend :as frontend]
            [meta-flow.lint.check.frontend.build :as frontend-build]
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

(deftest frontend-build-gate-skips-when-npm-bootstrap-is-missing
  (with-redefs [frontend-build/build-bootstrap-status
                (fn []
                  {:state :missing-node-modules
                   :headline "skipped because frontend npm dependencies are not installed"
                   :action "Run `bb ui:install` to enable frontend build governance."})]
    (let [gate (frontend-build/frontend-build-gate)]
      (is (= :skipped (:status gate)))
      (is (= "skipped because frontend npm dependencies are not installed" (:headline gate))))))

(deftest check-gates-include-frontend-governance
  (with-redefs [check/run-format-check! (fn [] {:label "format-hygiene" :status :pass})
                check/run-static-analysis! (fn [] {:label "static-analysis" :status :pass})
                check/run-structure-governance! (fn [] {:label "structure-governance" :status :pass})
                check/frontend-style-gate (fn [] {:label "frontend-style-governance" :status :pass})
                check/frontend-build-gate (fn [] {:label "frontend-build" :status :pass})
                coverage/evaluate-coverage
                (fn []
                  {:exit 0
                   :counts {:tests 1}
                   :combined ""
                   :summary {:line-coverage 90.0
                             :level nil
                             :lowest-namespaces []}})]
    (let [labels (mapv :label (check/check-gates))]
      (is (= ["format-hygiene"
              "static-analysis"
              "structure-governance"
              "frontend-style-governance"
              "frontend-build"
              "executable-correctness"
              "coverage-governance"]
             labels)))))

(deftest frontend-gates-return-style-and-build-gates
  (with-redefs [frontend/frontend-style-gate (fn [] {:label "frontend-style-governance" :status :pass})
                frontend/frontend-build-gate (fn [] {:label "frontend-build" :status :skipped})]
    (is (= ["frontend-style-governance" "frontend-build"]
           (mapv :label (frontend/frontend-gates))))))
