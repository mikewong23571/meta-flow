(ns meta-flow.runtime.mock.execution
  (:require [clojure.java.io :as io]
            [cheshire.core :as cheshire]
            [meta-flow.events :as events]
            [meta-flow.runtime.mock.events :as mock.events]
            [meta-flow.runtime.mock.fs :as mock.fs]
            [meta-flow.store.protocol :as store.protocol]))

(defn randomized-id
  [seed]
  (str seed "-" (System/currentTimeMillis)))

(defn artifact-content
  [task run]
  {:run/id (:run/id run)
   :task/id (:task/id task)
   :attempt (:run/attempt run)
   :runtime/profile (:task/runtime-profile-ref task)
   :generated/at (:run/updated-at run)
   :status "completed"})

(defn complete-run!
  [store task run runtime-state now]
  (let [run-id (:run/id run)
        task-id (:task/id task)
        artifact-root (mock.fs/artifact-root-path task-id run-id)
        artifact-id (or (:artifact/id runtime-state)
                        (randomized-id "artifact"))
        artifact-path (str artifact-root "/")
        manifest-path (str artifact-root "/manifest.json")
        notes-path (str artifact-root "/notes.md")
        run-log-path (str artifact-root "/run.log")
        contract-ref (:task/artifact-contract-ref task)]
    (.mkdirs (io/file artifact-root))
    (spit run-log-path (str "mock worker for task " task-id "; run " run-id "\n") :append true)
    (spit manifest-path (cheshire/generate-string (artifact-content task run)
                                                  {:pretty true}))
    (spit notes-path (str "Run " run-id " completed for task " task-id "\n"))
    (store.protocol/attach-artifact! store run-id
                                     {:artifact/id artifact-id
                                      :artifact/run-id run-id
                                      :artifact/task-id task-id
                                      :artifact/contract-ref contract-ref
                                      :artifact/location artifact-path
                                      :artifact/created-at now})
    {:runtime-state (assoc runtime-state
                           :phase :phase/exited
                           :artifact/id artifact-id
                           :artifact/root-path artifact-root)
     :events [(mock.events/mock-event-intent run events/run-worker-exited "worker-exited"
                                             {:worker/exit-code 0}
                                             now)]}))

(defn prepare-run!
  [task run]
  (let [task-id (:task/id task)
        run-id (:run/id run)
        workdir (mock.fs/run-workdir run-id)
        definitions-path (str workdir "/definitions.edn")
        task-path (str workdir "/task.edn")
        run-path (str workdir "/run.edn")
        profile-path (str workdir "/runtime-profile.edn")
        manifest-path (str workdir "/artifact-contract.edn")
        log-path (str workdir "/run.log")
        artifact-path (mock.fs/artifact-root-path task-id run-id)]
    (.mkdirs (io/file workdir))
    (.mkdirs (io/file artifact-path))
    (spit log-path (str "run " run-id " prepared\n"))
    (mock.fs/write-edn-file! definitions-path {})
    (mock.fs/write-edn-file! task-path task)
    (mock.fs/write-edn-file! run-path run)
    (mock.fs/write-edn-file! profile-path (:task/runtime-profile-ref task))
    (mock.fs/write-runtime-state! run-id {:phase :phase/prepared
                                          :task/id task-id
                                          :task/runtime-profile-ref (:task/runtime-profile-ref task)
                                          :task/artifact-contract-ref (:task/artifact-contract-ref task)
                                          :cancelled? false})
    (spit manifest-path (cheshire/generate-string
                         {:artifact-root artifact-path
                          :run-log log-path
                          :notes "placeholder"}
                         {:pretty true}))
    {:runtime-run/workdir workdir
     :runtime-run/artifact-root artifact-path
     :runtime-run/log-path log-path}))

(defn dispatch-run!
  [ctx task run]
  (let [{:keys [store now]} ctx
        run-id (:run/id run)
        artifact-root (mock.fs/artifact-root-path (:task/id task) run-id)
        artifact-id (randomized-id "artifact")
        runtime-state (assoc (or (mock.fs/read-runtime-state run-id) {})
                             :phase :phase/dispatched
                             :artifact/id artifact-id
                             :cancelled? false)]
    (mock.fs/write-runtime-state! run-id runtime-state)
    (mock.events/emit-event! store run events/run-dispatched "dispatched" {} now)
    {:runtime-run/dispatch "polling"
     :runtime-run/workdir (mock.fs/run-workdir run-id)
     :runtime-run/artifact-root artifact-root
     :runtime-run/artifact-id artifact-id}))

(defn poll-run!
  [{:keys [store]} run now]
  (let [run-id (:run/id run)
        runtime-state (or (mock.fs/read-runtime-state run-id) {:phase :phase/unknown})
        task {:task/id (:task/id runtime-state)
              :task/runtime-profile-ref (:task/runtime-profile-ref runtime-state)
              :task/artifact-contract-ref (:task/artifact-contract-ref runtime-state)}]
    (case (:phase runtime-state)
      :phase/dispatched
      (if (:cancelled? runtime-state)
        (do
          (mock.fs/write-runtime-state! run-id (assoc runtime-state :phase :phase/cancelled-exited))
          (mock.events/cancel-exit-events run now))
        (do
          (mock.fs/write-runtime-state! run-id (assoc runtime-state :phase :phase/started))
          [(mock.events/mock-event-intent run events/task-worker-started "worker-started" {} now)
           (mock.events/mock-event-intent run events/run-worker-started "run-started" {} now)]))

      :phase/started
      (if (:cancelled? runtime-state)
        (do
          (mock.fs/write-runtime-state! run-id (assoc runtime-state :phase :phase/cancelled-exited))
          (mock.events/cancel-exit-events run now))
        (do
          (mock.fs/write-runtime-state! run-id (assoc runtime-state :phase :phase/heartbeat))
          [(mock.events/mock-event-intent run events/run-worker-heartbeat "heartbeat"
                                          {:worker/status :worker.status/running
                                           :worker/stage :worker.stage/mock-execution}
                                          now)]))

      :phase/heartbeat
      (if (:cancelled? runtime-state)
        (do
          (mock.fs/write-runtime-state! run-id (assoc runtime-state :phase :phase/cancelled-exited))
          (mock.events/cancel-exit-events run now))
        (let [{:keys [runtime-state events]} (complete-run! store task run runtime-state now)]
          (mock.fs/write-runtime-state! run-id runtime-state)
          events))

      :phase/exited
      (let [artifact-id (:artifact/id runtime-state)
            artifact-root (:artifact/root-path runtime-state)
            contract-ref (:task/artifact-contract-ref task)]
        (mock.fs/write-runtime-state! run-id (assoc runtime-state :phase :phase/completed))
        [(mock.events/mock-event-intent run events/run-artifact-ready "artifact-ready"
                                        {:artifact/id artifact-id
                                         :artifact/root-path artifact-root
                                         :artifact/contract-ref contract-ref}
                                        now)
         (mock.events/mock-event-intent run events/task-artifact-ready "task-artifact-ready"
                                        {:artifact/id artifact-id
                                         :artifact/root-path artifact-root
                                         :artifact/contract-ref contract-ref}
                                        now)])

      [])))

(defn cancel-run!
  [run reason]
  (let [run-id (:run/id run)
        runtime-state (or (mock.fs/read-runtime-state run-id) {:phase :phase/unknown})]
    (mock.fs/write-runtime-state! run-id (assoc runtime-state
                                                :cancelled? true
                                                :cancel-reason reason))
    {:status :cancel-requested}))
