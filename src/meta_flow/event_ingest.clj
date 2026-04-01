(ns meta-flow.event-ingest
  (:require [meta-flow.store.protocol :as store.protocol]))

(def ^:private required-event-intent-keys
  [:event/run-id
   :event/type
   :event/payload
   :event/caused-by
   :event/idempotency-key])

(defn- require-event-intent-key!
  [event-intent key-name]
  (let [value (get event-intent key-name ::missing)]
    (when (or (= ::missing value) (nil? value))
      (throw (ex-info (str "Missing required event key " key-name)
                      {:event-intent event-intent
                       :key key-name})))))

(defn ingest-run-event!
  [store event-intent]
  (when (contains? event-intent :event/seq)
    (throw (ex-info "Event producers must not provide :event/seq"
                    {:event-intent event-intent})))
  (doseq [key-name required-event-intent-keys]
    (require-event-intent-key! event-intent key-name))
  (store.protocol/ingest-run-event! store event-intent))
