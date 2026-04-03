(ns meta-flow.lint.check
  (:gen-class)
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [meta-flow.lint.check.report :as report]
            [meta-flow.lint.check.shared :as shared]
            [meta-flow.lint.coverage :as coverage]
            [meta-flow.lint.file-length :as file-length]))

(def fmt-command
  ["clojure" "-M:fmt/check"])

(def kondo-command
  ["clojure" "-M:lint" "--config" "{:output {:format :json}}"])

(def test-command
  ["clojure" "-M:kaocha"])

(defn run-format-check!
  ([] (run-format-check! []))
  ([extra-paths]
   (let [{:keys [exit combined] :as result} (shared/run-command! (into fmt-command extra-paths))
         incorrect-files (->> (shared/non-blank-lines combined)
                              (keep (fn [line]
                                      (when-let [[_ path] (re-find #"^(.*?) has incorrect formatting$" line)]
                                        path)))
                              distinct
                              vec)
         headline (if (zero? exit)
                    "all tracked source roots are formatted"
                    (or (shared/first-matching-line combined #"formatted incorrectly")
                        (shared/first-matching-line combined #"incorrect formatting")
                        "format drift detected"))]
     (assoc result
            :gate :format-hygiene
            :label "format-hygiene"
            :status (if (zero? exit) :pass :error)
            :headline headline
            :incorrect-files incorrect-files
            :action "Run `bb fmt` and rerun the governance gate."))))

(defn parse-kondo-json
  [output]
  (when-not (str/blank? output)
    (json/parse-string output true)))

(defn run-static-analysis!
  ([] (run-static-analysis! []))
  ([extra-args]
   (let [{:keys [exit out] :as result} (shared/run-command! (into kondo-command extra-args))
         parsed (parse-kondo-json out)
         findings (vec (:findings parsed))
         summary (:summary parsed)
         top-findings (->> findings
                           (sort-by (juxt :filename :row :col :type))
                           (take 5)
                           vec)
         headline (cond
                    (nil? parsed) "static analysis output was not parseable as JSON"
                    (zero? exit) "no static-analysis findings"
                    :else (str (count findings) " static-analysis finding(s) require attention"))]
     (assoc result
            :gate :static-analysis
            :label "static-analysis"
            :status (cond
                      (nil? parsed) :error
                      (zero? exit) :pass
                      :else :error)
            :headline headline
            :summary summary
            :findings top-findings
            :action "Fix the reported clj-kondo findings before trusting downstream behavior checks."))))

(defn structure-headline
  [issues]
  (let [warnings (count (filter #(= :warning (:level %)) issues))
        errors (count (filter #(= :error (:level %)) issues))
        file-count (count (filter #(= :file-length (:kind %)) issues))
        directory-count (count (filter #(= :directory-width (:kind %)) issues))]
    (cond
      (seq issues)
      (str warnings " warning(s), "
           errors " error(s), "
           file-count " file-length issue(s), "
           directory-count " directory-width issue(s)")

      :else
      "no structure-governance issues")))

(defn run-structure-governance!
  ([] (run-structure-governance! file-length/governance-roots))
  ([roots]
   (let [issues (file-length/governance-issues roots)
         errors (count (filter #(= :error (:level %)) issues))
         top-issues (->> issues
                         (sort-by (juxt #(case (:level %)
                                           :error 0
                                           :warning 1
                                           2)
                                        #(case (:kind %)
                                           :file-length 0
                                           :directory-width 1)
                                        :path))
                         (take 5)
                         vec)]
     {:gate :structure-governance
      :label "structure-governance"
      :status (cond
                (pos? errors) :error
                (seq issues) :warning
                :else :pass)
      :headline (structure-headline issues)
      :issues top-issues
      :issue-count (count issues)
      :action "Split oversized namespaces or directories by responsibility before adding more behavior."})))

(def test-count-pattern
  #"(?m)(\d+)\s+tests?,\s+(\d+)\s+assertions?,(?:\s+(\d+)\s+errors?,)?\s+(\d+)\s+failures?")

(defn parse-test-counts
  [text]
  (when-let [[_ tests assertions errors failures] (re-find test-count-pattern (shared/strip-ansi text))]
    {:tests (Long/parseLong tests)
     :assertions (Long/parseLong assertions)
     :errors (Long/parseLong (or errors "0"))
     :failures (Long/parseLong failures)}))

(defn classify-test-failure
  [text]
  (cond
    (re-find #"Failed loading tests:" text) :load-failure
    (re-find #"FAIL in " text) :assertion-failure
    (re-find #"ERROR in " text) :runtime-error
    :else :unknown-failure))

(defn first-cause-line
  [text]
  (or (shared/first-matching-line text #"^Caused by:")
      (shared/first-matching-line text #"^Exception:")
      (shared/first-matching-line text #"^Syntax error")
      (shared/first-matching-line text #"^FAIL in ")
      (shared/first-matching-line text #"^ERROR in ")))

(defn run-test-check!
  []
  (let [{:keys [exit combined] :as result} (shared/run-command! test-command)
        counts (parse-test-counts combined)
        failure-type (when-not (zero? exit)
                       (classify-test-failure combined))
        headline (if (zero? exit)
                   (if counts
                     (str (:tests counts) " tests passed")
                     "test suite passed")
                   (case failure-type
                     :load-failure "test suite failed while loading namespaces"
                     :assertion-failure "test suite reported assertion failures"
                     :runtime-error "test suite reported runtime errors"
                     "test suite failed"))]
    (assoc result
           :gate :executable-correctness
           :label "executable-correctness"
           :status (if (zero? exit) :pass :error)
           :headline headline
           :counts counts
           :failure-type failure-type
           :cause (when-not (zero? exit)
                    (first-cause-line combined))
           :action "Fix test load, compile, or runtime breakage before trusting coverage governance.")))

(defn run-coverage-check!
  []
  (let [{:keys [exit err summary]} (coverage/evaluate-coverage)]
    (cond
      (not (zero? exit))
      {:gate :coverage-governance
       :label "coverage-governance"
       :status :error
       :headline "coverage command failed before producing a valid governance result"
       :cause (or (first-cause-line err) "review the coverage command output")
       :action "Fix the failing coverage command or test runtime before relying on coverage governance."}

      (nil? summary)
      {:gate :coverage-governance
       :label "coverage-governance"
       :status :error
       :headline "coverage output could not be parsed into a governance summary"
       :action "Update the coverage parser or the coverage command format before trusting this gate."}

      :else
      {:gate :coverage-governance
       :label "coverage-governance"
       :status (or (:level summary) :pass)
       :headline (str "overall line coverage "
                      (format "%.2f%%" (:line-coverage summary)))
       :summary summary
       :action "Add or split tests around low-coverage responsibilities before they drift further."})))

(defn skipped-coverage-gate
  []
  {:gate :coverage-governance
   :label "coverage-governance"
   :status :skipped
   :headline "skipped because executable-correctness is not green"
   :action "Recover the test gate first, then rerun coverage governance."})

(defn check-gates
  []
  (let [gates [(run-format-check!)
               (run-static-analysis!)
               (run-structure-governance!)
               (run-test-check!)]
        test-gate (last gates)
        coverage-gate (if (= :pass (:status test-gate))
                        (run-coverage-check!)
                        (skipped-coverage-gate))]
    (conj (vec gates) coverage-gate)))

(def overall-status report/overall-status)
(def print-report! report/print-report!)

(defn -main
  [& _]
  (let [gates (check-gates)
        status (report/overall-status gates)]
    (print-report! gates)
    (when (= :blocked status)
      (System/exit 1))))
