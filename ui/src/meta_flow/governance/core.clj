(ns meta-flow.governance.core)

(def blocked-statuses
  #{:error})

(def warning-statuses
  #{:warning})

(def gate-statuses
  #{:pass :warning :error :skipped})

(defn normalize-gate
  [entry gate]
  (let [entry-id (:id entry)
        gate-id (or (:gate gate) entry-id)
        label (or (:label gate)
                  (:label entry)
                  (some-> gate-id name))
        status (:status gate)]
    (when-not (contains? gate-statuses status)
      (throw (ex-info (str "Unsupported governance gate status: " status)
                      {:entry entry
                       :gate gate})))
    (merge {:entry/id entry-id
            :gate gate-id
            :label label}
           gate)))

(defn blocked?
  [gates]
  (boolean
   (some #(contains? blocked-statuses (:status %)) gates)))

(defn overall-status
  [gates]
  (cond
    (blocked? gates) :blocked
    (some #(contains? warning-statuses (:status %)) gates) :warning
    :else :pass))

(defn exit-code
  [gates]
  (when (blocked? gates)
    1))
