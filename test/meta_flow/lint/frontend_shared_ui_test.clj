(ns meta-flow.lint.frontend-shared-ui-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.lint.check.frontend.architecture :as frontend-architecture]
            [meta-flow.lint.check.frontend.shared-ui :as frontend-shared-ui])
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
