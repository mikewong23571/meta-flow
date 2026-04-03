(ns meta-flow.runtime.codex.events
  (:require [meta-flow.control.event-ingest :as event-ingest]))

(defn codex-event-intent
  [run actor-id prefix event-type idempotency-token payload now]
  {:event/run-id (:run/id run)
   :event/type event-type
   :event/payload payload
   :event/caused-by {:actor/type :runtime.adapter/codex
                     :actor/id actor-id}
   :event/idempotency-key (str prefix ":" (:run/id run) ":" event-type ":" idempotency-token)
   :event/emitted-at now})

(defn helper-event-intent
  [run event-type idempotency-token payload now]
  (codex-event-intent run
                      "codex-helper"
                      "codex-helper"
                      event-type
                      idempotency-token
                      payload
                      now))

(defn poll-event-intent
  [run event-type idempotency-token payload now]
  (codex-event-intent run
                      "codex-poller"
                      "codex-poll"
                      event-type
                      idempotency-token
                      payload
                      now))

(defn runtime-event-intent
  [run event-type idempotency-token payload now]
  (codex-event-intent run
                      "codex-runtime"
                      "codex-runtime"
                      event-type
                      idempotency-token
                      payload
                      now))

(defn emit-runtime-event!
  [store run event-type idempotency-token payload now]
  (event-ingest/ingest-run-event! store
                                  (runtime-event-intent run
                                                        event-type
                                                        idempotency-token
                                                        payload
                                                        now)))
