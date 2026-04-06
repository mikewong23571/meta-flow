(ns meta-flow.lint.frontend-semantics-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.lint.check.frontend.semantics :as semantics])
  (:import (java.nio.file Files Path)))

(defn temp-dir-path
  []
  (Files/createTempDirectory "meta-flow-frontend-semantics" (make-array java.nio.file.attribute.FileAttribute 0)))

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

(deftest frontend-semantics-gate-detects-text-inside-button-icon-controls
  (let [root (temp-dir-path)]
    (try
      (write-file! root "src/demo/page.cljs"
                   "(ns demo.page)\n(defn page [] [:button {:className \"button button-icon button-primary\"} \"New Task Type\"])\n")
      (let [gate (semantics/frontend-semantics-gate [(.toString (.resolve root "src"))])]
        (is (= :error (:status gate)))
        (is (= 1 (count (:findings gate))))
        (is (= :button-icon-visible-text
               (:type (first (:findings gate))))))
      (finally
        (delete-tree! root)))))

(deftest frontend-semantics-gate-allows-text-buttons-without-button-icon
  (let [root (temp-dir-path)]
    (try
      (write-file! root "src/demo/page.cljs"
                   "(ns demo.page)\n(defn page [] [:button {:className \"button button-primary\"} \"New Task Type\"])\n")
      (let [gate (semantics/frontend-semantics-gate [(.toString (.resolve root "src"))])]
        (is (= :pass (:status gate)))
        (is (= "frontend semantic component usage looks consistent"
               (:headline gate))))
      (finally
        (delete-tree! root)))))
