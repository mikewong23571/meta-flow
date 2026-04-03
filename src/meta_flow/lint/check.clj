(ns meta-flow.lint.check
  (:gen-class)
  (:require [meta-flow.lint.check.report :as report]
            [meta-flow.lint.check.shared :as shared]
            [meta-flow.lint.coverage :as coverage]
            [meta-flow.lint.file-length :as file-length]))

(def default-source-roots
  ["src" "test"])

(defn resolve-var!
  [sym]
  (or (requiring-resolve sym)
      (throw (ex-info (str "Missing governance dependency for " sym)
                      {:symbol sym}))))

(defn parse-kondo-targets
  [extra-args]
  (cond
    (empty? extra-args) default-source-roots
    (= "--lint" (first extra-args)) (vec (rest extra-args))
    :else (vec extra-args)))

(defn run-format-check!
  ([] (run-format-check! default-source-roots))
  ([paths]
   (let [load-config (var-get (resolve-var! 'cljfmt.config/load-config))
         reporter (var-get (resolve-var! 'cljfmt.report/clojure))
         check-no-config (var-get (resolve-var! 'cljfmt.tool/check-no-config))
         summary (with-bindings {(resolve-var! 'cljfmt.report/*no-output*) true}
                   (check-no-config (merge (load-config)
                                           {:paths (if (seq paths) (vec paths) default-source-roots)
                                            :report reporter})))
         results (:results summary)
         counts (:counts results)
         incorrect-files (->> (keys (:incorrect-files results))
                              sort
                              vec)
         error-count (:error counts 0)
         incorrect-count (:incorrect counts 0)
         headline (cond
                    (pos? error-count)
                    (str error-count " file(s) could not be parsed for formatting")

                    (pos? incorrect-count)
                    (str incorrect-count " file(s) formatted incorrectly")

                    :else
                    "all tracked source roots are formatted")]
     {:gate :format-hygiene
      :label "format-hygiene"
      :status (if (or (pos? error-count) (pos? incorrect-count))
                :error
                :pass)
      :headline headline
      :incorrect-files incorrect-files
      :action "Run `bb fmt` and rerun the governance gate."})))

(defn run-static-analysis!
  ([] (run-static-analysis! []))
  ([extra-args]
   (try
     (let [run-kondo! (var-get (resolve-var! 'clj-kondo.core/run!))
           lint-targets (parse-kondo-targets extra-args)
           result (run-kondo! {:lint lint-targets
                               :config {:output {:summary true}}})
           findings (vec (:findings result))
           summary (:summary result)
           top-findings (->> findings
                             (sort-by (juxt :filename :row :col :type))
                             (take 5)
                             vec)
           headline (if (seq findings)
                      (str (count findings) " static-analysis finding(s) require attention")
                      "no static-analysis findings")]
       {:gate :static-analysis
        :label "static-analysis"
        :status (if (seq findings) :error :pass)
        :headline headline
        :summary summary
        :findings top-findings
        :action "Fix the reported clj-kondo findings before trusting downstream behavior checks."})
     (catch Throwable throwable
       {:gate :static-analysis
        :label "static-analysis"
        :status :error
        :headline "static analysis failed before producing a valid result"
        :cause (.getMessage throwable)
        :action "Fix the static-analysis runner before trusting downstream behavior checks."}))))

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

(defn check-gates
  []
  (let [execution-result (coverage/evaluate-coverage)
        execution-gates (execution-gates-from-coverage execution-result)]
    (into [(run-format-check!)
           (run-static-analysis!)
           (run-structure-governance!)]
          execution-gates)))

(def overall-status report/overall-status)
(def print-report! report/print-report!)

(defn finish-process!
  [exit-code]
  (shutdown-agents)
  (when (some? exit-code)
    (System/exit exit-code)))

(defn -main
  [& _]
  (let [gates (check-gates)
        status (report/overall-status gates)]
    (print-report! gates)
    (finish-process! (when (= :blocked status)
                       1))))
