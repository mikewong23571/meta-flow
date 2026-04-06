(ns meta-flow.defs.authoring-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.authoring :as defs.authoring]
            [meta-flow.defs.loader :as defs.loader]))

(defn- bundled-repository
  []
  (defs.loader/filesystem-definition-repository))

(defn- temp-overlay-root
  []
  (.getPath (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-defs-authoring"
                                                              (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest authoring-contract-documents-supported-request-shapes
  (let [contract (defs.authoring/authoring-contract)]
    (is (= [:runtime-profile/web-search-enabled?
            :runtime-profile/worker-prompt-path]
           (get-in contract [:runtime-profile :supported-override-keys])))
    (is (= [:task-type/runtime-profile-ref
            :task-type/input-schema
            :task-type/work-key-expr]
           (get-in contract [:task-type :supported-override-keys])))
    (is (= :publish-order/task-type-runtime-profile
           (get-in contract [:publish-order-rules 0 :rule/id])))))

(deftest list-definition-templates-returns-sorted-template-summaries
  (let [repository (bundled-repository)
        templates (defs.authoring/list-definition-templates repository :task-type)]
    (is (= :task-type
           (:definition-kind templates)))
    (is (= [{:definition/id :task-type/cve-investigation
             :definition/version 1
             :definition/name "CVE investigation"}
            {:definition/id :task-type/default
             :definition/version 1
             :definition/name "Default generic task"}
            {:definition/id :task-type/repo-arch-investigation
             :definition/version 1
             :definition/name "Repo architecture investigation"}]
           (:templates templates)))))

(deftest list-definition-templates-rejects-unsupported-definition-kinds
  (let [repository (bundled-repository)]
    (try
      (defs.authoring/list-definition-templates repository :validator)
      (is false "expected unsupported definition kind failure")
      (catch clojure.lang.ExceptionInfo ex
        (is (re-find #"Unsupported definition kind :validator"
                     (.getMessage ex)))
        (is (= :validator
               (:definition-kind (ex-data ex))))))))

(deftest prepare-runtime-profile-draft-request-applies-defaults-and-resolves-latest-template
  (let [overlay-root (temp-overlay-root)
        repository (defs.loader/filesystem-definition-repository
                    {:resource-base "meta_flow/defs"
                     :overlay-root overlay-root})
        overlay-file (io/file overlay-root "runtime-profiles.edn")]
    (.mkdirs (.getParentFile overlay-file))
    (spit overlay-file
          "[{:runtime-profile/id :runtime-profile/codex-worker\n  :runtime-profile/version 2\n  :runtime-profile/name \"Codex worker v2\"\n  :runtime-profile/adapter-id :runtime.adapter/codex\n  :runtime-profile/dispatch-mode :runtime.dispatch/external\n  :runtime-profile/codex-home-root \"var/codex-home\"\n  :runtime-profile/allowed-mcp-servers [:mcp/context7]\n  :runtime-profile/web-search-enabled? false\n  :runtime-profile/worker-prompt-path \"meta_flow/prompts/worker.md\"\n  :runtime-profile/helper-script-path \"script/worker_api.bb\"\n  :runtime-profile/artifact-contract-ref {:definition/id :artifact-contract/cve-investigation\n                                          :definition/version 1}\n  :runtime-profile/worker-timeout-seconds 1800\n  :runtime-profile/heartbeat-interval-seconds 30\n  :runtime-profile/env-allowlist [\"OPENAI_API_KEY\" \"PATH\"]}]\n")
    (let [result (defs.authoring/prepare-runtime-profile-draft-request!
                  repository
                  {:authoring/from-id :runtime-profile/codex-worker
                   :authoring/new-id :runtime-profile/repo-review
                   :authoring/new-name "Repo review worker"
                   :authoring/overrides {:runtime-profile/web-search-enabled? false}})]
      (is (= {:definition/id :runtime-profile/codex-worker
              :definition/version 2
              :definition/name "Codex worker v2"}
             (:template result)))
      (is (= 2
             (get-in result [:request :authoring/from-version])))
      (is (= 1
             (get-in result [:request :authoring/new-version])))
      (is (= false
             (get-in result [:request :authoring/overrides :runtime-profile/web-search-enabled?]))))))

(deftest prepare-draft-request-rejects-unsupported-override-keys
  (let [repository (bundled-repository)]
    (try
      (defs.authoring/prepare-runtime-profile-draft-request!
       repository
       {:authoring/from-id :runtime-profile/codex-worker
        :authoring/new-id :runtime-profile/repo-review
        :authoring/new-name "Repo review worker"
        :authoring/overrides {:runtime-profile/allowed-mcp-servers [:mcp/context7]}})
      (is false "expected unsupported override failure")
      (catch clojure.lang.ExceptionInfo ex
        (is (re-find #"Unsupported runtime-profile override keys"
                     (.getMessage ex)))
        (is (= [:runtime-profile/allowed-mcp-servers]
               (:unsupported-override-keys (ex-data ex))))
        (is (= [:runtime-profile/web-search-enabled?
                :runtime-profile/worker-prompt-path]
               (:supported-override-keys (ex-data ex))))))))

(deftest prepare-draft-request-rejects-invalid-definition-namespaces
  (let [repository (bundled-repository)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":authoring/new-id must use keyword namespace runtime-profile"
                          (defs.authoring/prepare-runtime-profile-draft-request!
                           repository
                           {:authoring/from-id :runtime-profile/codex-worker
                            :authoring/new-id :task-type/repo-review
                            :authoring/new-name "Repo review worker"})))))

(deftest prepare-draft-request-rejects-blank-names
  (let [repository (bundled-repository)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":authoring/new-name must be a non-blank string"
                          (defs.authoring/prepare-runtime-profile-draft-request!
                           repository
                           {:authoring/from-id :runtime-profile/codex-worker
                            :authoring/new-id :runtime-profile/repo-review
                            :authoring/new-name "   "})))))

(deftest prepare-draft-request-rejects-same-id-and-version-as-template
  (let [repository (bundled-repository)]
    (try
      (defs.authoring/prepare-runtime-profile-draft-request!
       repository
       {:authoring/from-id :runtime-profile/codex-worker
        :authoring/new-id :runtime-profile/codex-worker
        :authoring/new-name "Codex worker clone"})
      (is false "expected same id/version rejection")
      (catch clojure.lang.ExceptionInfo ex
        (is (re-find #"Draft request must change id or version from the source template"
                     (.getMessage ex)))
        (is (= {:definition/id :runtime-profile/codex-worker
                :definition/version 1
                :definition/name "Codex worker"}
               (:template (ex-data ex))))))))

(deftest prepare-draft-request-rejects-missing-explicit-template-version
  (let [repository (bundled-repository)]
    (try
      (defs.authoring/prepare-task-type-draft-request!
       repository
       {:authoring/from-id :task-type/repo-arch-investigation
        :authoring/from-version 99
        :authoring/new-id :task-type/repo-review
        :authoring/new-name "Repo review"})
      (is false "expected missing explicit template version failure")
      (catch clojure.lang.ExceptionInfo ex
        (is (re-find #"Template task type not found"
                     (.getMessage ex)))
        (is (= :task-type
               (:definition-kind (ex-data ex))))
        (is (= 99
               (:definition/version (ex-data ex))))))))

(deftest prepare-task-type-draft-request-enforces-publish-order-for-runtime-profile-refs
  (let [repository (bundled-repository)]
    (testing "published runtime-profile refs are accepted"
      (let [result (defs.authoring/prepare-task-type-draft-request!
                    repository
                    {:authoring/from-id :task-type/repo-arch-investigation
                     :authoring/new-id :task-type/repo-review
                     :authoring/new-name "Repo review"
                     :authoring/overrides {:task-type/runtime-profile-ref
                                           {:definition/id :runtime-profile/codex-worker
                                            :definition/version 1}}})]
        (is (= :task-type/repo-arch-investigation
               (get-in result [:template :definition/id])))))
    (testing "unpublished runtime-profile refs fail with an explicit publish-first message"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Publish the runtime-profile draft into defs/runtime-profiles.edn before creating or validating the task-type draft"
                            (defs.authoring/prepare-task-type-draft-request!
                             repository
                             {:authoring/from-id :task-type/repo-arch-investigation
                              :authoring/new-id :task-type/repo-review
                              :authoring/new-name "Repo review"
                              :authoring/overrides {:task-type/runtime-profile-ref
                                                    {:definition/id :runtime-profile/repo-review
                                                     :definition/version 1}}}))))))
