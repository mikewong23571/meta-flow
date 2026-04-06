(ns meta-flow.ui.http.defs-authoring-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.ui.http-support :as http.support]
            [meta-flow.ui.http.defs-support :as defs.support]))

(deftest defs-authoring-endpoints-round-trip-runtime-profiles-and-task-types
  (let [{:keys [server] :as test-env} (defs.support/start-test-server!)]
    (try
      (testing "contract and templates expose the clone-first authoring surface"
        (let [contract-response (http.support/http-get (:port server) "/api/defs/contract")
              contract-body (defs.support/json-body contract-response)
              runtime-templates-response (http.support/http-get (:port server) "/api/defs/runtime-profiles/templates")
              runtime-templates-body (defs.support/json-body runtime-templates-response)
              task-templates-response (http.support/http-get (:port server) "/api/defs/task-types/templates")
              task-templates-body (defs.support/json-body task-templates-response)]
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
              validate-body (defs.support/json-body validate-response)
              create-response (http.support/http-post-json (:port server)
                                                           "/api/defs/runtime-profiles/drafts"
                                                           request-body)
              create-body (defs.support/json-body create-response)
              list-response (http.support/http-get (:port server) "/api/defs/runtime-profiles/drafts")
              list-body (defs.support/json-body list-response)
              detail-response (http.support/http-get (:port server)
                                                     "/api/defs/runtime-profiles/drafts/detail?definition-id=runtime-profile%2Frepo-review&definition-version=1")
              detail-body (defs.support/json-body detail-response)
              publish-response (http.support/http-post-json (:port server)
                                                            "/api/defs/runtime-profiles/drafts/publish"
                                                            {:definition/id "runtime-profile/repo-review"
                                                             :definition/version 1})
              publish-body (defs.support/json-body publish-response)
              live-detail-response (http.support/http-get (:port server)
                                                          "/api/runtime-profiles/detail?runtime-profile-id=runtime-profile%2Frepo-review&runtime-profile-version=1")
              live-detail-body (defs.support/json-body live-detail-response)]
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
              validate-body (defs.support/json-body validate-response)
              create-response (http.support/http-post-json (:port server)
                                                           "/api/defs/task-types/drafts"
                                                           request-body)
              create-body (defs.support/json-body create-response)
              list-response (http.support/http-get (:port server) "/api/defs/task-types/drafts")
              list-body (defs.support/json-body list-response)
              detail-response (http.support/http-get (:port server)
                                                     "/api/defs/task-types/drafts/detail?definition-id=task-type%2Frepo-review&definition-version=1")
              detail-body (defs.support/json-body detail-response)
              publish-response (http.support/http-post-json (:port server)
                                                            "/api/defs/task-types/drafts/publish"
                                                            {:definition/id "task-type/repo-review"
                                                             :definition/version 1})
              publish-body (defs.support/json-body publish-response)
              live-detail-response (http.support/http-get (:port server)
                                                          "/api/task-types/detail?task-type-id=task-type%2Frepo-review&task-type-version=1")
              live-detail-body (defs.support/json-body live-detail-response)
              reload-response (http.support/http-post-json (:port server) "/api/defs/reload" {})
              reload-body (defs.support/json-body reload-response)]
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
        (defs.support/stop-test-server! test-env)))))
