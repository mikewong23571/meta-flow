(ns meta-flow.ui.http.middleware-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.ui.http.middleware :as middleware])
  (:import (java.io ByteArrayInputStream)))

(defn- input-stream
  [text]
  (ByteArrayInputStream. (.getBytes text "UTF-8")))

(defn- json-body
  [response]
  (json/parse-string (:body response) true))

(deftest wrap-app-parses-json-requests-and-serializes-json-responses
  (let [captured-request (atom nil)
        handler (middleware/wrap-app
                 (fn [request]
                   (reset! captured-request request)
                   {:status 201
                    :body {:ok true
                           :body-params (:body-params request)}}))
        response (handler {:request-method :post
                           :uri "/api/example"
                           :headers {"content-type" "application/json"}
                           :body (input-stream "{\"repo\":\"meta-flow\"}")})]
    (is (= 201 (:status response)))
    (is (= {:repo "meta-flow"}
           (:body-params @captured-request)))
    (is (= {:ok true
            :body-params {:repo "meta-flow"}}
           (json-body response)))
    (is (= "application/json; charset=utf-8"
           (get-in response [:headers "Content-Type"])))
    (is (= "*"
           (get-in response [:headers "Access-Control-Allow-Origin"])))
    (is (string? (get-in response [:headers "X-Request-Id"])))))

(deftest wrap-app-handles-options-preflight-and-malformed-json
  (let [handler (middleware/wrap-app
                 (fn [_]
                   {:status 200
                    :body {:ok true}}))]
    (testing "options requests short-circuit with cors headers"
      (let [response (handler {:request-method :options
                               :uri "/api/example"
                               :headers {}})]
        (is (= 204 (:status response)))
        (is (= "*"
               (get-in response [:headers "Access-Control-Allow-Origin"])))
        (is (= "GET, POST, OPTIONS"
               (get-in response [:headers "Access-Control-Allow-Methods"])))
        (is (string? (get-in response [:headers "X-Request-Id"])))))
    (testing "malformed json requests return a 400 response"
      (let [response (handler {:request-method :post
                               :uri "/api/example"
                               :headers {"content-type" "application/json"}
                               :body (input-stream "{\"repo\"")})
            body (json-body response)]
        (is (= 400 (:status response)))
        (is (= "Malformed JSON request body" (:error body)))
        (is (= "request/invalid-json"
               (get-in body [:data :error/type])))
        (is (= "*"
               (get-in response [:headers "Access-Control-Allow-Origin"])))
        (is (string? (get-in response [:headers "X-Request-Id"])))))))

(deftest wrap-app-maps-domain-errors-to-status-codes
  (testing "exception-info messages map to their domain status"
    (let [handler (middleware/wrap-app
                   (fn [_]
                     (throw (ex-info "Cannot publish runtime profile :runtime-profile/repo-review version 1 because that id/version already exists in active definitions"
                                     {:definition/id :runtime-profile/repo-review}))))
          response (handler {:request-method :post
                             :uri "/api/defs/runtime-profiles/drafts/publish"
                             :headers {}
                             :body (input-stream "")})
          body (json-body response)]
      (is (= 409 (:status response)))
      (is (= "Cannot publish runtime profile :runtime-profile/repo-review version 1 because that id/version already exists in active definitions"
             (:error body)))
      (is (= "runtime-profile/repo-review"
             (get-in body [:data :definition/id])))))
  (testing "non-ex-info failures fall back to a 500 response"
    (let [handler (middleware/wrap-app
                   (fn [_]
                     (throw (RuntimeException. "boom"))))
          response (handler {:request-method :get
                             :uri "/api/example"
                             :headers {}})
          body (json-body response)]
      (is (= 500 (:status response)))
      (is (= "boom" (:error body)))
      (is (nil? (:data body))))))
