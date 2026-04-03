(ns meta-flow.lint.check.execution
  (:require [meta-flow.lint.check.shared :as shared]))

(def test-count-pattern
  #"(?m)(\d+)\s+tests?,\s+(\d+)\s+assertions?,(?:\s+(\d+)\s+errors?,)?\s+(\d+)\s+failures?")

(defn parse-test-counts
  [text]
  (when-let [[_ tests assertions errors failures] (re-find test-count-pattern
                                                           (shared/strip-ansi text))]
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

(defn test-gate-from-coverage
  [{:keys [exit counts combined]}]
  (let [failure-type (when-not (zero? exit)
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
    {:gate :executable-correctness
     :label "executable-correctness"
     :status (if (zero? exit) :pass :error)
     :headline headline
     :counts counts
     :failure-type failure-type
     :cause (when-not (zero? exit)
              (first-cause-line combined))
     :action "Fix test load, compile, or runtime breakage before trusting coverage governance."}))

(defn coverage-gate-from-result
  [{:keys [summary]}]
  (cond
    (nil? summary)
    {:gate :coverage-governance
     :label "coverage-governance"
     :status :error
     :headline "coverage output could not be parsed into a governance summary"
     :action "Update the coverage parser or governance runner before trusting this gate."}

    :else
    {:gate :coverage-governance
     :label "coverage-governance"
     :status (or (:level summary) :pass)
     :headline (str "overall line coverage "
                    (format "%.2f%%" (:line-coverage summary)))
     :summary summary
     :action "Add or split tests around low-coverage responsibilities before they drift further."}))

(defn skipped-coverage-gate
  []
  {:gate :coverage-governance
   :label "coverage-governance"
   :status :skipped
   :headline "skipped because executable-correctness is not green"
   :action "Recover the test gate first, then rerun coverage governance."})

(defn execution-gates-from-coverage
  [result]
  (let [test-gate (test-gate-from-coverage result)]
    [test-gate
     (if (= :pass (:status test-gate))
       (coverage-gate-from-result result)
       (skipped-coverage-gate))]))
