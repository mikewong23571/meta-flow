(ns meta-flow.governance.runner
  (:require [meta-flow.governance.core :as core]))

(defn default-crash-gate
  [entry throwable]
  {:gate (:id entry)
   :label (or (:label entry)
              (some-> (:id entry) name)
              "governance-entry")
   :status :error
   :headline (or (:crash/headline entry)
                 (str "governance entry `"
                      (or (:label entry)
                          (some-> (:id entry) name)
                          "unknown")
                      "` failed before producing a result"))
   :cause (.getMessage throwable)
   :action (or (:crash/action entry)
               "Recover the governance entry before trusting the repository gate.")})

(defn emergency-crash-gate
  [entry throwable]
  {:entry/id (:id entry)
   :gate (or (:id entry) :governance-entry)
   :label (or (:label entry)
              (some-> (:id entry) name)
              "governance-entry")
   :status :error
   :headline "governance entry failed before producing a recoverable error gate"
   :cause (str (class throwable)
               ": "
               (or (.getMessage throwable) "no error message"))
   :action "Recover the governance runner before trusting the repository gate."})

(defn- coerce-gates
  [entry result]
  (cond
    (map? result)
    [(core/normalize-gate entry result)]

    (sequential? result)
    (mapv #(core/normalize-gate entry %) result)

    :else
    (throw (ex-info "Governance entry returned an unsupported result shape"
                    {:entry entry
                     :result result}))))

(defn run-entry!
  [entry]
  (try
    (coerce-gates entry ((:run entry)))
    (catch Throwable throwable
      [(core/normalize-gate entry (default-crash-gate entry throwable))])))

(defn run-entries!
  [entries]
  (->> entries
       (mapv (fn [entry]
               (future
                 (try
                   (run-entry! entry)
                   (catch Throwable throwable
                     [(emergency-crash-gate entry throwable)])))))
       (mapcat deref)
       vec))
