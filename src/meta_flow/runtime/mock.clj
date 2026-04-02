(ns meta-flow.runtime.mock
  (:require [clojure.java.io :as io]
            [cheshire.core :as cheshire]
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

(defn- write-edn-file!
  [path value]
  (spit path (pr-str value)))

(defn- emit-event!
  [store run event-type idempotency-token payload now]
  (event-ingest/ingest-run-event! store
                                  {:event/run-id (:run/id run)
                                   :event/type event-type
                                   :event/payload payload
                                   :event/caused-by {:actor/type :runtime.adapter/mock
                                                     :actor/id "mock-runtime"}
                                   :event/idempotency-key (str "mock:" (:run/id run) ":" event-type ":" idempotency-token)
                                   :event/emitted-at now}))

(defn- artifact-content
  [task run]
  {:run/id (:run/id run)
   :task/id (:task/id task)
   :attempt (:run/attempt run)
   :runtime/profile (:task/runtime-profile-ref task)
   :generated/at (:run/updated-at run)
   :status "completed"})

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
      (spit manifest-path (cheshire/generate-string
                           {:artifact-root artifact-path
                            :run-log log-path
                            :notes "placeholder"}
                           {:pretty true}))
      {:runtime-run/workdir workdir
       :runtime-run/artifact-root artifact-path
       :runtime-run/log-path log-path}))
  (dispatch-run! [this ctx task run]
    (let [{:keys [store now]} ctx
          run-id (:run/id run)
          task-id (:task/id task)
          artifact-root (artifact-root-path task-id run-id)
          artifact-id (randomized-id "artifact")
          artifact-path (str artifact-root "/")
          manifest-path (str artifact-root "/manifest.json")
          notes-path (str artifact-root "/notes.md")
          run-log-path (str artifact-root "/run.log")
          contract-ref (:task/artifact-contract-ref task)
          contract-id (:definition/id contract-ref)
          contract-version (:definition/version contract-ref)]
      (.mkdirs (io/file artifact-root))
      (spit run-log-path (str "mock worker for task " task-id "; run " run-id "\n"))
      (spit manifest-path (cheshire/generate-string (artifact-content task run)
                                                   {:pretty true}))
      (spit notes-path (str "Run " run-id " completed for task " task-id "\n"))
      (emit-event! store run :run.event/dispatched "dispatched" {} now)
      (emit-event! store run :task.event/worker-started "worker-started" {} now)
      (emit-event! store run :run.event/worker-started "run-started" {} now)
      (emit-event! store run :run.event/worker-exited "worker-exited" {:worker/exit-code 0} now)
      (store.protocol/attach-artifact! store run-id
                                       {:artifact/id artifact-id
                                        :artifact/run-id run-id
                                        :artifact/task-id task-id
                                        :artifact/contract-ref {:definition/id contract-id
                                                               :definition/version contract-version}
                                        :artifact/location artifact-path
                                        :artifact/created-at now})
      (emit-event! store run :run.event/artifact-ready "artifact-ready"
                   {:artifact/id artifact-id
                    :artifact/root-path artifact-root
                    :artifact/contract-ref {:definition/id contract-id
                                           :definition/version contract-version}}
                   now)
      (emit-event! store run :task.event/artifact-ready "task-artifact-ready"
                   {:artifact/id artifact-id
                    :artifact/root-path artifact-root
                    :artifact/contract-ref {:definition/id contract-id
                                           :definition/version contract-version}}
                   now)
      {:runtime-run/dispatch "synchronous"
       :runtime-run/workdir (run-workdir run-id)
       :runtime-run/artifact-root artifact-root
       :runtime-run/artifact-id artifact-id}))
  (poll-run! [_ _ _ _]
    [])
  (cancel-run! [_ _ _ _]
    {:status :not-implemented}))

(defn mock-runtime
  []
  (->MockRuntimeAdapter))
