(ns meta-flow.defs.authoring-workflow-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [meta-flow.defs.authoring :as defs.authoring]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]))

(defn- temp-overlay-root
  []
  (.getPath (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-defs-authoring-flow"
                                                              (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest create-and-publish-drafts-round-trips-through-the-overlay
  (let [overlay-root (temp-overlay-root)
        repository (defs.loader/filesystem-definition-repository
                    {:resource-base "meta_flow/defs"
                     :overlay-root overlay-root})
        runtime-result (defs.authoring/create-runtime-profile-draft!
                        repository
                        {:authoring/from-id :runtime-profile/codex-worker
                         :authoring/new-id :runtime-profile/repo-review
                         :authoring/new-name "Codex repo review worker"
                         :authoring/overrides {:runtime-profile/web-search-enabled? false
                                               :runtime-profile/worker-prompt-path "meta_flow/prompts/worker.md"}})]
    (is (str/ends-with? (:draft-path runtime-result)
                        "/drafts/runtime-profiles/runtime-profile_repo-review_v1.edn"))
    (is (.isFile (io/file (:draft-path runtime-result))))
    (is (= false
           (get-in runtime-result [:definition :runtime-profile/web-search-enabled?])))
    (let [runtime-publish-result (defs.authoring/publish-runtime-profile-draft!
                                  repository
                                  {:definition/id :runtime-profile/repo-review
                                   :definition/version 1})]
      (is (= (str overlay-root "/runtime-profiles.edn")
             (:published-path runtime-publish-result)))
      (defs.loader/reload-filesystem-definition-repository! repository)
      (is (= :runtime-profile/repo-review
             (:runtime-profile/id
              (defs.protocol/find-runtime-profile repository :runtime-profile/repo-review 1)))))
    (let [task-result (defs.authoring/create-task-type-draft!
                       repository
                       {:authoring/from-id :task-type/repo-arch-investigation
                        :authoring/new-id :task-type/repo-review
                        :authoring/new-name "Repo review"
                        :authoring/overrides {:task-type/runtime-profile-ref
                                              {:definition/id :runtime-profile/repo-review
                                               :definition/version 1}}})]
      (is (str/ends-with? (:draft-path task-result)
                          "/drafts/task-types/task-type_repo-review_v1.edn"))
      (let [task-publish-result (defs.authoring/publish-task-type-draft!
                                 repository
                                 {:definition/id :task-type/repo-review
                                  :definition/version 1})]
        (is (= (str overlay-root "/task-types.edn")
               (:published-path task-publish-result)))
        (defs.loader/reload-filesystem-definition-repository! repository)
        (is (= {:definition/id :runtime-profile/repo-review
                :definition/version 1}
               (:task-type/runtime-profile-ref
                (defs.protocol/find-task-type-def repository :task-type/repo-review 1))))))))

(deftest publish-draft-rejects-a-missing-draft-file
  (let [overlay-root (temp-overlay-root)
        repository (defs.loader/filesystem-definition-repository
                    {:resource-base "meta_flow/defs"
                     :overlay-root overlay-root})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Draft runtime profile not found"
                          (defs.authoring/publish-runtime-profile-draft!
                           repository
                           {:definition/id :runtime-profile/missing
                            :definition/version 1})))))
