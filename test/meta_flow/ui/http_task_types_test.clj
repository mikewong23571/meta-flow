(ns meta-flow.ui.http-task-types-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.store.sqlite.test-support :as store-support]
            [meta-flow.ui.http :as ui.http]
            [meta-flow.ui.http-support :as http.support]))

(deftest definition-endpoints-return-list-and-detail-json
  (let [server (ui.http/start-server! {:port 0})]
    (try
      (testing "task type list"
        (let [response (http.support/http-get (:port server) "/api/task-types")
              body (json/parse-string (:body response) true)
              items (:items body)
              repo-item (first (filter #(= "task-type/repo-arch-investigation"
                                           (:task-type/id %))
                                       items))]
          (is (= 200 (:status response)))
          (is (seq items))
          (is repo-item)
          (is (contains? repo-item :task-type/input-count))
          (is (contains? repo-item :task-type/input-labels))
          (is (not (contains? repo-item :task-type/input-schema)))
          (is (contains? repo-item :task-type/runtime-profile))
          (is (contains? repo-item :task-type/resource-policy))))
      (testing "task type create options"
        (let [response (http.support/http-get (:port server) "/api/task-types/create-options")
              body (json/parse-string (:body response) true)
              repo-item (first (filter #(= "task-type/repo-arch-investigation"
                                           (:task-type/id %))
                                       (:items body)))]
          (is (= 200 (:status response)))
          (is repo-item)
          (is (contains? repo-item :task-type/input-schema))))
      (testing "task type detail"
        (let [response (http.support/http-get (:port server)
                                              "/api/task-types/detail?task-type-id=task-type%2Frepo-arch-investigation&task-type-version=1")
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= "task-type/repo-arch-investigation" (:task-type/id body)))
          (is (= "Repo architecture investigation" (:task-type/name body)))
          (is (contains? body :task-type/task-fsm))
          (is (contains? body :task-type/run-fsm))
          (is (contains? body :task-type/validator))
          (is (contains? body :task-type/artifact-contract))))
      (testing "task type detail 404"
        (let [response (http.support/http-get (:port server)
                                              "/api/task-types/detail?task-type-id=task-type%2Fmissing&task-type-version=1")
              body (json/parse-string (:body response) true)]
          (is (= 404 (:status response)))
          (is (= "Task type not found: :task-type/missing" (:error body)))))
      (testing "task type detail coercion failure"
        (let [response (http.support/http-get (:port server)
                                              "/api/task-types/detail?task-type-id=task-type%2Frepo-arch-investigation&task-type-version=x")
              body (json/parse-string (:body response) true)]
          (is (= 400 (:status response)))
          (is (= "Request coercion failed" (:error body)))
          (is (= "reitit.coercion/request-coercion" (get-in body [:data :type])))
          (is (= "malli" (get-in body [:data :coercion])))
          (is (= ["request" "query-params"] (get-in body [:data :in])))
          (is (seq (get-in body [:data :humanized :task-type-version])))))
      (testing "runtime profile list"
        (let [response (http.support/http-get (:port server) "/api/runtime-profiles")
              body (json/parse-string (:body response) true)
              items (:items body)
              runtime-item (first (filter #(= "runtime-profile/codex-repo-arch"
                                              (:runtime-profile/id %))
                                          items))]
          (is (= 200 (:status response)))
          (is (seq items))
          (is runtime-item)
          (is (contains? runtime-item :runtime-profile/task-type-count))
          (is (contains? runtime-item :runtime-profile/artifact-contract))))
      (testing "runtime profile detail"
        (let [response (http.support/http-get (:port server)
                                              "/api/runtime-profiles/detail?runtime-profile-id=runtime-profile%2Fcodex-repo-arch&runtime-profile-version=1")
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (= "runtime-profile/codex-repo-arch" (:runtime-profile/id body)))
          (is (= "Codex repo architecture worker" (:runtime-profile/name body)))
          (is (contains? body :runtime-profile/task-types))
          (is (contains? body :runtime-profile/artifact-contract))
          (is (contains? body :runtime-profile/worker-prompt-path))))
      (testing "runtime profile detail 404"
        (let [response (http.support/http-get (:port server)
                                              "/api/runtime-profiles/detail?runtime-profile-id=runtime-profile%2Fmissing&runtime-profile-version=1")
              body (json/parse-string (:body response) true)]
          (is (= 404 (:status response)))
          (is (= "Runtime profile not found: :runtime-profile/missing" (:error body)))))
      (finally
        (ui.http/stop-server! server)))))

(deftest create-task-endpoint-rejects-missing-required-input-fields
  (let [{:keys [db-path]} (store-support/test-system)
        server (ui.http/start-server! {:db-path db-path
                                       :port 0})]
    (try
      (let [response (http.support/http-post-json (:port server)
                                                  "/api/tasks"
                                                  {:task-type-id "task-type/repo-arch-investigation"
                                                   :task-type-version 1
                                                   :input {}})
            body (json/parse-string (:body response) true)
            list-response (http.support/http-get (:port server) "/api/tasks")
            list-body (json/parse-string (:body list-response) true)]
        (is (= 400 (:status response)))
        (is (= "Required task input fields cannot be blank: :input/repo-url, :input/notify-email"
               (:error body)))
        (is (= [{:field/id "input/repo-url"
                 :field/label "Repository URL"}
                {:field/id "input/notify-email"
                 :field/label "Notification Email"}]
               (get-in body [:data :missing-fields])))
        (is (= [] (:items list-body))))
      (finally
        (ui.http/stop-server! server)))))
