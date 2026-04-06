(ns meta-flow.defs.generation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.generation.core :as defs.generation]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.source :as defs.source]))

(defn- temp-overlay-root
  []
  (.getPath (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-defs-generation"
                                                              (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest generate-task-type-draft-creates-linked-runtime-profile-and-task-type-drafts
  (let [overlay-root (temp-overlay-root)
        repository (defs.loader/filesystem-definition-repository
                    {:resource-base defs.source/default-resource-base
                     :overlay-root overlay-root})
        description "Create a repo review task that uses Codex, disables web search, and emits a markdown report"
        result (defs.generation/generate-task-type-draft! repository
                                                          {:generation/description description})]
    (is (= :ok
           (get-in result [:validation :status])))
    (is (= :runtime-profile/repo-review
           (get-in result [:runtime-profile :definition :runtime-profile/id])))
    (is (= false
           (get-in result [:runtime-profile :definition :runtime-profile/web-search-enabled?])))
    (is (str/ends-with? (get-in result [:runtime-profile :draft-path])
                        "/drafts/runtime-profiles/runtime-profile_repo-review_v1.edn"))
    (is (= :task-type/repo-review
           (get-in result [:task-type :definition :task-type/id])))
    (is (= description
           (get-in result [:task-type :definition :task-type/description])))
    (is (= {:definition/id :runtime-profile/repo-review
            :definition/version 1}
           (get-in result [:task-type :definition :task-type/runtime-profile-ref])))
    (is (= {:work-key/type :work-key.type/tuple
            :work-key/tag :repo-review
            :work-key/fields [:input/repo-url :input/notify-email]}
           (get-in result [:task-type :definition :task-type/work-key-expr])))
    (is (str/ends-with? (get-in result [:task-type :draft-path])
                        "/drafts/task-types/task-type_repo-review_v1.edn"))
    (is (= ["Drafts remain under defs/drafts until publish."
            "Publish the runtime-profile draft before publishing the task-type draft."]
           (:notes result)))))

(deftest generate-task-type-draft-avoids-template-id-collisions-for-default-cve-generation
  (let [overlay-root (temp-overlay-root)
        repository (defs.loader/filesystem-definition-repository
                    {:resource-base defs.source/default-resource-base
                     :overlay-root overlay-root})
        result (defs.generation/generate-task-type-draft! repository
                                                          {:generation/description "Create a CVE investigation task"})]
    (is (= nil
           (:runtime-profile result)))
    (is (= :task-type/cve-investigation-generated
           (get-in result [:task-type :definition :task-type/id])))
    (is (= {:definition/id :runtime-profile/codex-worker
            :definition/version 1}
           (get-in result [:task-type :definition :task-type/runtime-profile-ref])))
    (is (= ["Drafts remain under defs/drafts until publish."]
           (:notes result)))))

(deftest generate-task-type-draft-uses-latest-explicit-runtime-profile-template-version
  (let [overlay-root (temp-overlay-root)
        _ (spit (str overlay-root "/runtime-profiles.edn")
                "[{:runtime-profile/id :runtime-profile/codex-worker\n  :runtime-profile/version 2\n  :runtime-profile/name \"Codex worker v2\"\n  :runtime-profile/adapter-id :runtime.adapter/codex\n  :runtime-profile/dispatch-mode :runtime.dispatch/external\n  :runtime-profile/codex-home-root \"var/codex-home\"\n  :runtime-profile/allowed-mcp-servers [:mcp/context7]\n  :runtime-profile/web-search-enabled? false\n  :runtime-profile/worker-prompt-path \"meta_flow/prompts/worker.md\"\n  :runtime-profile/helper-script-path \"script/worker_api.bb\"\n  :runtime-profile/artifact-contract-ref {:definition/id :artifact-contract/cve-investigation\n                                          :definition/version 1}\n  :runtime-profile/worker-timeout-seconds 1800\n  :runtime-profile/heartbeat-interval-seconds 30\n  :runtime-profile/env-allowlist [\"OPENAI_API_KEY\" \"PATH\"]}]\n")
        repository (defs.loader/filesystem-definition-repository
                    {:resource-base defs.source/default-resource-base
                     :overlay-root overlay-root})
        result (defs.generation/generate-task-type-draft! repository
                                                          {:generation/description "Create a CVE investigation task"
                                                           :generation/task-type-id :task-type/cve-followup
                                                           :generation/task-type-name "CVE followup"
                                                           :generation/runtime-profile-template-id :runtime-profile/codex-worker})]
    (is (= nil
           (:runtime-profile result)))
    (is (= {:definition/id :runtime-profile/codex-worker
            :definition/version 2}
           (get-in result [:task-type :definition :task-type/runtime-profile-ref])))))

(deftest generate-task-type-draft-can-reuse-a-published-runtime-profile
  (let [overlay-root (temp-overlay-root)
        repository (defs.loader/filesystem-definition-repository
                    {:resource-base defs.source/default-resource-base
                     :overlay-root overlay-root})
        description "Create a repository architecture task"
        result (defs.generation/generate-task-type-draft! repository
                                                          {:generation/description description})]
    (is (= nil
           (:runtime-profile result)))
    (is (= :task-type/repo-arch
           (get-in result [:task-type :definition :task-type/id])))
    (is (= {:definition/id :runtime-profile/codex-repo-arch
            :definition/version 1}
           (get-in result [:task-type :definition :task-type/runtime-profile-ref])))
    (is (= ["Drafts remain under defs/drafts until publish."]
           (:notes result)))))

(deftest generate-task-type-draft-rejects-mock-runtime-for-repo-arch-derived-tasks
  (let [overlay-root (temp-overlay-root)
        repository (defs.loader/filesystem-definition-repository
                    {:resource-base defs.source/default-resource-base
                     :overlay-root overlay-root})]
    (testing "description-driven mock selection is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Repo-arch-derived task types cannot use the mock runtime profile"
                            (defs.generation/generate-task-type-draft!
                             repository
                             {:generation/description "Create a mock repo review task"}))))
    (testing "explicit mock runtime selection is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Repo-arch-derived task types cannot use the mock runtime profile"
                            (defs.generation/generate-task-type-draft!
                             repository
                             {:generation/description "Create a repo review task"
                              :generation/runtime-profile-template-id :runtime-profile/mock-worker}))))))
