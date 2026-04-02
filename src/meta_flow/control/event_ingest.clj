(ns meta-flow.control.event-ingest
  (:require [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite.shared :as store.sqlite.shared]))

(defn ingest-run-event!
  [store event-intent]
  (when (contains? event-intent :event/seq)
    (throw (ex-info "Event producers must not provide :event/seq"
                    {:event-intent event-intent})))
  (store.sqlite.shared/validate-event-intent! event-intent)
  (store.protocol/ingest-run-event! store event-intent))
