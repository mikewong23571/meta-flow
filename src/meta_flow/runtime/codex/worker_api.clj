(ns meta-flow.runtime.codex.worker-api
  (:require [clojure.string :as str]
            [meta-flow.runtime.codex.helper :as codex.helper]
            [meta-flow.runtime.codex.worker :as codex.worker]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn- parse-argv
  [argv]
  (loop [args argv
         options {}]
    (if (empty? args)
      options
      (let [[option value & rest] args]
        (when-not (str/starts-with? option "--")
          (throw (ex-info "Expected option starting with --"
                          {:args argv
                           :option option})))
        (when (nil? value)
          (throw (ex-info "Missing option value"
                          {:args argv
                           :option option})))
        (recur rest
               (assoc options (keyword (subs option 2)) value))))))

(defn- require-option!
  [options key-name]
  (or (get options key-name)
      (throw (ex-info (str "Missing required option --" (name key-name))
                      {:options options
                       :option key-name}))))

(defn- emit-worker-started!
  [store ctx options]
  (codex.helper/emit-worker-started! store
                                     ctx
                                     {:token (require-option! options :token)
                                      :at (:at options)}))

(defn- emit-heartbeat!
  [store ctx options]
  (codex.helper/emit-heartbeat! store
                                ctx
                                {:token (require-option! options :token)
                                 :at (:at options)
                                 :status (:status options)
                                 :stage (:stage options)
                                 :message (:message options)}))

(defn- emit-worker-exit!
  [store ctx options]
  (codex.helper/emit-worker-exit! store
                                  ctx
                                  {:token (require-option! options :token)
                                   :at (:at options)
                                   :exit-code (Long/parseLong (or (:exit-code options) "0"))
                                   :cancelled (= "true" (:cancelled options))}))

(defn- emit-artifact-ready!
  [store ctx options]
  (codex.helper/emit-artifact-ready! store
                                     ctx
                                     {:token (require-option! options :token)
                                      :at (:at options)
                                      :artifact-id (:artifact-id options)
                                      :artifact-root (:artifact-root options)}))

(defn- run-stub-worker!
  [_ ctx options]
  (codex.worker/run-stub-worker! ctx
                                 (require-option! options :db-path)
                                 (codex.helper/artifact-root ctx options)
                                 (codex.helper/artifact-id ctx options)))

(defn -main
  [& argv]
  (let [[command & rest-args] argv
        options (parse-argv rest-args)
        db-path (:db-path options)
        store (when db-path (store.sqlite/sqlite-state-store db-path))
        ctx (when-let [workdir (:workdir options)]
              (codex.helper/workdir-context workdir))]
    (case command
      "worker-started" (emit-worker-started! store ctx options)
      "heartbeat" (emit-heartbeat! store ctx options)
      "progress" (emit-heartbeat! store ctx options)
      "worker-exit" (emit-worker-exit! store ctx options)
      "artifact-ready" (emit-artifact-ready! store ctx options)
      "stub-worker" (run-stub-worker! store ctx options)
      (throw (ex-info "Unsupported worker API command"
                      {:command command
                       :argv argv})))))
