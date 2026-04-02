(ns meta-flow.store.sqlite.lease.core
  (:require [meta-flow.sql :as sql]
            [meta-flow.store.sqlite.shared :as shared]))

(defn insert-lease!
  [connection lease]
  (sql/execute-update! connection
                       (str "INSERT INTO leases "
                            "(lease_id, run_id, state, lease_token, lease_expires_at, lease_edn, created_at, updated_at) "
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                       [(shared/require-key! lease :lease/id)
                        (shared/require-key! lease :lease/run-id)
                        (shared/require-key! lease :lease/state)
                        (shared/require-key! lease :lease/token)
                        (shared/require-key! lease :lease/expires-at)
                        (sql/edn->text lease)
                        (:lease/created-at lease)
                        (:lease/updated-at lease)]))

(defn release-lease-via-connection!
  [connection lease-id now]
  (when-let [row (sql/query-one connection
                                "SELECT lease_edn FROM leases WHERE lease_id = ?"
                                [lease-id])]
    (let [released-lease (-> (sql/text->edn (:lease_edn row))
                             (assoc :lease/state :lease.state/released
                                    :lease/updated-at now)
                             sql/canonicalize-edn)]
      (sql/execute-update! connection
                           "UPDATE leases SET state = ?, lease_edn = ?, updated_at = ? WHERE lease_id = ?"
                           [(:lease/state released-lease)
                            (sql/edn->text released-lease)
                            (:lease/updated-at released-lease)
                            lease-id])
      released-lease)))
