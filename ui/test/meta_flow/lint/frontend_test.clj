(ns meta-flow.lint.frontend-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.lint.check.frontend :as frontend]
            [meta-flow.lint.check.frontend.build :as frontend-build]
            [meta-flow.lint.check.frontend.style :as frontend-style]
            [meta-flow.lint.check.shared :as shared]
            [meta-flow.lint.file-length :as file-length])
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

(deftest frontend-structure-governance-warns-on-wide-source-directories
  (let [root (temp-dir-path)
        src-root (.resolve root "src")]
    (try
      (doseq [idx (range (inc file-length/directory-warning-threshold))]
        (write-file! root
                     (format "src/meta_flow_ui/pressure/part_%s.cljs" idx)
                     (format "(ns meta-flow-ui.pressure.part-%s)\n" idx)))
      (let [gate (frontend/run-structure-governance! [(.toString src-root)])]
        (is (= :warning (:status gate)))
        (is (some #(= :directory-width (:kind %)) (:issues gate))
            "expected a directory-width issue for an over-wide source directory"))
      (finally
        (delete-tree! root)))))

(deftest frontend-structure-governance-ignores-markdown-files-for-directory-width
  (let [root (temp-dir-path)
        src-root (.resolve root "src")]
    (try
      (doseq [idx (range file-length/directory-warning-threshold)]
        (write-file! root
                     (format "src/meta_flow_ui/pressure/part_%s.cljs" idx)
                     (format "(ns meta-flow-ui.pressure.part-%s)\n" idx)))
      (write-file! root
                   "src/meta_flow_ui/pressure/AGENTS.md"
                   "# ignored\n")
      (let [gate (frontend/run-structure-governance! [(.toString src-root)])]
        (is (= :pass (:status gate)))
        (is (empty? (:issues gate))
            "markdown files should not increase directory-width counts"))
      (finally
        (delete-tree! root)))))

(deftest frontend-structure-governance-warns-on-oversized-source-files
  (let [root (temp-dir-path)
        src-root (.resolve root "src")]
    (try
      (write-file! root
                   "src/meta_flow_ui/pressure/oversized.cljs"
                   (str "(ns meta-flow-ui.pressure.oversized)\n"
                        (apply str (repeat 241 "(def value :ok)\n"))))
      (let [gate (frontend/run-structure-governance! [(.toString src-root)])]
        (is (= :warning (:status gate)))
        (is (some #(= :file-length (:kind %)) (:issues gate))
            "expected a file-length issue for an oversized source file"))
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
                   :action "Run `bb install` and rerun `bb governance`."})]
    (let [gate (frontend-build/frontend-build-gate)]
      (is (= :error (:status gate)))
      (is (= "frontend bootstrap is incomplete because npm dependencies are not installed" (:headline gate)))
      (is (= "Run `bb install` and rerun `bb governance`." (:action gate))))))

(deftest build-bootstrap-status-prefers-missing-npm-over-missing-node-modules
  (let [status (frontend-build/build-bootstrap-status-from
                {:package-json? true
                 :npm-available? false
                 :node-modules? false
                 :shadow-cljs-package? false})]
    (is (= :missing-npm (:state status)))
    (is (= "frontend bootstrap is incomplete because npm is not available in PATH"
           (:headline status)))))

(deftest frontend-gates-return-all-frontend-governance-gates
  (with-redefs [frontend/frontend-architecture-gate (fn [] {:label "frontend-architecture-governance" :status :pass})
                frontend/frontend-shared-component-placement-gate (fn [] {:label "frontend-shared-component-placement-governance" :status :pass})
                frontend/frontend-shared-component-facade-gate (fn [] {:label "frontend-shared-component-facade-governance" :status :pass})
                frontend/frontend-ui-layering-gate (fn [] {:label "frontend-ui-layering-governance" :status :pass})
                frontend/frontend-page-role-gate (fn [] {:label "frontend-page-role-governance" :status :pass})
                frontend/frontend-semantics-gate (fn [] {:label "frontend-semantics-governance" :status :pass})
                frontend/run-structure-governance! (fn [_] {:label "structure-governance" :status :pass})
                frontend/frontend-style-gate (fn [] {:label "frontend-style-governance" :status :pass})
                frontend/frontend-build-gate (fn [] {:label "frontend-build" :status :skipped})]
    (is (= ["frontend-architecture-governance"
            "frontend-shared-component-placement-governance"
            "frontend-shared-component-facade-governance"
            "frontend-ui-layering-governance"
            "frontend-page-role-governance"
            "frontend-semantics-governance"
            "structure-governance"
            "frontend-style-governance"
            "frontend-build"]
           (mapv :label (frontend/frontend-gates))))))
