(ns meta-flow.scheduler.shared
  (:require [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]))

(defn new-id
  []
  (str (java.util.UUID/randomUUID)))

(defn now
  []
  (sql/utc-now))

(defn lease-expires-at
  [now-value]
  (-> (java.time.Instant/parse now-value)
      (.plusSeconds 1800)
      str))

(defn run-artifact-root
  [store run]
  (when-let [artifact-id (:run/artifact-id run)]
    (when-let [artifact (store.protocol/find-artifact store artifact-id)]
      (or (:artifact/root-path artifact)
          (:artifact/location artifact)))))

(defn latest-run
  [store task-id]
  (store.protocol/find-latest-run-for-task store task-id))

(defn ensure-collection-state!
  [store defs-repo now-value]
  (or (store.protocol/find-collection-state store :collection/default)
      (let [default-policy (defs.protocol/find-resource-policy defs-repo
                                                               :resource-policy/default
                                                               1)
            initial {:collection/id :collection/default
                     :collection/dispatch {:dispatch/paused? false}
                     :collection/resource-policy-ref {:definition/id (:resource-policy/id default-policy)
                                                      :definition/version (:resource-policy/version default-policy)}
                     :collection/created-at now-value
                     :collection/updated-at now-value}]
        (store.protocol/upsert-collection-state! store initial)
        initial)))

(defn collection-policy
  [defs-repo collection-state]
  (defs.protocol/find-resource-policy defs-repo
                                      (get-in collection-state [:collection/resource-policy-ref :definition/id])
                                      (get-in collection-state [:collection/resource-policy-ref :definition/version])))
