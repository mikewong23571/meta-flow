(ns meta-flow.runtime.codex-units.worker-api-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.runtime.codex.helper :as codex.helper]
            [meta-flow.runtime.codex.worker :as codex.worker]
            [meta-flow.runtime.codex.worker-api :as codex.worker-api]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest worker-api-main-parses-options-and-dispatches-supported-commands
  (let [calls (atom [])]
    (with-redefs [store.sqlite/sqlite-state-store (fn [db-path]
                                                    {:db-path db-path})
                  codex.helper/workdir-context (fn [workdir]
                                                 {:workdir workdir
                                                  :run {:run/id "run-1"}
                                                  :task {:task/id "task-1"}})
                  codex.helper/emit-worker-started! (fn [store ctx options]
                                                      (swap! calls conj [:worker-started store ctx options]))
                  codex.helper/emit-heartbeat! (fn [store ctx options]
                                                 (swap! calls conj [:heartbeat store ctx options]))
                  codex.helper/emit-worker-exit! (fn [store ctx options]
                                                   (swap! calls conj [:worker-exit store ctx options]))
                  codex.helper/emit-artifact-ready! (fn [store ctx options]
                                                      (swap! calls conj [:artifact-ready store ctx options]))
                  codex.helper/artifact-root (fn [_ options]
                                               (str "/artifacts/" (:artifact-id options)))
                  codex.helper/artifact-id (fn [_ options]
                                             (or (:artifact-id options) "artifact-default"))
                  codex.worker/run-stub-worker! (fn [ctx db-path artifact-root artifact-id]
                                                  (swap! calls conj [:stub-worker ctx db-path artifact-root artifact-id]))
                  codex.worker/run-codex-worker! (fn [ctx db-path artifact-root artifact-id]
                                                   (swap! calls conj [:codex-worker ctx db-path artifact-root artifact-id]))]
      (codex.worker-api/-main "worker-started"
                              "--db-path" "var/test.sqlite3"
                              "--workdir" "/tmp/work"
                              "--token" "token-1"
                              "--at" "2026-04-03T00:00:00Z")
      (codex.worker-api/-main "progress"
                              "--db-path" "var/test.sqlite3"
                              "--workdir" "/tmp/work"
                              "--token" "token-2"
                              "--status" ":worker.status/running"
                              "--stage" ":worker.stage/research")
      (codex.worker-api/-main "worker-exit"
                              "--db-path" "var/test.sqlite3"
                              "--workdir" "/tmp/work"
                              "--token" "token-3"
                              "--exit-code" "7"
                              "--cancelled" "true")
      (codex.worker-api/-main "artifact-ready"
                              "--db-path" "var/test.sqlite3"
                              "--workdir" "/tmp/work"
                              "--token" "token-4"
                              "--artifact-id" "artifact-4"
                              "--artifact-root" "/tmp/artifacts/run-1")
      (codex.worker-api/-main "stub-worker"
                              "--db-path" "var/test.sqlite3"
                              "--workdir" "/tmp/work"
                              "--artifact-id" "artifact-5")
      (codex.worker-api/-main "codex-worker"
                              "--db-path" "var/test.sqlite3"
                              "--workdir" "/tmp/work"
                              "--artifact-id" "artifact-6")
      (is (= [[:worker-started {:db-path "var/test.sqlite3"} {:workdir "/tmp/work"
                                                              :run {:run/id "run-1"}
                                                              :task {:task/id "task-1"}}
               {:token "token-1"
                :at "2026-04-03T00:00:00Z"}]
              [:heartbeat {:db-path "var/test.sqlite3"} {:workdir "/tmp/work"
                                                         :run {:run/id "run-1"}
                                                         :task {:task/id "task-1"}}
               {:token "token-2"
                :at nil
                :status ":worker.status/running"
                :stage ":worker.stage/research"
                :message nil}]
              [:worker-exit {:db-path "var/test.sqlite3"} {:workdir "/tmp/work"
                                                           :run {:run/id "run-1"}
                                                           :task {:task/id "task-1"}}
               {:token "token-3"
                :at nil
                :exit-code 7
                :cancelled true}]
              [:artifact-ready {:db-path "var/test.sqlite3"} {:workdir "/tmp/work"
                                                              :run {:run/id "run-1"}
                                                              :task {:task/id "task-1"}}
               {:token "token-4"
                :at nil
                :artifact-id "artifact-4"
                :artifact-root "/tmp/artifacts/run-1"}]
              [:stub-worker {:workdir "/tmp/work"
                             :run {:run/id "run-1"}
                             :task {:task/id "task-1"}}
               "var/test.sqlite3"
               "/artifacts/artifact-5"
               "artifact-5"]
              [:codex-worker {:workdir "/tmp/work"
                              :run {:run/id "run-1"}
                              :task {:task/id "task-1"}}
               "var/test.sqlite3"
               "/artifacts/artifact-6"
               "artifact-6"]]
             @calls))))
  (testing "command parsing rejects malformed or incomplete argv"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Expected option starting with --"
                          (codex.worker-api/-main "worker-started" "db-path" "var/test.sqlite3")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing option value"
                          (codex.worker-api/-main "worker-started" "--db-path")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing required option --token"
                          (codex.worker-api/-main "worker-started"
                                                  "--db-path" "var/test.sqlite3"
                                                  "--workdir" "/tmp/work")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unsupported worker API command"
                          (codex.worker-api/-main "unsupported")))))
