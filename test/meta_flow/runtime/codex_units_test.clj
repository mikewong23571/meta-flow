(ns meta-flow.runtime.codex-units-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.runtime.codex.events :as codex.events]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.helper :as codex.helper]
            [meta-flow.runtime.codex.worker :as codex.worker]
            [meta-flow.runtime.codex.worker-api :as codex.worker-api]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.runtime.registry :as runtime.registry]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest runtime-registry-resolves-supported-adapters-and-rejects-unknown-ids
  (is (= :runtime.adapter/mock
         (runtime.protocol/adapter-id (runtime.registry/runtime-adapter :runtime.adapter/mock))))
  (is (= :runtime.adapter/codex
         (runtime.protocol/adapter-id (runtime.registry/runtime-adapter :runtime.adapter/codex))))
  (let [exception (try
                    (runtime.registry/runtime-adapter :runtime.adapter/missing)
                    nil
                    (catch clojure.lang.ExceptionInfo ex
                      ex))]
    (is (some? exception))
    (is (= {:adapter-id :runtime.adapter/missing}
           (ex-data exception)))))

(deftest codex-event-intents-carry-stable-actor-and-idempotency-shapes
  (let [run {:run/id "run-1"}
        helper (codex.events/helper-event-intent run events/run-worker-started "token-1" {:a 1} "2026-04-03T00:00:00Z")
        poll (codex.events/poll-event-intent run events/run-worker-exited "token-2" {:b 2} "2026-04-03T00:01:00Z")
        runtime (codex.events/runtime-event-intent run events/run-artifact-ready "token-3" {:c 3} "2026-04-03T00:02:00Z")
        emitted (atom nil)]
    (is (= {:actor/type :runtime.adapter/codex
            :actor/id "codex-helper"}
           (:event/caused-by helper)))
    (is (= "codex-helper:run-1::run.event/worker-started:token-1"
           (:event/idempotency-key helper)))
    (is (= "codex-poll:run-1::run.event/worker-exited:token-2"
           (:event/idempotency-key poll)))
    (is (= "codex-runtime:run-1::run.event/artifact-ready:token-3"
           (:event/idempotency-key runtime)))
    (with-redefs [event-ingest/ingest-run-event! (fn [store event-intent]
                                                   (reset! emitted [store event-intent])
                                                   event-intent)]
      (is (= runtime
             (codex.events/emit-runtime-event! ::store
                                               run
                                               events/run-artifact-ready
                                               "token-3"
                                               {:c 3}
                                               "2026-04-03T00:02:00Z")))
      (is (= [::store runtime] @emitted)))))

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
                                                  (swap! calls conj [:stub-worker ctx db-path artifact-root artifact-id]))]
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
               "artifact-5"]]
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

(deftest codex-worker-writes-stub-artifacts-and-respects-cancel-state
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-codex-worker"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        artifact-root (str root "/artifact")
        workdir (str root "/workdir")
        ctx {:workdir workdir
             :task {:task/id "task-1"}
             :run {:run/id "run-1"}
             :artifact-contract {:artifact-contract/required-paths ["manifest.json" "notes.md" "run.log" "extra.txt"]}}]
    (codex.worker/write-stub-artifact! ctx artifact-root)
    (is (.exists (io/file artifact-root "manifest.json")))
    (is (.exists (io/file artifact-root "notes.md")))
    (is (.exists (io/file artifact-root "run.log")))
    (is (.exists (io/file artifact-root "extra.txt")))
    (is (false? (codex.worker/cancelled? workdir)))
    (codex.fs/write-json-file! (str workdir "/process.json")
                               {:status "cancel-requested"})
    (is (true? (codex.worker/cancelled? workdir)))))

(deftest codex-worker-run-stub-worker-drives-success-and-cancel-branches
  (let [ctx {:workdir "/tmp/workdir"
             :task {:task/id "task-1"}
             :run {:run/id "run-1"}
             :artifact-contract {:artifact-contract/required-paths ["manifest.json"]}}
        success-calls (atom [])
        cancel-calls (atom [])]
    (with-redefs [store.sqlite/sqlite-state-store (fn [_] ::store)
                  codex.fs/ensure-directory! (fn [path]
                                               (swap! success-calls conj [:ensure-directory path]))
                  codex.worker/write-stub-artifact! (fn [_ artifact-root]
                                                      (swap! success-calls conj [:write-artifact artifact-root]))
                  codex.worker/cancelled? (let [states (atom [false false false false])]
                                            (fn [_]
                                              (let [current (first @states)]
                                                (swap! states #(if (next %) (vec (rest %)) %))
                                                current)))
                  codex.helper/emit-worker-started! (fn [_ _ options]
                                                      (swap! success-calls conj [:worker-started options]))
                  codex.helper/emit-heartbeat! (fn [_ _ options]
                                                 (swap! success-calls conj [:heartbeat options]))
                  codex.helper/emit-worker-exit! (fn [_ _ options]
                                                   (swap! success-calls conj [:worker-exit options]))
                  codex.helper/emit-artifact-ready! (fn [_ _ options]
                                                      (swap! success-calls conj [:artifact-ready options]))]
      (codex.worker/run-stub-worker! ctx "var/test.sqlite3" "/tmp/artifacts/run-1" "artifact-1")
      (is (= [[:ensure-directory "/tmp/artifacts/run-1"]
              [:worker-started {:token "worker-started"}]
              [:heartbeat {:token "heartbeat-1"
                           :status ":worker.status/running"
                           :stage ":worker.stage/research"}]
              [:heartbeat {:token "progress-1"
                           :status ":worker.status/running"
                           :stage ":worker.stage/synthesizing"
                           :message "Writing managed artifact bundle"}]
              [:write-artifact "/tmp/artifacts/run-1"]
              [:worker-exit {:token "worker-exited"
                             :exit-code 0}]
              [:artifact-ready {:token "artifact-ready"
                                :artifact-id "artifact-1"
                                :artifact-root "/tmp/artifacts/run-1"}]]
             @success-calls)))
    (with-redefs [store.sqlite/sqlite-state-store (fn [_] ::store)
                  codex.fs/ensure-directory! (fn [path]
                                               (swap! cancel-calls conj [:ensure-directory path]))
                  codex.worker/write-stub-artifact! (fn [_ artifact-root]
                                                      (swap! cancel-calls conj [:write-artifact artifact-root]))
                  codex.worker/cancelled? (let [states (atom [true true true true])]
                                            (fn [_]
                                              (let [current (first @states)]
                                                (swap! states #(if (next %) (vec (rest %)) %))
                                                current)))
                  codex.helper/emit-worker-started! (fn [& _]
                                                      (swap! cancel-calls conj [:worker-started]))
                  codex.helper/emit-heartbeat! (fn [& _]
                                                 (swap! cancel-calls conj [:heartbeat]))
                  codex.helper/emit-worker-exit! (fn [_ _ options]
                                                   (swap! cancel-calls conj [:worker-exit options]))
                  codex.helper/emit-artifact-ready! (fn [& _]
                                                      (swap! cancel-calls conj [:artifact-ready]))]
      (codex.worker/run-stub-worker! ctx "var/test.sqlite3" "/tmp/artifacts/run-2" "artifact-2")
      (is (= [[:ensure-directory "/tmp/artifacts/run-2"]
              [:write-artifact "/tmp/artifacts/run-2"]
              [:worker-exit {:token "worker-cancelled"
                             :exit-code 130
                             :cancelled true}]]
             @cancel-calls)))))
