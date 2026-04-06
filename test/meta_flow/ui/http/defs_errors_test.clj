(ns meta-flow.ui.http.defs-errors-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.ui.http-support :as http.support]
            [meta-flow.ui.http.defs-support :as defs.support]))

(deftest defs-authoring-endpoints-return-400-404-and-409-for-common-errors
  (let [{:keys [overlay-root server] :as test-env} (defs.support/start-test-server!)]
    (try
      (testing "request coercion failures return 400"
        (let [response (http.support/http-post-json (:port server)
                                                    "/api/defs/runtime-profiles/drafts/validate"
                                                    {:authoring/from-id "runtime-profile/codex-worker"
                                                     :authoring/new-id "runtime-profile/repo-review"
                                                     :authoring/new-name "Broken runtime profile"
                                                     :authoring/new-version "x"})
              body (defs.support/json-body response)]
          (is (= 400 (:status response)))
          (is (= "Request coercion failed" (:error body)))))
      (testing "authoring validation failures return 400"
        (let [response (http.support/http-post-json (:port server)
                                                    "/api/defs/runtime-profiles/drafts/validate"
                                                    {:authoring/from-id "runtime-profile/codex-worker"
                                                     :authoring/new-id "runtime-profile/codex-worker"
                                                     :authoring/new-name "Same runtime profile"})
              body (defs.support/json-body response)]
          (is (= 400 (:status response)))
          (is (= "Draft request must change id or version from the source template"
                 (:error body)))))
      (testing "invalid generation requests return 400"
        (let [response (http.support/http-post-json (:port server)
                                                    "/api/defs/task-types/generate"
                                                    {:generation/description "Create a mock repo review task"})
              body (defs.support/json-body response)]
          (is (= 400 (:status response)))
          (is (= "Repo-arch-derived task types cannot use the mock runtime profile"
                 (:error body)))))
      (testing "task-type draft creation rejects unpublished runtime-profile refs with 409"
        (let [response (http.support/http-post-json (:port server)
                                                    "/api/defs/task-types/drafts"
                                                    {:authoring/from-id "task-type/repo-arch-investigation"
                                                     :authoring/new-id "task-type/repo-review"
                                                     :authoring/new-name "Repo review"
                                                     :authoring/overrides {:task-type/runtime-profile-ref
                                                                           {:definition/id "runtime-profile/unpublished"
                                                                            :definition/version 1}}})
              body (defs.support/json-body response)]
          (is (= 409 (:status response)))
          (is (= "Task-type draft requests may only reference published runtime profiles. Publish the runtime-profile draft into defs/runtime-profiles.edn before creating or validating the task-type draft."
                 (:error body)))))
      (testing "missing draft detail returns 404"
        (let [response (http.support/http-get (:port server)
                                              "/api/defs/runtime-profiles/drafts/detail?definition-id=runtime-profile%2Fmissing&definition-version=1")
              body (defs.support/json-body response)]
          (is (= 404 (:status response)))
          (is (= (str "Draft runtime profile not found at "
                      overlay-root
                      "/drafts/runtime-profiles/runtime-profile_missing_v1.edn")
                 (:error body)))))
      (testing "create after publish and duplicate publish return 409"
        (let [request-body {:authoring/from-id "runtime-profile/codex-worker"
                            :authoring/new-id "runtime-profile/repo-review"
                            :authoring/new-name "Codex repo review worker"}
              initial-create-response (http.support/http-post-json (:port server)
                                                                   "/api/defs/runtime-profiles/drafts"
                                                                   request-body)
              first-publish-response (http.support/http-post-json (:port server)
                                                                  "/api/defs/runtime-profiles/drafts/publish"
                                                                  {:definition/id "runtime-profile/repo-review"
                                                                   :definition/version 1})
              create-after-publish-response (http.support/http-post-json (:port server)
                                                                         "/api/defs/runtime-profiles/drafts"
                                                                         request-body)
              create-after-publish-body (defs.support/json-body create-after-publish-response)
              duplicate-publish-response (http.support/http-post-json (:port server)
                                                                      "/api/defs/runtime-profiles/drafts/publish"
                                                                      {:definition/id "runtime-profile/repo-review"
                                                                       :definition/version 1})
              duplicate-publish-body (defs.support/json-body duplicate-publish-response)]
          (is (= 201 (:status initial-create-response)))
          (is (= 200 (:status first-publish-response)))
          (is (= 409 (:status create-after-publish-response)))
          (is (= "Cannot create draft for runtime profile :runtime-profile/repo-review version 1 because that id/version already exists in active definitions"
                 (:error create-after-publish-body)))
          (is (= 409 (:status duplicate-publish-response)))
          (is (= "Cannot publish runtime profile :runtime-profile/repo-review version 1 because that id/version already exists in active definitions"
                 (:error duplicate-publish-body)))))
      (finally
        (defs.support/stop-test-server! test-env)))))
