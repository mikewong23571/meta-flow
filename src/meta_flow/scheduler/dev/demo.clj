(ns meta-flow.scheduler.dev.demo
  (:require [clojure.java.io :as io]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.scheduler.dev.task :as task]
            [meta-flow.scheduler.shared :as shared]
            [meta-flow.scheduler.validation :as validation]
            [meta-flow.store.protocol :as store.protocol]
            [meta-flow.store.sqlite :as store.sqlite]))

(def max-demo-scheduler-steps
  20)

(def codex-smoke-sleep-ms
  100)

(def codex-smoke-timeout-grace-seconds
  30)

(defn codex-max-steps
  [runtime-profile sleep-ms]
  (let [worker-timeout-seconds (long (or (:runtime-profile/worker-timeout-seconds runtime-profile)
                                         300))
        total-ms (* 1000 (+ worker-timeout-seconds
                            codex-smoke-timeout-grace-seconds))]
    (max 300
         (long (Math/ceil (/ (double total-ms)
                             (double sleep-ms)))))))

(defn codex-smoke-max-steps
  [runtime-profile]
  (codex-max-steps runtime-profile codex-smoke-sleep-ms))

(defn happy-path-complete?
  [task run]
  (and (= :task.state/completed (:task/state task))
       (= :run.state/finalized (:run/state run))))

(defn retry-path-complete?
  [task run]
  (and (= :task.state/retryable-failed (:task/state task))
       (= :run.state/retryable-failed (:run/state run))))

(defn demo-happy-path!
  [db-path run-scheduler-step-fn]
  (let [store (store.sqlite/sqlite-state-store db-path)
        task (:task (task/enqueue-demo-task! db-path))]
    (loop [step 0]
      (let [task-now (store.protocol/find-task store (:task/id task))
            run-now (shared/latest-run store (:task/id task))]
        (cond
          (happy-path-complete? task-now run-now)
          {:task task-now
           :run run-now
           :artifact-root (shared/run-artifact-root store run-now)
           :scheduler-steps step}

          (>= step max-demo-scheduler-steps)
          (throw (ex-info "Demo happy path did not converge within the expected scheduler steps"
                          {:task task-now
                           :run run-now
                           :scheduler-steps step}))

          :else
          (do
            (run-scheduler-step-fn db-path)
            (recur (inc step))))))))

(defn demo-retry-path!
  [db-path run-scheduler-step-fn]
  (let [store (store.sqlite/sqlite-state-store db-path)
        task (:task (task/enqueue-demo-task! db-path))]
    (loop [step 0
           tampered? false]
      (let [task-now (store.protocol/find-task store (:task/id task))
            run-now (shared/latest-run store (:task/id task))
            artifact-root (some->> run-now
                                   (shared/run-artifact-root store))]
        (cond
          (retry-path-complete? task-now run-now)
          {:task task-now
           :run run-now
           :artifact-root artifact-root
           :assessment (store.protocol/find-assessment-by-key store (:run/id run-now) validation/current-assessment-key)
           :disposition (store.protocol/find-disposition-by-key store (:run/id run-now) validation/current-disposition-key)
           :scheduler-steps step}

          (>= step max-demo-scheduler-steps)
          (throw (ex-info "Demo retry path did not converge within the expected scheduler steps"
                          {:task task-now
                           :run run-now
                           :scheduler-steps step
                           :artifact-root artifact-root
                           :tampered? tampered?}))

          :else
          (let [tampered-now? (if (and (not tampered?)
                                       artifact-root
                                       (= :run.state/exited (:run/state run-now)))
                                (let [notes-file (io/file artifact-root "notes.md")]
                                  (when-not (.exists notes-file)
                                    (throw (ex-info "Demo retry path could not find notes.md to remove"
                                                    {:artifact-root artifact-root
                                                     :run run-now})))
                                  (when-not (.delete notes-file)
                                    (throw (ex-info "Demo retry path could not remove notes.md"
                                                    {:artifact-root artifact-root
                                                     :run run-now})))
                                  true)
                                tampered?)]
            (run-scheduler-step-fn db-path)
            (recur (inc step) tampered-now?)))))))

(defn demo-codex-smoke!
  [db-path run-scheduler-step-fn]
  (let [store (store.sqlite/sqlite-state-store db-path)
        defs-repo (defs.loader/filesystem-definition-repository)
        runtime-profile (defs.protocol/find-runtime-profile defs-repo
                                                            (:definition/id task/codex-runtime-profile-ref)
                                                            (:definition/version task/codex-runtime-profile-ref))
        max-steps (codex-smoke-max-steps runtime-profile)
        task (:task (task/enqueue-demo-task! db-path {:runtime-profile-ref task/codex-runtime-profile-ref
                                                      :work-key (str "CVE-2024-CODEX-SMOKE-" (subs (shared/new-id) 0 8))}))]
    (loop [step 0]
      (let [task-now (store.protocol/find-task store (:task/id task))
            run-now (shared/latest-run store (:task/id task))]
        (cond
          (happy-path-complete? task-now run-now)
          {:task task-now
           :run run-now
           :artifact-root (shared/run-artifact-root store run-now)
           :scheduler-steps step}

          (>= step max-steps)
          (throw (ex-info "Codex smoke demo did not converge within the expected scheduler steps"
                          {:task task-now
                           :run run-now
                           :scheduler-steps step
                           :max-steps max-steps
                           :worker-timeout-seconds (:runtime-profile/worker-timeout-seconds runtime-profile)}))

          :else
          (do
            (run-scheduler-step-fn db-path)
            (Thread/sleep codex-smoke-sleep-ms)
            (recur (inc step))))))))
