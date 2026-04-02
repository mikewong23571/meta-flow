(ns meta-flow.control.projection.snapshot
  (:require [meta-flow.db :as db]
            [meta-flow.sql :as sql]))

(defn collection-states
  [connection]
  (mapv #(-> % :state_edn sql/text->edn)
        (sql/query-rows connection
                        "SELECT state_edn FROM collection_state ORDER BY updated_at DESC, collection_id ASC"
                        [])))

(defn dispatch-cooldown-until
  [collection-state]
  (get-in collection-state [:collection/dispatch :dispatch/cooldown-until]))

(defn cooldown-active?
  [cooldown-until now]
  (and cooldown-until
       (.isAfter (java.time.Instant/parse cooldown-until)
                 (java.time.Instant/parse now))))

(defn active-cooldowns
  [collections now]
  (->> collections
       (map dispatch-cooldown-until)
       (filter #(cooldown-active? % now))
       sort
       vec))

(defn- query-count
  [connection sql-text params]
  (long (or (:item_count (sql/query-one connection sql-text params)) 0)))

(defn runnable-task-count-query
  [connection]
  (query-count connection
               "SELECT COUNT(*) AS item_count FROM runnable_tasks_v1"
               []))

(defn awaiting-validation-run-count-query
  [connection]
  (query-count connection
               "SELECT COUNT(*) AS item_count FROM awaiting_validation_runs_v1"
               []))

(defn retryable-failed-task-count-query
  [connection]
  (query-count connection
               "SELECT COUNT(*) AS item_count FROM tasks WHERE state = ':task.state/retryable-failed'"
               []))

(defn expired-lease-run-count-query
  [connection now]
  (query-count connection
               (str "SELECT COUNT(*) AS item_count "
                    "FROM leases l "
                    "JOIN runs r ON r.run_id = l.run_id "
                    "WHERE l.state = ':lease.state/active' "
                    "AND l.lease_expires_at <= ? "
                    "AND r.state NOT IN (':run.state/awaiting-validation', ':run.state/finalized', ':run.state/retryable-failed')")
               [now]))

(defn active-run-count-query
  [connection]
  (query-count connection
               (str "SELECT COUNT(*) AS item_count FROM runs "
                    "WHERE state NOT IN (':run.state/finalized', ':run.state/retryable-failed')")
               []))

(defn active-run-count-for-resource-policy-query
  [connection resource-policy-ref]
  (query-count connection
               (str "SELECT COUNT(*) AS item_count "
                    "FROM runs r "
                    "JOIN tasks t ON t.task_id = r.task_id "
                    "WHERE r.state NOT IN (':run.state/finalized', ':run.state/retryable-failed') "
                    "AND t.resource_policy_id = ? "
                    "AND t.resource_policy_version = ?")
               [(-> resource-policy-ref :definition/id db/keyword-text)
                (:definition/version resource-policy-ref)]))
