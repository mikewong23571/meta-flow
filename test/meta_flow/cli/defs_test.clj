(ns meta-flow.cli.defs-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [meta-flow.cli :as cli]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.defs.source :as defs.source]))

(defn- temp-overlay-root
  []
  (.getPath (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-cli-defs"
                                                              (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest defs-authoring-commands-create-and-publish-runtime-profiles-and-task-types
  (let [overlay-root (temp-overlay-root)]
    (with-redefs [defs.source/default-overlay-root overlay-root]
      (let [create-runtime-output (with-out-str
                                    (cli/dispatch-command!
                                     ["defs" "create-runtime-profile"
                                      "--from" "runtime-profile/codex-worker"
                                      "--new-id" "runtime-profile/repo-review"
                                      "--name" "Codex repo review worker"
                                      "--worker-prompt-path" "meta_flow/prompts/worker.md"
                                      "--web-search" "false"]))
            publish-runtime-output (with-out-str
                                     (cli/dispatch-command!
                                      ["defs" "publish-runtime-profile"
                                       "--id" "runtime-profile/repo-review"
                                       "--version" "1"]))
            create-task-output (with-out-str
                                 (cli/dispatch-command!
                                  ["defs" "create-task-type"
                                   "--from" "task-type/repo-arch-investigation"
                                   "--new-id" "task-type/repo-review"
                                   "--name" "Repo review"
                                   "--runtime-profile" "runtime-profile/repo-review"]))
            publish-task-output (with-out-str
                                  (cli/dispatch-command!
                                   ["defs" "publish-task-type"
                                    "--id" "task-type/repo-review"
                                    "--version" "1"]))
            repository (defs.loader/filesystem-definition-repository
                        {:resource-base defs.source/default-resource-base
                         :overlay-root overlay-root})]
        (is (str/includes? create-runtime-output "Wrote draft runtime profile :runtime-profile/repo-review version 1"))
        (is (str/includes? create-runtime-output "Validation: OK"))
        (is (str/includes? publish-runtime-output "Published :runtime-profile/repo-review version 1"))
        (is (str/includes? publish-runtime-output "Reloaded definitions cache"))
        (is (str/includes? create-task-output "Wrote draft task type :task-type/repo-review version 1"))
        (is (str/includes? create-task-output "Validation: OK"))
        (is (str/includes? publish-task-output "Published :task-type/repo-review version 1"))
        (is (str/includes? publish-task-output "Reloaded definitions cache"))
        (defs.loader/reload-filesystem-definition-repository! repository)
        (is (= :runtime-profile/repo-review
               (:runtime-profile/id
                (defs.protocol/find-runtime-profile repository :runtime-profile/repo-review 1))))
        (is (= {:definition/id :runtime-profile/repo-review
                :definition/version 1}
               (:task-type/runtime-profile-ref
                (defs.protocol/find-task-type-def repository :task-type/repo-review 1))))))))

(deftest defs-generate-task-type-command-creates-drafts-from-description
  (let [overlay-root (temp-overlay-root)]
    (with-redefs [defs.source/default-overlay-root overlay-root]
      (let [output (with-out-str
                     (cli/dispatch-command!
                      ["defs" "generate-task-type"
                       "--description" "Create a repo review task that uses Codex, disables web search, and emits a markdown report"]))
            runtime-draft (defs.source/load-edn-file!
                           (str overlay-root "/drafts/runtime-profiles/runtime-profile_repo-review_v1.edn"))
            task-draft (defs.source/load-edn-file!
                        (str overlay-root "/drafts/task-types/task-type_repo-review_v1.edn"))]
        (is (str/includes? output "Generated draft request from description"))
        (is (str/includes? output "Wrote draft runtime profile :runtime-profile/repo-review version 1"))
        (is (str/includes? output "Wrote draft task type :task-type/repo-review version 1"))
        (is (str/includes? output "Validation: OK"))
        (is (= false
               (:runtime-profile/web-search-enabled? runtime-draft)))
        (is (= {:definition/id :runtime-profile/repo-review
                :definition/version 1}
               (:task-type/runtime-profile-ref task-draft)))))))

(deftest defs-authoring-commands-reject-invalid-flags-and-unpublished-runtime-profiles
  (let [overlay-root (temp-overlay-root)]
    (with-redefs [defs.source/default-overlay-root overlay-root]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Option --web-search must be true or false"
                            (cli/dispatch-command!
                             ["defs" "create-runtime-profile"
                              "--from" "runtime-profile/codex-worker"
                              "--new-id" "runtime-profile/repo-review"
                              "--name" "Codex repo review worker"
                              "--web-search" "maybe"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unsupported options for defs create-task-type"
                            (cli/dispatch-command!
                             ["defs" "create-task-type"
                              "--from" "task-type/repo-arch-investigation"
                              "--new-id" "task-type/repo-review"
                              "--name" "Repo review"
                              "--bogus" "x"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Published runtime profile :runtime-profile/repo-review not found"
                            (cli/dispatch-command!
                             ["defs" "create-task-type"
                              "--from" "task-type/repo-arch-investigation"
                              "--new-id" "task-type/repo-review"
                              "--name" "Repo review"
                              "--runtime-profile" "runtime-profile/repo-review"]))))))
