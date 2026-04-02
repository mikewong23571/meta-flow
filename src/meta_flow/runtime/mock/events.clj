(ns meta-flow.runtime.mock.events
  (:require [meta-flow.event-ingest :as event-ingest]
            [meta-flow.events :as events]))

(defn mock-event-intent
  [run event-type idempotency-token payload now]
  {:event/run-id (:run/id run)
   :event/type event-type
   :event/payload payload
   :event/caused-by {:actor/type :runtime.adapter/mock
                     :actor/id "mock-runtime"}
   :event/idempotency-key (str "mock:" (:run/id run) ":" event-type ":" idempotency-token)
   :event/emitted-at now})

(defn emit-event!
  [store run event-type idempotency-token payload now]
  (event-ingest/ingest-run-event! store
                                  (mock-event-intent run event-type idempotency-token payload now)))

(defn cancel-exit-events
  [run now]
  [(mock-event-intent run events/run-worker-exited "worker-cancelled"
                      {:worker/exit-code 130
                       :worker/cancelled? true}
                      now)])
