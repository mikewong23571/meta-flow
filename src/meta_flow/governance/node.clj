(ns meta-flow.governance.node
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [meta-flow.governance.core :as governance]))

(def mounted-ui-node-id
  :ui)

(def mounted-ui-label
  "mounted-ui-governance")

(def ui-node-working-dir
  "ui")

(def ui-node-command
  ["bb" "node"])

(defn failure-gate
  [headline cause]
  {:gate :mounted-ui-governance
   :label mounted-ui-label
   :status :error
   :headline headline
   :cause cause
   :action "Run `cd ui && bb node` or `cd ui && bb check` and recover the UI governance node before trusting `bb check:full`."})

(defn parse-node-payload
  [text]
  (edn/read-string {:readers {}
                    :default tagged-literal}
                   text))

(defn normalize-node-payload
  [payload]
  (let [{:node/keys [id label status headline summary evidence action]} payload]
    (when-not (= mounted-ui-node-id id)
      (throw (ex-info "Mounted UI payload has an unexpected node id"
                      {:payload payload})))
    (when-not (string? label)
      (throw (ex-info "Mounted UI payload label must be a string"
                      {:payload payload})))
    (when-not (contains? governance/gate-statuses status)
      (throw (ex-info "Mounted UI payload has an unsupported status"
                      {:payload payload})))
    (when-not (string? headline)
      (throw (ex-info "Mounted UI payload headline must be a string"
                      {:payload payload})))
    (when-not (or (nil? summary) (string? summary))
      (throw (ex-info "Mounted UI payload summary must be a string when present"
                      {:payload payload})))
    (when-not (and (vector? evidence) (every? string? evidence))
      (throw (ex-info "Mounted UI payload evidence must be a vector of strings"
                      {:payload payload})))
    (when-not (string? action)
      (throw (ex-info "Mounted UI payload action must be a string"
                      {:payload payload})))
    {:gate :mounted-ui-governance
     :label label
     :status status
     :headline headline
     :evidence (cond-> []
                 (seq summary) (conj summary)
                 (seq evidence) (into evidence))
     :action action}))

(defn run-mounted-ui-node!
  []
  (let [{:keys [exit out err]} (apply shell/sh (concat ui-node-command [:dir ui-node-working-dir]))
        stdout (str/trim (or out ""))
        stderr (str/trim (or err ""))]
    (cond
      (str/blank? stdout)
      (failure-gate "mounted UI node failed before emitting a payload"
                    (str "exit " exit
                         (when (seq stderr)
                           (str ", stderr: " (first (str/split-lines stderr))))))

      :else
      (try
        (normalize-node-payload (parse-node-payload stdout))
        (catch Throwable throwable
          (failure-gate "mounted UI node emitted an invalid payload"
                        (or (.getMessage throwable) "invalid mounted UI payload")))))))
