(ns meta-flow.lint.coverage
  (:gen-class)
  (:require [clojure.string :as str]))

(def warning-threshold 88.0)
(def error-threshold 85.0)

(def governed-runner
  ::kaocha)

(def governance-intent
  (str "Intent: use test coverage as a governance signal to keep behavior and "
       "responsibility aligned with executable checks. When this warning or "
       "error appears, consider which responsibilities changed without enough "
       "test coverage, and add or split tests around the uncovered behavior "
       "instead of letting critical paths drift unverified."))

(def coverage-row-pattern
  #"(?m)^\|\s+(.+?)\s+\|\s+([0-9.]+)\s+\|\s+([0-9.]+)\s+\|$")

(defn resolve-var!
  [sym]
  (or (requiring-resolve sym)
      (throw (ex-info (str "Missing governance dependency for " sym)
                      {:symbol sym}))))

(defn call!
  [sym & args]
  (apply (var-get (resolve-var! sym)) args))

(defn throwable->string
  [^Throwable throwable]
  (let [writer (java.io.StringWriter.)
        printer (java.io.PrintWriter. writer)]
    (.printStackTrace throwable printer)
    (.flush printer)
    (str writer)))

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

(defn summary-from-forms
  [forms]
  (let [rows (->> (call! 'cloverage.report/file-stats forms)
                  (map file-stat->coverage-row)
                  vec)
        totals (call! 'cloverage.report/total-stats forms)
        line-coverage (:percent-lines-covered totals 0.0)]
    {:line-coverage line-coverage
     :lowest-namespaces (lowest-covered-namespaces rows)
     :level (classify-line-coverage line-coverage)}))

(defn counts-from-totals
  [totals]
  {:tests (:kaocha.result/count totals 0)
   :passes (:kaocha.result/pass totals 0)
   :errors (:kaocha.result/error totals 0)
   :failures (:kaocha.result/fail totals 0)
   :pending (:kaocha.result/pending totals 0)})

(defn active-suites
  [config]
  (->> (:kaocha/tests config)
       (remove :kaocha.testable/skip)
       vec))

(defn coverage-run-input
  [config]
  (let [suites (active-suites config)
        source-paths (->> suites
                          (mapcat :kaocha/source-paths)
                          distinct
                          vec)
        test-paths (->> suites
                        (mapcat :kaocha/test-paths)
                        distinct
                        vec)
        tests (->> suites
                   (map :kaocha.testable/id)
                   vec)]
    {:test-paths test-paths
     :opts {:output "target/coverage"
            :text? false
            :html? false
            :raw? false
            :emma-xml? false
            :lcov? false
            :codecov? false
            :coveralls? false
            :summary? false
            :fail-threshold 0
            :low-watermark 50
            :high-watermark 80
            :nop? false
            :src-ns-path (if (seq source-paths) source-paths ["src"])
            :test-ns-path (if (seq test-paths) test-paths ["test"])
            :ns-regex []
            :ns-exclude-regex [#"^meta-flow\.lint\..*"]
            :exclude-call []
            :test-ns-regex []
            :runner governed-runner
            :extra-test-ns tests
            :kaocha.plugin.cloverage/config config}}))

(defn register-governed-runner!
  []
  (let [runner-fn (var-get (resolve-var! 'cloverage.coverage/runner-fn))
        api-run! (var-get (resolve-var! 'kaocha.api/run))
        run-hook! (var-get (resolve-var! 'kaocha.plugin/run-hook))
        totals! (var-get (resolve-var! 'kaocha.result/totals))]
    (when-not (contains? (methods runner-fn) governed-runner)
      (.addMethod ^clojure.lang.MultiFn
       runner-fn
                  governed-runner
                  (fn [_opts]
                    (fn [_test-nses]
                      (let [config (assoc (:kaocha.plugin.cloverage/config _opts)
                                          :kaocha/reporter (fn [_])
                                          :kaocha/color? false)
                            result (->> config
                                        api-run!
                                        (run-hook! :kaocha.hooks/post-summary))
                            totals (-> result :kaocha.result/tests totals!)]
                        {:errors (+ (:kaocha.result/error totals 0)
                                    (:kaocha.result/fail totals 0))
                         :totals totals
                         :result result})))))))

(defn execute-governed-coverage!
  []
  (try
    (register-governed-runner!)
    (let [config-source (call! 'kaocha.config/find-config-and-warn "tests.edn")
          config (call! 'kaocha.config/validate!
                        (call! 'kaocha.config/load-config config-source))
          {:keys [opts test-paths]} (coverage-run-input config)
          add-classpath! (var-get (resolve-var! 'kaocha.classpath/add-classpath))
          load-namespaces! (var-get (resolve-var! 'cloverage.coverage/load-namespaces))
          instrument-namespaces! (var-get (resolve-var! 'cloverage.coverage/instrument-namespaces))
          run-tests! (var-get (resolve-var! 'cloverage.coverage/run-tests))
          out-writer (java.io.StringWriter.)
          err-writer (java.io.StringWriter.)
          result (binding [*out* out-writer
                           *err* err-writer]
                   (with-bindings {#'*ns* (find-ns 'cloverage.coverage)
                                   (resolve-var! 'cloverage.coverage/*covered*) (atom [])
                                   (resolve-var! 'cloverage.coverage/*exit-after-test*) false
                                   (resolve-var! 'cloverage.debug/*debug*) false}
                     (run! add-classpath! test-paths)
                     (let [{:keys [test-nses ordered-nses]} (load-namespaces! opts [])
                           _ (instrument-namespaces! opts ordered-nses)]
                       (run-tests! opts test-nses))))
          totals (:totals (:test-result result))
          forms (:forms result)
          out (str out-writer)
          err (str err-writer)]
      {:exit (or (:errors (:test-result result))
                 (:num-errors result)
                 0)
       :out out
       :err err
       :combined (str out err)
       :totals totals
       :counts (counts-from-totals totals)
       :summary (when forms
                  (summary-from-forms forms))
       :forms forms})
    (catch Throwable throwable
      (let [stacktrace (throwable->string throwable)]
        {:exit 1
         :out ""
         :err stacktrace
         :combined stacktrace
         :exception throwable
         :summary nil}))))

(defn evaluate-coverage
  []
  (execute-governed-coverage!))

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
