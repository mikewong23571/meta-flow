(ns meta-flow.scheduler.dev.task
  (:require [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(def demo-runtime-profile-ref
  {:definition/id :runtime-profile/mock-worker
   :definition/version 1})

(def codex-runtime-profile-ref
  {:definition/id :runtime-profile/codex-worker
   :definition/version 1})

(defn build-demo-task
  [defs-repo {:keys [task-id work-key runtime-profile-ref]
              :or {runtime-profile-ref demo-runtime-profile-ref}}]
  (let [task-type (defs.protocol/find-task-type-def defs-repo :task-type/cve-investigation 1)]
    {:task/id (or task-id (str "task-" (shared/new-id)))
     :task/work-key (or work-key (str "CVE-2024-12345-" (subs (shared/new-id) 0 8)))
     :task/task-type-ref {:definition/id (:task-type/id task-type)
                          :definition/version (:task-type/version task-type)}
     :task/task-fsm-ref (:task-type/task-fsm-ref task-type)
     :task/run-fsm-ref (:task-type/run-fsm-ref task-type)
     :task/runtime-profile-ref runtime-profile-ref
     :task/artifact-contract-ref (:task-type/artifact-contract-ref task-type)
     :task/validator-ref (:task-type/validator-ref task-type)
     :task/resource-policy-ref (:task-type/resource-policy-ref task-type)
     :task/state :task.state/queued
     :task/created-at (shared/now)
     :task/updated-at (shared/now)}))

(defn build-repo-arch-task
  [defs-repo {:keys [task-id repo-url notify-email]}]
  (let [task-type (defs.protocol/find-task-type-def defs-repo :task-type/repo-arch-investigation 1)]
    {:task/id (or task-id (str "task-" (shared/new-id)))
     :task/work-key (pr-str [:repo-arch repo-url notify-email])
     :task/input {:input/repo-url repo-url
                  :input/notify-email notify-email}
     :task/task-type-ref {:definition/id (:task-type/id task-type)
                          :definition/version (:task-type/version task-type)}
     :task/task-fsm-ref (:task-type/task-fsm-ref task-type)
     :task/run-fsm-ref (:task-type/run-fsm-ref task-type)
     :task/runtime-profile-ref (:task-type/runtime-profile-ref task-type)
     :task/artifact-contract-ref (:task-type/artifact-contract-ref task-type)
     :task/validator-ref (:task-type/validator-ref task-type)
     :task/resource-policy-ref (:task-type/resource-policy-ref task-type)
     :task/state :task.state/queued
     :task/created-at (shared/now)
     :task/updated-at (shared/now)}))

(defn enqueue-repo-arch-task!
  [db-path {:keys [repo-url notify-email] :as options}]
  (let [store (store.sqlite/sqlite-state-store db-path)
        defs-repo (defs.loader/filesystem-definition-repository)
        now-value (shared/now)
        _ (shared/ensure-collection-state! store defs-repo now-value)
        work-key (pr-str [:repo-arch repo-url notify-email])
        existing-task (store.protocol/find-task-by-work-key store work-key)
        task (or existing-task
                 (store.protocol/enqueue-task! store
                                               (build-repo-arch-task defs-repo options)))]
    {:task task
     :reused? (boolean existing-task)}))

(defn enqueue-demo-task!
  ([db-path]
   (enqueue-demo-task! db-path {}))
  ([db-path options]
   (let [store (store.sqlite/sqlite-state-store db-path)
         defs-repo (defs.loader/filesystem-definition-repository)
         now-value (shared/now)
         _ (shared/ensure-collection-state! store defs-repo now-value)
         existing-task (when-let [work-key (:work-key options)]
                         (store.protocol/find-task-by-work-key store work-key))
         task (or existing-task
                  (store.protocol/enqueue-task! store
                                                (build-demo-task defs-repo options)))]
     {:task task
      :reused? (boolean existing-task)})))
