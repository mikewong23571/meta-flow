(ns meta-flow.runtime.codex-units.registry-events-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.runtime.codex.events :as codex.events]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.runtime.registry :as runtime.registry]))

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
