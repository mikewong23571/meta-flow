(ns meta-flow.ui.http.defs-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.ui.http :as ui.http]
            [meta-flow.ui.http-support :as http.support]))

(defn- temp-overlay-root
  []
  (.getPath
   (.toFile
    (java.nio.file.Files/createTempDirectory "meta-flow-http-defs"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn- json-body
  [response]
  (json/parse-string (:body response) true))

(deftest defs-authoring-endpoints-round-trip-runtime-profiles-and-task-types
  (let [overlay-root (temp-overlay-root)
        defs-repo (defs.loader/filesystem-definition-repository
                   {:resource-base "meta_flow/defs"
                    :overlay-root overlay-root})
        server (ui.http/start-server! {:defs-repo defs-repo
                                       :port 0})]
    (try
      (testing "contract and templates expose the clone-first authoring surface"
        (let [contract-response (http.support/http-get (:port server) "/api/defs/contract")
              contract-body (json-body contract-response)
              runtime-templates-response (http.support/http-get (:port server) "/api/defs/runtime-profiles/templates")
              runtime-templates-body (json-body runtime-templates-response)
              task-templates-response (http.support/http-get (:port server) "/api/defs/task-types/templates")
              task-templates-body (json-body task-templates-response)]
          (is (= 200 (:status contract-response)))
          (is (= "authoring/overrides"
                 (get-in contract-body [:runtime-profile :request-shape :optional 2])))
          (is (= 200 (:status runtime-templates-response)))
          (is (some #(= "runtime-profile/codex-worker" (:definition/id %))
                    (:templates runtime-templates-body)))
          (is (= 200 (:status task-templates-response)))
          (is (some #(= "task-type/repo-arch-investigation" (:definition/id %))
                    (:templates task-templates-body)))))
      (testing "runtime-profile draft validate, create, inspect, publish, and reload work through HTTP"
        (let [request-body {:authoring/from-id "runtime-profile/codex-worker"
                            :authoring/new-id "runtime-profile/repo-review"
                            :authoring/new-name "Codex repo review worker"
                            :authoring/overrides {:runtime-profile/web-search-enabled? false
                                                  :runtime-profile/worker-prompt-path "meta_flow/prompts/worker.md"}}
              validate-response (http.support/http-post-json (:port server)
                                                             "/api/defs/runtime-profiles/drafts/validate"
                                                             request-body)
              validate-body (json-body validate-response)
              create-response (http.support/http-post-json (:port server)
                                                           "/api/defs/runtime-profiles/drafts"
                                                           request-body)
              create-body (json-body create-response)
              list-response (http.support/http-get (:port server) "/api/defs/runtime-profiles/drafts")
              list-body (json-body list-response)
              detail-response (http.support/http-get (:port server)
                                                     "/api/defs/runtime-profiles/drafts/detail?definition-id=runtime-profile%2Frepo-review&definition-version=1")
              detail-body (json-body detail-response)
              publish-response (http.support/http-post-json (:port server)
                                                            "/api/defs/runtime-profiles/drafts/publish"
                                                            {:definition/id "runtime-profile/repo-review"
                                                             :definition/version 1})
              publish-body (json-body publish-response)
              live-detail-response (http.support/http-get (:port server)
                                                          "/api/runtime-profiles/detail?runtime-profile-id=runtime-profile%2Frepo-review&runtime-profile-version=1")
              live-detail-body (json-body live-detail-response)]
          (is (= 200 (:status validate-response)))
          (is (= "runtime-profile/codex-worker"
                 (get-in validate-body [:template :definition/id])))
          (is (= 1
                 (get-in validate-body [:request :authoring/from-version])))
          (is (= 201 (:status create-response)))
          (is (= "runtime-profile/repo-review"
                 (get-in create-body [:definition :runtime-profile/id])))
          (is (= 200 (:status list-response)))
          (is (some #(= "runtime-profile/repo-review" (:definition/id %))
                    (:items list-body)))
          (is (= 200 (:status detail-response)))
          (is (= "runtime-profile/repo-review"
                 (get-in detail-body [:definition :runtime-profile/id])))
          (is (= 200 (:status publish-response)))
          (is (= "ok" (get-in publish-body [:reload :status])))
          (is (= 200 (:status live-detail-response)))
          (is (= "Codex repo review worker" (:runtime-profile/name live-detail-body)))))
      (testing "task-type draft validate, create, inspect, publish, and reload can reference the published runtime profile"
        (let [request-body {:authoring/from-id "task-type/repo-arch-investigation"
                            :authoring/new-id "task-type/repo-review"
                            :authoring/new-name "Repo review"
                            :authoring/overrides {:task-type/runtime-profile-ref
                                                  {:definition/id "runtime-profile/repo-review"
                                                   :definition/version 1}}}
              validate-response (http.support/http-post-json (:port server)
                                                             "/api/defs/task-types/drafts/validate"
                                                             request-body)
              validate-body (json-body validate-response)
              create-response (http.support/http-post-json (:port server)
                                                           "/api/defs/task-types/drafts"
                                                           request-body)
              create-body (json-body create-response)
              list-response (http.support/http-get (:port server) "/api/defs/task-types/drafts")
              list-body (json-body list-response)
              detail-response (http.support/http-get (:port server)
                                                     "/api/defs/task-types/drafts/detail?definition-id=task-type%2Frepo-review&definition-version=1")
              detail-body (json-body detail-response)
              publish-response (http.support/http-post-json (:port server)
                                                            "/api/defs/task-types/drafts/publish"
                                                            {:definition/id "task-type/repo-review"
                                                             :definition/version 1})
              publish-body (json-body publish-response)
              live-detail-response (http.support/http-get (:port server)
                                                          "/api/task-types/detail?task-type-id=task-type%2Frepo-review&task-type-version=1")
              live-detail-body (json-body live-detail-response)
              reload-response (http.support/http-post-json (:port server) "/api/defs/reload" {})
              reload-body (json-body reload-response)]
          (is (= 200 (:status validate-response)))
          (is (= "task-type/repo-arch-investigation"
                 (get-in validate-body [:template :definition/id])))
          (is (= "runtime-profile/repo-review"
                 (get-in validate-body [:request :authoring/overrides :task-type/runtime-profile-ref :definition/id])))
          (is (= 201 (:status create-response)))
          (is (= "task-type/repo-review"
                 (get-in create-body [:definition :task-type/id])))
          (is (= 200 (:status list-response)))
          (is (some #(= "task-type/repo-review" (:definition/id %))
                    (:items list-body)))
          (is (= 200 (:status detail-response)))
          (is (= "task-type/repo-review"
                 (get-in detail-body [:definition :task-type/id])))
          (is (= 200 (:status publish-response)))
          (is (= "ok" (get-in publish-body [:reload :status])))
          (is (= 200 (:status live-detail-response)))
          (is (= "runtime-profile/repo-review"
                 (get-in live-detail-body [:task-type/runtime-profile :definition/id])))
          (is (= 200 (:status reload-response)))
          (is (= "ok" (:status reload-body)))))
      (finally
        (ui.http/stop-server! server)))))

(deftest defs-authoring-endpoints-return-400-404-and-409-for-common-errors
  (let [overlay-root (temp-overlay-root)
        defs-repo (defs.loader/filesystem-definition-repository
                   {:resource-base "meta_flow/defs"
                    :overlay-root overlay-root})
        server (ui.http/start-server! {:defs-repo defs-repo
                                       :port 0})]
    (try
      (testing "request coercion failures return 400"
        (let [response (http.support/http-post-json (:port server)
                                                    "/api/defs/runtime-profiles/drafts/validate"
                                                    {:authoring/from-id "runtime-profile/codex-worker"
                                                     :authoring/new-id "runtime-profile/repo-review"
                                                     :authoring/new-name "Broken runtime profile"
                                                     :authoring/new-version "x"})
              body (json-body response)]
          (is (= 400 (:status response)))
          (is (= "Request coercion failed" (:error body)))))
      (testing "authoring validation failures return 400"
        (let [response (http.support/http-post-json (:port server)
                                                    "/api/defs/runtime-profiles/drafts/validate"
                                                    {:authoring/from-id "runtime-profile/codex-worker"
                                                     :authoring/new-id "runtime-profile/codex-worker"
                                                     :authoring/new-name "Same runtime profile"})
              body (json-body response)]
          (is (= 400 (:status response)))
          (is (= "Draft request must change id or version from the source template"
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
              body (json-body response)]
          (is (= 409 (:status response)))
          (is (= "Task-type draft requests may only reference published runtime profiles. Publish the runtime-profile draft into defs/runtime-profiles.edn before creating or validating the task-type draft."
                 (:error body)))))
      (testing "missing draft detail returns 404"
        (let [response (http.support/http-get (:port server)
                                              "/api/defs/runtime-profiles/drafts/detail?definition-id=runtime-profile%2Fmissing&definition-version=1")
              body (json-body response)]
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
              create-after-publish-body (json-body create-after-publish-response)
              duplicate-publish-response (http.support/http-post-json (:port server)
                                                                      "/api/defs/runtime-profiles/drafts/publish"
                                                                      {:definition/id "runtime-profile/repo-review"
                                                                       :definition/version 1})
              duplicate-publish-body (json-body duplicate-publish-response)]
          (is (= 201 (:status initial-create-response)))
          (is (= 200 (:status first-publish-response)))
          (is (= 409 (:status create-after-publish-response)))
          (is (= "Cannot create draft for runtime profile :runtime-profile/repo-review version 1 because that id/version already exists in active definitions"
                 (:error create-after-publish-body)))
          (is (= 409 (:status duplicate-publish-response)))
          (is (= "Cannot publish runtime profile :runtime-profile/repo-review version 1 because that id/version already exists in active definitions"
                 (:error duplicate-publish-body)))))
      (finally
        (ui.http/stop-server! server)))))
