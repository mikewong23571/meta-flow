(ns meta-flow.lint.coverage.summary
  (:require [clojure.string :as str]))

(def warning-threshold 88.0)
(def error-threshold 85.0)

(def governance-intent
  (str "Intent: use test coverage as a governance signal to keep behavior and "
       "responsibility aligned with executable checks. When this warning or "
       "error appears, consider which responsibilities changed without enough "
       "test coverage, and add or split tests around the uncovered behavior "
       "instead of letting critical paths drift unverified."))

(def coverage-row-pattern
  #"(?m)^\|\s+(.+?)\s+\|\s+([0-9.]+)\s+\|\s+([0-9.]+)\s+\|$")

(defn classify-line-coverage
  [line-coverage]
  (cond
    (< line-coverage error-threshold) :error
    (< line-coverage warning-threshold) :warning
    :else nil))

(defn parse-coverage-rows
  [output]
  (->> (re-seq coverage-row-pattern output)
       (map (fn [[_ namespace form-pct line-pct]]
              {:namespace (str/trim namespace)
               :form-coverage (Double/parseDouble form-pct)
               :line-coverage (Double/parseDouble line-pct)}))
       vec))

(defn file-stat->coverage-row
  [{:keys [lib forms covered-forms instrd-lines covered-lines partial-lines]}]
  {:namespace (str lib)
   :form-coverage (if (zero? forms)
                    0.0
                    (* 100.0 (/ covered-forms forms)))
   :line-coverage (if (zero? instrd-lines)
                    0.0
                    (* 100.0 (/ (+ covered-lines partial-lines) instrd-lines)))})

(defn lowest-covered-namespaces
  [rows]
  (->> rows
       (remove #(= "ALL FILES" (:namespace %)))
       (remove #(str/starts-with? (:namespace %) "meta-flow.lint."))
       (sort-by (juxt :line-coverage :namespace))
       (take 5)
       vec))

(defn parse-summary
  [output]
  (let [rows (parse-coverage-rows output)
        line-coverage (some (fn [{:keys [namespace line-coverage]}]
                              (when (= "ALL FILES" namespace)
                                line-coverage))
                            rows)]
    (when line-coverage
      {:line-coverage line-coverage
       :lowest-namespaces (lowest-covered-namespaces rows)
       :level (classify-line-coverage line-coverage)})))

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

(defn counts-from-totals
  [totals]
  {:tests (:kaocha.result/count totals 0)
   :passes (:kaocha.result/pass totals 0)
   :errors (:kaocha.result/error totals 0)
   :failures (:kaocha.result/fail totals 0)
   :pending (:kaocha.result/pending totals 0)})
