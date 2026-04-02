(ns meta-flow.runtime.mock
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cheshire.core :as cheshire]
            [meta-flow.events :as events]
            [meta-flow.event-ingest :as event-ingest]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.store.protocol :as store.protocol]))

(def ^:dynamic *artifact-root-dir*
  "var/artifacts")

(def ^:dynamic *run-root-dir*
  "var/runs")

(defn- randomized-id
  [seed]
  (str seed "-" (System/currentTimeMillis)))

(defn- run-workdir
  [run-id]
  (str *run-root-dir* "/" run-id))

(defn- artifact-root-path
  [task-id run-id]
  (str *artifact-root-dir* "/" task-id "/" run-id))

(defn- runtime-state-path
  [run-id]
  (str (run-workdir run-id) "/runtime-state.edn"))

(defn- write-edn-file!
  [path value]
  (spit path (pr-str value)))

(defn- read-edn-file
  [path]
  (when (.exists (io/file path))
    (edn/read-string (slurp path))))

(defn- write-runtime-state!
  [run-id runtime-state]
  (write-edn-file! (runtime-state-path run-id) runtime-state))

(defn- read-runtime-state
  [run-id]
  (read-edn-file (runtime-state-path run-id)))

(defn- mock-event-intent
  [run event-type idempotency-token payload now]
  {:event/run-id (:run/id run)
   :event/type event-type
   :event/payload payload
   :event/caused-by {:actor/type :runtime.adapter/mock
                     :actor/id "mock-runtime"}
   :event/idempotency-key (str "mock:" (:run/id run) ":" event-type ":" idempotency-token)
   :event/emitted-at now})

(defn- emit-event!
  [store run event-type idempotency-token payload now]
  (event-ingest/ingest-run-event! store
                                  (mock-event-intent run event-type idempotency-token payload now)))

(defn- artifact-content
  [task run]
  {:run/id (:run/id run)
   :task/id (:task/id task)
   :attempt (:run/attempt run)
   :runtime/profile (:task/runtime-profile-ref task)
   :generated/at (:run/updated-at run)
   :status "completed"})

(defn- complete-run!
  [store task run runtime-state now]
  (let [run-id (:run/id run)
        task-id (:task/id task)
        artifact-root (artifact-root-path task-id run-id)
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
     :events [(mock-event-intent run events/run-worker-exited "worker-exited"
                                 {:worker/exit-code 0}
                                 now)]}))

(defn- cancel-exit-events
  [run now]
  [(mock-event-intent run events/run-worker-exited "worker-cancelled"
                      {:worker/exit-code 130
                       :worker/cancelled? true}
                      now)])

(defrecord MockRuntimeAdapter []
  runtime.protocol/RuntimeAdapter
  (adapter-id [_]
    :runtime.adapter/mock)
  (prepare-run! [_ _ task run]
    (let [task-id (:task/id task)
          run-id (:run/id run)
          workdir (run-workdir run-id)
          definitions-path (str workdir "/definitions.edn")
          task-path (str workdir "/task.edn")
          run-path (str workdir "/run.edn")
          profile-path (str workdir "/runtime-profile.edn")
          manifest-path (str workdir "/artifact-contract.edn")
          log-path (str workdir "/run.log")
          artifact-path (artifact-root-path task-id run-id)]
      (.mkdirs (io/file workdir))
      (.mkdirs (io/file artifact-path))
      (spit log-path (str "run " run-id " prepared\n"))
      (write-edn-file! definitions-path {})
      (write-edn-file! task-path task)
      (write-edn-file! run-path run)
      (write-edn-file! profile-path (:task/runtime-profile-ref task))
      (write-runtime-state! run-id {:phase :phase/prepared
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
  (dispatch-run! [_ ctx task run]
    (let [{:keys [store now]} ctx
          run-id (:run/id run)
          artifact-root (artifact-root-path (:task/id task) run-id)
          artifact-id (randomized-id "artifact")
          runtime-state (assoc (or (read-runtime-state run-id) {})
                               :phase :phase/dispatched
                               :artifact/id artifact-id
                               :cancelled? false)]
      (write-runtime-state! run-id runtime-state)
      (emit-event! store run events/run-dispatched "dispatched" {} now)
      {:runtime-run/dispatch "polling"
       :runtime-run/workdir (run-workdir run-id)
       :runtime-run/artifact-root artifact-root
       :runtime-run/artifact-id artifact-id}))
  (poll-run! [_ {:keys [store]} run now]
    (let [run-id (:run/id run)
          runtime-state (or (read-runtime-state run-id) {:phase :phase/unknown})
          task {:task/id (:task/id runtime-state)
                :task/runtime-profile-ref (:task/runtime-profile-ref runtime-state)
                :task/artifact-contract-ref (:task/artifact-contract-ref runtime-state)}]
      (case (:phase runtime-state)
        :phase/dispatched
        (if (:cancelled? runtime-state)
          (do
            (write-runtime-state! run-id (assoc runtime-state :phase :phase/cancelled-exited))
            (cancel-exit-events run now))
          (do
            (write-runtime-state! run-id (assoc runtime-state :phase :phase/started))
            [(mock-event-intent run events/task-worker-started "worker-started" {} now)
             (mock-event-intent run events/run-worker-started "run-started" {} now)]))

        :phase/started
        (if (:cancelled? runtime-state)
          (do
            (write-runtime-state! run-id (assoc runtime-state :phase :phase/cancelled-exited))
            (cancel-exit-events run now))
          (do
            (write-runtime-state! run-id (assoc runtime-state :phase :phase/heartbeat))
            [(mock-event-intent run events/run-worker-heartbeat "heartbeat"
                                {:worker/status :worker.status/running
                                 :worker/stage :worker.stage/mock-execution}
                                now)]))

        :phase/heartbeat
        (if (:cancelled? runtime-state)
          (do
            (write-runtime-state! run-id (assoc runtime-state :phase :phase/cancelled-exited))
            (cancel-exit-events run now))
          (let [{:keys [runtime-state events]} (complete-run! store task run runtime-state now)]
            (write-runtime-state! run-id runtime-state)
            events))

        :phase/exited
        (let [artifact-id (:artifact/id runtime-state)
              artifact-root (:artifact/root-path runtime-state)
              contract-ref (:task/artifact-contract-ref task)]
          (write-runtime-state! run-id (assoc runtime-state :phase :phase/completed))
          [(mock-event-intent run events/run-artifact-ready "artifact-ready"
                              {:artifact/id artifact-id
                               :artifact/root-path artifact-root
                               :artifact/contract-ref contract-ref}
                              now)
           (mock-event-intent run events/task-artifact-ready "task-artifact-ready"
                              {:artifact/id artifact-id
                               :artifact/root-path artifact-root
                               :artifact/contract-ref contract-ref}
                              now)])

        [])))
  (cancel-run! [_ _ run reason]
    (let [run-id (:run/id run)
          runtime-state (or (read-runtime-state run-id) {:phase :phase/unknown})]
      (write-runtime-state! run-id (assoc runtime-state
                                          :cancelled? true
                                          :cancel-reason reason))
      {:status :cancel-requested})))

(defn mock-runtime
  []
  (->MockRuntimeAdapter))
