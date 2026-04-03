(ns meta-flow.runtime.codex.events
  (:require [meta-flow.control.event-ingest :as event-ingest]))

(defn codex-event-intent
  [run event-type idempotency-token payload now]
  {:event/run-id (:run/id run)
   :event/type event-type
   :event/payload payload
   :event/caused-by {:actor/type :runtime.adapter/codex
                     :actor/id "codex-runtime"}
   :event/idempotency-key (str "codex:" (:run/id run) ":" event-type ":" idempotency-token)
   :event/emitted-at now})

(defn emit-event!
  [store run event-type idempotency-token payload now]
  (event-ingest/ingest-run-event! store
                                  (codex-event-intent run event-type idempotency-token payload now)))
