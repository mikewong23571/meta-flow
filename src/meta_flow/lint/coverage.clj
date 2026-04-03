(ns meta-flow.lint.coverage
  (:gen-class)
  (:require [meta-flow.lint.coverage.execution :as execution]
            [meta-flow.lint.coverage.summary :as summary]))

(def warning-threshold summary/warning-threshold)
(def error-threshold summary/error-threshold)
(def governance-intent summary/governance-intent)

(def classify-line-coverage summary/classify-line-coverage)
(def parse-summary summary/parse-summary)
(def counts-from-totals summary/counts-from-totals)
(def issue-message summary/issue-message)
(def evaluate-coverage execution/evaluate-coverage)

(defn print-issue!
  [issue]
  (binding [*out* *err*]
    (println (issue-message issue))
    (println)))

(defn print-summary!
  [{:keys [line-coverage level]}]
  (binding [*out* *err*]
    (println (str "coverage summary: overall line coverage "
                  (format "%.2f%%" line-coverage)
                  ", governance level "
                  (or (some-> level name) "pass")
                  "."))))

(defn finish-process!
  [exit-code]
  (shutdown-agents)
  (when (some? exit-code)
    (System/exit exit-code)))

(defn -main
  [& _]
  (let [{:keys [exit err summary]} (evaluate-coverage)]
    (when-not (zero? exit)
      (binding [*out* *err*]
        (print err)
        (flush))
      (finish-process! exit))
    (when-not summary
      (binding [*out* *err*]
        (println "ERROR [coverage-governance] Failed to calculate coverage summary. Review the governance runner and coverage inputs."))
      (finish-process! 1))
    (let [{:keys [level]} summary]
      (when level
        (print-issue! summary))
      (print-summary! summary)
      (finish-process! (when (= :error level)
                         1)))))
