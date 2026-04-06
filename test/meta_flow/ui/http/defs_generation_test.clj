(ns meta-flow.ui.http.defs-generation-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.ui.http-support :as http.support]
            [meta-flow.ui.http.defs-support :as defs.support]))

(deftest defs-generation-endpoint-creates-drafts-from-description
  (let [{:keys [server] :as test-env} (defs.support/start-test-server!)]
    (try
      (let [response (http.support/http-post-json (:port server)
                                                  "/api/defs/task-types/generate"
                                                  {:generation/description "Create a repo review task that uses Codex, disables web search, and emits a markdown report"})
            body (defs.support/json-body response)
            runtime-drafts (defs.support/json-body (http.support/http-get (:port server)
                                                                          "/api/defs/runtime-profiles/drafts"))
            task-drafts (defs.support/json-body (http.support/http-get (:port server)
                                                                       "/api/defs/task-types/drafts"))]
        (is (= 201 (:status response)))
        (is (= "runtime-profile/repo-review"
               (get-in body [:runtime-profile :definition :runtime-profile/id])))
        (is (= false
               (get-in body [:runtime-profile :definition :runtime-profile/web-search-enabled?])))
        (is (= "task-type/repo-review"
               (get-in body [:task-type :definition :task-type/id])))
        (is (= "runtime-profile/repo-review"
               (get-in body [:task-type :definition :task-type/runtime-profile-ref :definition/id])))
        (is (some #(= "runtime-profile/repo-review" (:definition/id %))
                  (:items runtime-drafts)))
        (is (some #(= "task-type/repo-review" (:definition/id %))
                  (:items task-drafts))))
      (finally
        (defs.support/stop-test-server! test-env)))))
