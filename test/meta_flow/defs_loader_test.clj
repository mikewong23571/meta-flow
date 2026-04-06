(ns meta-flow.defs-loader-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.files :as defs.files]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.defs.repository :as defs.repository]))

(defn- temp-overlay-root
  []
  (.getPath (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-defs-loader"
                                                              (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest filesystem-repository-loads-and-indexes-definitions
  (let [repository (defs.loader/filesystem-definition-repository)
        definitions (defs.protocol/load-workflow-defs repository)
        summary (defs.loader/definitions-summary definitions)]
    (testing "summary counts match milestone 1 expectations"
      (is (= 2 (:task-fsms summary)))
      (is (= 2 (:run-fsms summary))))
    (testing "lookups resolve version-pinned definitions"
      (is (= :task-type/cve-investigation
             (:task-type/id (defs.protocol/find-task-type-def repository :task-type/cve-investigation 1))))
      (is (= :task-type/repo-arch-investigation
             (:task-type/id (defs.protocol/find-task-type-def repository :task-type/repo-arch-investigation 1))))
      (is (= :runtime.adapter/codex
             (:runtime-profile/adapter-id (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1))))
      (is (= "meta_flow/prompts/worker.md"
             (:runtime-profile/worker-prompt-path (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1))))
      (is (= :artifact-contract/cve-investigation
             (get-in (defs.protocol/find-runtime-profile repository :runtime-profile/codex-worker 1)
                     [:runtime-profile/artifact-contract-ref :definition/id])))
      (is (= "meta_flow/prompts/repo-arch-worker.md"
             (:runtime-profile/worker-prompt-path (defs.protocol/find-runtime-profile repository :runtime-profile/codex-repo-arch 1))))
      (is (= :launch.mode/codex-exec
             (:runtime-profile/default-launch-mode
              (defs.protocol/find-runtime-profile repository :runtime-profile/codex-repo-arch 1)))))
    (testing "summary reports every definition bucket"
      (is (= {:task-types 3
              :task-fsms 2
              :run-fsms 2
              :runtime-profiles 3
              :artifact-contracts 3
              :validators 3
              :resource-policies 4}
             summary)))))

(deftest filesystem-definition-repository-delegates-both-arities
  (with-redefs [defs.repository/filesystem-definition-repository (fn
                                                                   ([] ::default-repository)
                                                                   ([resource-base]
                                                                    [:repository resource-base]))]
    (is (= ::default-repository
           (defs.loader/filesystem-definition-repository)))
    (is (= [:repository "alt/base"]
           (defs.loader/filesystem-definition-repository "alt/base")))))

(deftest defs-loader-delegates-overlay-init-and-reload
  (let [repository ::repository]
    (with-redefs [defs.files/initialize-overlay! (fn [overlay-root]
                                                   {:overlay-root overlay-root
                                                    :created-files []})
                  defs.repository/reload-filesystem-definition-repository! (fn [repo]
                                                                             [:reloaded repo])]
      (is (= {:overlay-root "defs/custom"
              :created-files []}
             (defs.loader/init-overlay! "defs/custom")))
      (is (= [:reloaded repository]
             (defs.loader/reload-filesystem-definition-repository! repository))))))

(deftest filesystem-repository-reloads-overlay-changes-explicitly
  (let [overlay-root (temp-overlay-root)
        repository (defs.loader/filesystem-definition-repository
                    {:resource-base "meta_flow/defs"
                     :overlay-root overlay-root})
        active-path (io/file overlay-root "runtime-profiles.edn")
        write-overlay! #(spit active-path
                              "[{:runtime-profile/id :runtime-profile/overlay-worker\n  :runtime-profile/version 1\n  :runtime-profile/name \"Overlay worker\"\n  :runtime-profile/adapter-id :runtime.adapter/mock\n  :runtime-profile/dispatch-mode :dispatch.mode/inline}]\n")]
    (is (nil? (defs.protocol/find-runtime-profile repository
                                                  :runtime-profile/overlay-worker
                                                  1)))
    (.mkdirs (.getParentFile active-path))
    (write-overlay!)
    (is (nil? (defs.protocol/find-runtime-profile repository
                                                  :runtime-profile/overlay-worker
                                                  1)))
    (defs.loader/reload-filesystem-definition-repository! repository)
    (is (= :runtime-profile/overlay-worker
           (:runtime-profile/id (defs.protocol/find-runtime-profile repository
                                                                    :runtime-profile/overlay-worker
                                                                    1))))))

(deftest filesystem-repository-rejects-same-version-overlay-collisions
  (let [overlay-root (temp-overlay-root)
        active-path (io/file overlay-root "runtime-profiles.edn")
        repository (defs.loader/filesystem-definition-repository
                    {:resource-base "meta_flow/defs"
                     :overlay-root overlay-root})]
    (.mkdirs (.getParentFile active-path))
    (spit active-path
          "[{:runtime-profile/id :runtime-profile/mock-worker\n  :runtime-profile/version 1\n  :runtime-profile/name \"Duplicate mock worker\"\n  :runtime-profile/adapter-id :runtime.adapter/mock\n  :runtime-profile/dispatch-mode :dispatch.mode/inline}]\n")
    (try
      (defs.protocol/load-workflow-defs repository)
      (is false "expected duplicate runtime-profile version failure")
      (catch clojure.lang.ExceptionInfo ex
        (is (re-find #"Duplicate definition version in runtime-profiles"
                     (.getMessage ex)))
        (is (= [:runtime-profile/mock-worker 1]
               (:definition-key (ex-data ex))))
        (is (= :bundled
               (get-in (ex-data ex) [:existing-source :definition/layer])))
        (is (= :overlay
               (get-in (ex-data ex) [:duplicate-source :definition/layer])))))))
