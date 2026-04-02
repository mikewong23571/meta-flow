(ns meta-flow.scheduler.test-support
  (:require [clojure.java.io :as io]
            [meta-flow.control.event-ingest :as event-ingest]
            [meta-flow.control.events :as events]
            [meta-flow.db :as db]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.scheduler :as scheduler]
            [meta-flow.sql :as sql]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn temp-system
  []
  (let [temp-dir (java.nio.file.Files/createTempDirectory "meta-flow-scheduler-test"
                                                          (make-array java.nio.file.attribute.FileAttribute 0))
        root (.toFile temp-dir)
        db-path (str root "/meta-flow.sqlite3")
        artifacts-dir (str root "/artifacts")
        runs-dir (str root "/runs")]
    (.mkdirs (io/file artifacts-dir))
    (.mkdirs (io/file runs-dir))
    (db/initialize-database! db-path)
    {:db-path db-path
     :artifacts-dir artifacts-dir
     :runs-dir runs-dir}))

(defn query-one
  [db-path sql-text params]
  (sql/with-connection db-path
    (fn [connection]
      (sql/query-one connection sql-text params))))

(defn enqueue-demo-task!
  [db-path]
  (let [store (store.sqlite/sqlite-state-store db-path)
        defs-repo (defs.loader/filesystem-definition-repository)
        task-type (defs.protocol/find-task-type-def defs-repo :task-type/cve-investigation 1)
        now (sql/utc-now)]
    (store.protocol/enqueue-task! store
                                  {:task/id (str "task-" (java.util.UUID/randomUUID))
                                   :task/work-key (str "CVE-2024-12345-" (subs (str (java.util.UUID/randomUUID)) 0 8))
                                   :task/task-type-ref {:definition/id (:task-type/id task-type)
                                                        :definition/version (:task-type/version task-type)}
                                   :task/task-fsm-ref (:task-type/task-fsm-ref task-type)
                                   :task/run-fsm-ref (:task-type/run-fsm-ref task-type)
                                   :task/runtime-profile-ref {:definition/id :runtime-profile/mock-worker
                                                              :definition/version 1}
                                   :task/artifact-contract-ref (:task-type/artifact-contract-ref task-type)
                                   :task/validator-ref (:task-type/validator-ref task-type)
                                   :task/resource-policy-ref (:task-type/resource-policy-ref task-type)
                                   :task/state :task.state/queued
                                   :task/created-at now
                                   :task/updated-at now})))

(defn enqueue-codex-task!
  [db-path]
  (let [store (store.sqlite/sqlite-state-store db-path)
        defs-repo (defs.loader/filesystem-definition-repository)
        task-type (defs.protocol/find-task-type-def defs-repo :task-type/cve-investigation 1)
        now (sql/utc-now)]
    (store.protocol/enqueue-task! store
                                  {:task/id (str "task-" (java.util.UUID/randomUUID))
                                   :task/work-key (str "CVE-2024-99999-" (subs (str (java.util.UUID/randomUUID)) 0 8))
                                   :task/task-type-ref {:definition/id (:task-type/id task-type)
                                                        :definition/version (:task-type/version task-type)}
                                   :task/task-fsm-ref (:task-type/task-fsm-ref task-type)
                                   :task/run-fsm-ref (:task-type/run-fsm-ref task-type)
                                   :task/runtime-profile-ref (:task-type/runtime-profile-ref task-type)
                                   :task/artifact-contract-ref (:task-type/artifact-contract-ref task-type)
                                   :task/validator-ref (:task-type/validator-ref task-type)
                                   :task/resource-policy-ref (:task-type/resource-policy-ref task-type)
                                   :task/state :task.state/queued
                                   :task/created-at now
                                   :task/updated-at now})))

(defn create-expired-leased-run!
  [db-path task]
  (let [store (store.sqlite/sqlite-state-store db-path)
        run-id (str "run-" (java.util.UUID/randomUUID))
        lease-id (str "lease-" (java.util.UUID/randomUUID))
        created-at "2026-04-01T00:00:00Z"
        leased-at "2026-04-01T00:01:00Z"
        run {:run/id run-id
             :run/attempt 1
             :run/run-fsm-ref (:task/run-fsm-ref task)
             :run/runtime-profile-ref (:task/runtime-profile-ref task)
             :run/state :run.state/leased
             :run/created-at created-at
             :run/updated-at created-at}
        lease {:lease/id lease-id
               :lease/run-id run-id
               :lease/token (str lease-id "-token")
               :lease/state :lease.state/active
               :lease/expires-at "2026-04-01T00:05:00Z"
               :lease/created-at created-at
               :lease/updated-at created-at}]
    (store.protocol/create-run! store task run lease)
    (store.protocol/transition-task! store (:task/id task)
                                     {:transition/from :task.state/queued
                                      :transition/to :task.state/leased}
                                     leased-at)
    {:run-id run-id
     :lease-id lease-id}))

(defn create-heartbeat-timeout-run!
  [db-path task]
  (let [store (store.sqlite/sqlite-state-store db-path)
        run-id (str "run-" (java.util.UUID/randomUUID))
        lease-id (str "lease-" (java.util.UUID/randomUUID))
        created-at "2026-04-01T00:00:00Z"
        running-at "2026-04-01T00:01:00Z"
        heartbeat-at "2026-04-01T00:02:00Z"
        run {:run/id run-id
             :run/attempt 1
             :run/run-fsm-ref (:task/run-fsm-ref task)
             :run/runtime-profile-ref (:task/runtime-profile-ref task)
             :run/heartbeat-timeout-seconds 60
             :run/state :run.state/leased
             :run/created-at created-at
             :run/updated-at created-at}
        lease {:lease/id lease-id
               :lease/run-id run-id
               :lease/token (str lease-id "-token")
               :lease/state :lease.state/active
               :lease/expires-at "2099-04-01T00:30:00Z"
               :lease/created-at created-at
               :lease/updated-at created-at}]
    (store.protocol/create-run! store task run lease)
    (store.protocol/transition-task! store (:task/id task)
                                     {:transition/from :task.state/queued
                                      :transition/to :task.state/running}
                                     running-at)
    (store.protocol/transition-run! store run-id
                                    {:transition/from :run.state/leased
                                     :transition/to :run.state/running}
                                    running-at)
    (event-ingest/ingest-run-event! store {:event/run-id run-id
                                           :event/type events/run-worker-started
                                           :event/idempotency-key (str run-id ":worker-started")
                                           :event/payload {}
                                           :event/caused-by {:actor/type :worker
                                                             :actor/id "mock-worker"}
                                           :event/emitted-at running-at})
    (event-ingest/ingest-run-event! store {:event/run-id run-id
                                           :event/type events/run-worker-heartbeat
                                           :event/idempotency-key (str run-id ":worker-heartbeat")
                                           :event/payload {:progress/stage :stage/research}
                                           :event/caused-by {:actor/type :worker
                                                             :actor/id "mock-worker"}
                                           :event/emitted-at heartbeat-at})
    {:run-id run-id
     :lease-id lease-id}))

(defn advance-scheduler!
  [db-path steps]
  (dotimes [_ steps]
    (scheduler/run-scheduler-step db-path)))
