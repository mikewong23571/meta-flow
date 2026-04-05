(ns meta-flow.ui.http-task-types-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.store.sqlite.test-support :as store-support]
            [meta-flow.ui.http :as ui.http]
            [meta-flow.ui.http-support :as http.support]))

(deftest task-type-endpoints-return-list-and-detail-json
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
