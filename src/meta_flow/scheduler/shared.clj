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
  [now-value lease-duration-seconds]
  (-> (java.time.Instant/parse now-value)
      (.plusSeconds (long lease-duration-seconds))
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

(defn workflow-default-resource-policy-ref
  [defs-repo]
  (get-in (defs.protocol/load-workflow-defs defs-repo)
          [:workflow :workflow/default-resource-policy-ref]))

(defn ensure-collection-state!
  [store defs-repo now-value]
  (or (store.protocol/find-collection-state store :collection/default)
      (let [default-policy-ref (or (workflow-default-resource-policy-ref defs-repo)
                                   (throw (ex-info "Workflow default resource policy ref is missing"
                                                   {:workflow (defs.protocol/load-workflow-defs defs-repo)})))
            default-policy (defs.protocol/find-resource-policy defs-repo
                                                               (:definition/id default-policy-ref)
                                                               (:definition/version default-policy-ref))
            initial {:collection/id :collection/default
                     :collection/dispatch {:dispatch/paused? false
                                           :dispatch/cooldown-until nil}
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

(defn task-policy
  [defs-repo task]
  (defs.protocol/find-resource-policy defs-repo
                                      (get-in task [:task/resource-policy-ref :definition/id])
                                      (get-in task [:task/resource-policy-ref :definition/version])))

(defn task-heartbeat-timeout-seconds
  [defs-repo task]
  (:resource-policy/heartbeat-timeout-seconds (task-policy defs-repo task)))

(defn task-lease-duration-seconds
  [defs-repo task]
  (:resource-policy/lease-duration-seconds (task-policy defs-repo task)))
