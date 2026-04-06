(ns meta-flow.lint.check.frontend-node
  (:gen-class)
  (:require [meta-flow.governance.core :as governance]
            [meta-flow.lint.check.frontend :as frontend]))

(def node-id
  :ui)

(def node-label
  "mounted-ui-governance")

(defn node-status
  [gates]
  (cond
    (every? #(= :skipped (:status %)) gates) :skipped
    :else (case (governance/overall-status gates)
            :blocked :error
            :warning :warning
            :pass :pass)))

(defn node-headline
  [status]
  (case status
    :error "ui governance reported blocking failures"
    :warning "ui governance reported warnings"
    :skipped "ui governance skipped"
    "ui governance passed"))

(defn- severity-rank
  [gate]
  (case (:status gate)
    :error 0
    :warning 1
    :skipped 2
    :pass 3
    4))

(defn gate->evidence-line
  [gate]
  (let [base (str (:label gate) ": " (:headline gate))]
    (if-let [cause (:cause gate)]
      (str base " (" cause ")")
      base)))

(defn evidence-lines
  [gates]
  (->> gates
       (remove #(= :pass (:status %)))
       (sort-by (juxt severity-rank :label))
       (map gate->evidence-line)
       (take 5)
       vec))

(defn node-payload
  [gates]
  (let [status (node-status gates)]
    {:node/id node-id
     :node/label node-label
     :node/status status
     :node/headline (node-headline status)
     :node/summary (str (count gates) " ui gate(s) evaluated")
     :node/evidence (evidence-lines gates)
     :node/action "Run `cd ui && bb governance` for gate-by-gate detail."}))

(defn finish-process!
  [exit-code]
  (shutdown-agents)
  (when (some? exit-code)
    (System/exit exit-code)))

(defn -main
  [& _]
  (let [gates (frontend/frontend-gates)]
    (prn (node-payload gates))
    (flush)
    (finish-process! (governance/exit-code gates))))
