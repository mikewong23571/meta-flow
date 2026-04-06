(ns meta-flow.lint.frontend-page-roles-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.lint.check.frontend.page-roles :as page-roles])
  (:import (java.nio.file Files Path)))

(defn temp-dir-path
  []
  (Files/createTempDirectory "meta-flow-frontend-page-roles" (make-array java.nio.file.attribute.FileAttribute 0)))

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

(deftest frontend-page-role-gate-detects-authoring-state-implementation
  (let [root (temp-dir-path)
        pages-root (.toString (.resolve root "src/meta_flow_ui/pages"))]
    (try
      (write-file! root
                   "src/meta_flow_ui/pages/defs/authoring/state.cljs"
                   "(ns meta-flow-ui.pages.defs.authoring.state\n  (:require [meta-flow-ui.pages.defs.authoring.mutate :as mutate]))\n(def submit! mutate/submit!)\n(defn helper [] :bad)\n")
      (with-redefs [page-roles/frontend-pages-root pages-root]
        (let [gate (page-roles/frontend-page-role-gate)]
          (is (= :error (:status gate)))
          (is (= :page-role-facade-implementation
                 (:type (first (:findings gate)))))))
      (finally
        (delete-tree! root)))))

(deftest frontend-page-role-gate-detects-non-orchestration-files-reaching-into-http-and-global-state
  (let [root (temp-dir-path)
        pages-root (.toString (.resolve root "src/meta_flow_ui/pages"))]
    (try
      (write-file! root
                   "src/meta_flow_ui/pages/defs/authoring/runtime_profile/dialog.cljs"
                   "(ns meta-flow-ui.pages.defs.authoring.runtime-profile.dialog\n  (:require [meta-flow-ui.http :as http]))\n(defn dialog [] http/post-json)\n")
      (write-file! root
                   "src/meta_flow_ui/pages/defs/authoring/runtime_profile/shared.cljs"
                   "(ns meta-flow-ui.pages.defs.authoring.runtime-profile.shared\n  (:require [meta-flow-ui.state :as state]))\n(defn summary [] state/ui-state)\n")
      (with-redefs [page-roles/frontend-pages-root pages-root]
        (let [gate (page-roles/frontend-page-role-gate)]
          (is (= :error (:status gate)))
          (is (= 2 (count (:findings gate))))
          (is (every? #(= :page-role-layering (:type %))
                      (:findings gate)))))
      (finally
        (delete-tree! root)))))

(deftest frontend-page-role-gate-detects-other-authoring-files-reaching-into-http-and-global-state
  (let [root (temp-dir-path)
        pages-root (.toString (.resolve root "src/meta_flow_ui/pages"))]
    (try
      (write-file! root
                   "src/meta_flow_ui/pages/defs/authoring/task_type/generation.cljs"
                   "(ns meta-flow-ui.pages.defs.authoring.task-type.generation\n  (:require [meta-flow-ui.http :as http]\n            [meta-flow-ui.state :as state]))\n(defn generate [] [http/post-json state/ui-state])\n")
      (with-redefs [page-roles/frontend-pages-root pages-root]
        (let [gate (page-roles/frontend-page-role-gate)]
          (is (= :error (:status gate)))
          (is (= 2 (count (:findings gate))))
          (is (every? #(= :page-role-layering (:type %))
                      (:findings gate)))))
      (finally
        (delete-tree! root)))))

(deftest frontend-page-role-gate-allows-thin-facades-and-view-modules-without-http-or-global-state
  (let [root (temp-dir-path)
        pages-root (.toString (.resolve root "src/meta_flow_ui/pages"))]
    (try
      (write-file! root
                   "src/meta_flow_ui/pages/defs/authoring/state.cljs"
                   "(ns meta-flow-ui.pages.defs.authoring.state\n  (:require [meta-flow-ui.pages.defs.authoring.mutate :as mutate]))\n(def submit! mutate/submit!)\n")
      (write-file! root
                   "src/meta_flow_ui/pages/defs/authoring/runtime_profile/dialog.cljs"
                   "(ns meta-flow-ui.pages.defs.authoring.runtime-profile.dialog\n  (:require [meta-flow-ui.pages.defs.authoring.runtime-profile.shared :as shared]))\n(defn dialog [] shared/title)\n")
      (with-redefs [page-roles/frontend-pages-root pages-root]
        (let [gate (page-roles/frontend-page-role-gate)]
          (is (= :pass (:status gate)))
          (is (= "authoring page roles respect facade and orchestration boundaries"
                 (:headline gate)))))
      (finally
        (delete-tree! root)))))
