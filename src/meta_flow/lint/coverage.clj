(ns meta-flow.lint.coverage
  (:gen-class)
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def warning-threshold 88.0)
(def error-threshold 85.0)

(def coverage-command
  ["clojure"
   "-M:kaocha:coverage"
   "--plugin" "cloverage"
   "--cov-summary"
   "--no-cov-html"
   "--no-codecov"
   "--no-lcov"
   "--no-emma-xml"
   "--no-coveralls"
   "--cov-ns-exclude-regex" "^meta-flow\\.lint\\."
   "--cov-output" "target/coverage"])

(def governance-intent
  (str "Intent: use test coverage as a governance signal to keep behavior and "
       "responsibility aligned with executable checks. When this warning or "
       "error appears, consider which responsibilities changed without enough "
       "test coverage, and add or split tests around the uncovered behavior "
       "instead of letting critical paths drift unverified."))

(def coverage-row-pattern
  #"(?m)^\|\s+(.+?)\s+\|\s+([0-9.]+)\s+\|\s+([0-9.]+)\s+\|$")

(defn parse-coverage-rows
  [output]
  (->> (re-seq coverage-row-pattern output)
       (map (fn [[_ namespace form-pct line-pct]]
              {:namespace (str/trim namespace)
               :form-coverage (Double/parseDouble form-pct)
               :line-coverage (Double/parseDouble line-pct)}))
       vec))

(defn overall-line-coverage
  [rows]
  (some (fn [{:keys [namespace line-coverage]}]
          (when (= "ALL FILES" namespace)
            line-coverage))
        rows))

(defn classify-line-coverage
  [line-coverage]
  (cond
    (< line-coverage error-threshold) :error
    (< line-coverage warning-threshold) :warning
    :else nil))

(defn lowest-covered-namespaces
  [rows]
  (->> rows
       (remove #(= "ALL FILES" (:namespace %)))
       (remove #(str/starts-with? (:namespace %) "meta-flow.lint."))
       (sort-by (juxt :line-coverage :namespace))
       (take 5)
       vec))

(defn format-namespace-summary
  [rows]
  (->> rows
       (map (fn [{:keys [namespace line-coverage]}]
              (format "%s %.2f%%" namespace line-coverage)))
       (str/join ", ")))

(defn issue-message
  [{:keys [line-coverage level lowest-namespaces]}]
  (let [level-label (str/upper-case (name level))
        threshold (case level
                    :error error-threshold
                    :warning warning-threshold)
        consequence (case level
                      :error "This blocks coverage governance until enough behavior is covered to climb back above the error threshold."
                      :warning "This does not fail coverage governance yet, but it should trigger a review of which responsibilities are changing without matching executable checks.")
        lowest-summary (format-namespace-summary lowest-namespaces)]
    (str level-label " [coverage-governance] overall line coverage is "
         (format "%.2f%%" line-coverage)
         ", below the " (name level) " threshold of "
         (format "%.2f%%" threshold) ".\n"
         governance-intent "\n"
         "Lowest-covered namespaces: " lowest-summary ".\n"
         consequence)))

(defn parse-summary
  [output]
  (let [rows (parse-coverage-rows output)
        line-coverage (overall-line-coverage rows)]
    (when line-coverage
      {:line-coverage line-coverage
       :lowest-namespaces (lowest-covered-namespaces rows)
       :level (classify-line-coverage line-coverage)})))

(defn run-coverage-command!
  ([] (run-coverage-command! coverage-command))
  ([command]
   (apply shell/sh command)))

(defn evaluate-coverage
  ([] (evaluate-coverage coverage-command))
  ([command]
   (let [{:keys [exit out err]} (run-coverage-command! command)]
     {:exit exit
      :out out
      :err err
      :summary (when (zero? exit)
                 (parse-summary out))})))

(defn run-coverage!
  []
  (let [{:keys [exit out err]} (run-coverage-command!)]
    (print out)
    (flush)
    (binding [*out* *err*]
      (print err)
      (flush))
    {:exit exit
     :out out
     :err err}))

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

(defn -main
  [& _]
  (let [{:keys [exit out]} (run-coverage!)]
    (when-not (zero? exit)
      (System/exit exit))
    (let [summary (parse-summary out)]
      (when-not summary
        (binding [*out* *err*]
          (println "ERROR [coverage-governance] Failed to parse Cloverage summary output. Review the coverage command output and update the parser."))
        (System/exit 1))
      (let [{:keys [level]} summary]
        (when level
          (print-issue! summary))
        (print-summary! summary)
        (when (= :error level)
          (System/exit 1))))))
