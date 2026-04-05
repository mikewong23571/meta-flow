(ns meta-flow.ui.http-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.sql]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite.test-support :as support]
            [meta-flow.ui.http :as ui.http])
  (:import (java.io ByteArrayInputStream)
           (java.net HttpURLConnection URL)))

(def ^:private http-get-retry-count 10)

(def ^:private http-get-retry-delay-ms 50)

(defn- http-get
  [port path]
  (let [url (URL. (str "http://localhost:" port path))]
    (loop [attempt 1]
      (let [connection ^HttpURLConnection (.openConnection url)
            _ (.setRequestMethod connection "GET")
            _ (.setConnectTimeout connection 1000)
            _ (.setReadTimeout connection 1000)
            result (try
                     (let [status (.getResponseCode connection)
                           stream (or (if (>= status 400)
                                        (.getErrorStream connection)
                                        (.getInputStream connection))
                                      (ByteArrayInputStream. (byte-array 0)))
                           body (with-open [in stream]
                                  (slurp in :encoding "UTF-8"))]
                       {:status status
                        :body body})
                     (catch java.io.IOException exception
                       exception))]
        (.disconnect connection)
        (if (instance? java.io.IOException result)
          (if (< attempt http-get-retry-count)
            (do
              ;; The embedded server can briefly accept-and-close during startup under coverage instrumentation.
              (Thread/sleep http-get-retry-delay-ms)
              (recur (inc attempt)))
            (throw result))
          result)))))

(deftest scheduler-overview-endpoint-returns-projection-backed-json
  (let [{:keys [store db-path]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        future-now "2026-04-01T00:10:00Z"
        task (store.protocol/enqueue-task! store (support/task "task-timeout" "work/cve-timeout" now))]
    (store.protocol/upsert-collection-state! store (support/collection-state true now {:cooldown-until "2026-04-01T00:30:00Z"}))
    (store.protocol/create-run! store task
                                (assoc (support/run "run-timeout" 1 now)
                                       :run/heartbeat-timeout-seconds 60)
                                (support/lease "lease-timeout" "run-timeout" "2026-04-01T02:00:00Z" now))
    (store.protocol/transition-task! store "task-timeout"
                                     {:transition/from :task.state/queued
                                      :transition/to :task.state/running}
                                     "2026-04-01T00:01:00Z")
    (store.protocol/transition-run! store "run-timeout"
                                    {:transition/from :run.state/leased
                                     :transition/to :run.state/running}
                                    "2026-04-01T00:01:00Z")
    (event-ingest/ingest-run-event! store {:event/run-id "run-timeout"
                                           :event/type events/run-worker-heartbeat
                                           :event/idempotency-key "heartbeat-timeout"
                                           :event/payload {:progress/stage :stage/research}
                                           :event/caused-by {:actor/type :worker
                                                             :actor/id "mock-worker"}
                                           :event/emitted-at "2026-04-01T00:01:00Z"})
    (with-redefs [meta-flow.sql/utc-now (fn [] future-now)]
      (let [server (ui.http/start-server! {:db-path db-path
                                           :port 0})]
        (try
          (let [response (http-get (:port server) "/api/scheduler/overview")
                body (json/parse-string (:body response) true)]
            (is (= 200 (:status response)))
            (is (= future-now (get-in body [:snapshot :now])))
            (is (true? (get-in body [:snapshot :dispatch-paused?])))
            (is (= "2026-04-01T00:30:00Z" (get-in body [:snapshot :dispatch-cooldown-until])))
            (is (= 1 (get-in body [:snapshot :active-run-count])))
            (is (= 1 (get-in body [:snapshot :heartbeat-timeout-count])))
            (is (= ["run-timeout"] (get-in body [:snapshot :heartbeat-timeout-run-ids])))
            (is (= {:definition/id "resource-policy/default"
                    :definition/version 3}
                   (get-in body [:collection :resource-policy-ref]))))
          (finally
            (ui.http/stop-server! server)))))))

(deftest task-and-run-endpoints-return-detail-json-and-404s
  (let [{:keys [store db-path]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        task (store.protocol/enqueue-task! store (support/task "task-42" "work/cve-42" now))]
    (store.protocol/create-run! store task
                                (support/run "run-42" 1 now)
                                (support/lease "lease-42" "run-42" "2026-04-01T02:00:00Z" now))
    (let [server (ui.http/start-server! {:db-path db-path
                                         :port 0})]
      (try
        (testing "task detail"
          (let [response (http-get (:port server) "/api/tasks/task-42")
                body (json/parse-string (:body response) true)]
            (is (= 200 (:status response)))
            (is (= "task-42" (:task/id body)))
            (is (= "work/cve-42" (:task/work-key body)))))
        (testing "run detail"
          (let [response (http-get (:port server) "/api/runs/run-42")
                body (json/parse-string (:body response) true)]
            (is (= 200 (:status response)))
            (is (= "run-42" (:run/id body)))
            (is (= 0 (:run/event-count body)))))
        (testing "404"
          (let [response (http-get (:port server) "/api/tasks/missing-task")
                body (json/parse-string (:body response) true)]
            (is (= 404 (:status response)))
            (is (= "Task not found: missing-task" (:error body)))))
        (finally
          (ui.http/stop-server! server))))))

(deftest task-list-endpoint-returns-unified-items-with-type-specific-summary
  (let [{:keys [store db-path]} (support/test-system)
        now "2026-04-01T00:00:00Z"
        repo-task (store.protocol/enqueue-task! store
                                                (assoc (support/task "task-repo" "repo-arch-key" now)
                                                       :task/task-type-ref {:definition/id :task-type/repo-arch-investigation
                                                                            :definition/version 1}
                                                       :task/input {:input/repo-url "github.com/acme/project"
                                                                    :input/notify-email "ops@example.com"}
                                                       :task/updated-at "2026-04-01T00:02:00Z"))
        _ (store.protocol/enqueue-task! store
                                        (assoc (support/task "task-cve" "CVE-2024-12345" now)
                                               :task/task-type-ref {:definition/id :task-type/cve-investigation
                                                                    :definition/version 1}
                                               :task/updated-at "2026-04-01T00:01:00Z"))]
    (store.protocol/create-run! store repo-task
                                (assoc (support/run "run-repo-1" 1 now)
                                       :run/state :run.state/running
                                       :run/updated-at "2026-04-01T00:03:00Z")
                                (support/lease "lease-repo-1" "run-repo-1" "2026-04-01T02:00:00Z" now))
    (let [server (ui.http/start-server! {:db-path db-path
                                         :port 0})]
      (try
        (let [response (http-get (:port server) "/api/tasks")
              body (json/parse-string (:body response) true)
              items (:items body)
              first-item (first items)
              second-item (second items)]
          (is (= 200 (:status response)))
          (is (= 2 (count items)))
          (is (= "task-repo" (:task/id first-item)))
          (is (= "Repo architecture investigation" (:task/type-name first-item)))
          (is (= {:primary "github.com/acme/project"
                  :secondary "ops@example.com"}
                 (:task/summary first-item)))
          (is (= {:run/id "run-repo-1"
                  :run/state "run.state/running"
                  :run/attempt 1
                  :run/updated-at "2026-04-01T00:03:00Z"}
                 (:latest-run first-item)))
          (is (= "task-cve" (:task/id second-item)))
          (is (= "CVE investigation" (:task/type-name second-item)))
          (is (= {:primary "CVE-2024-12345"
                  :secondary "CVE investigation"}
                 (:task/summary second-item))))
        (finally
          (ui.http/stop-server! server))))))
