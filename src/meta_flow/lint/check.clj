(ns meta-flow.lint.check
  (:gen-class)
  (:require [meta-flow.lint.check.execution :as execution]
            [meta-flow.lint.check.frontend :as frontend]
            [meta-flow.lint.check.report :as report]
            [meta-flow.lint.coverage :as coverage]
            [meta-flow.lint.file-length :as file-length]))

(def default-source-roots
  ["src" "test" "frontend/src"])

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

(def parse-test-counts execution/parse-test-counts)
(def classify-test-failure execution/classify-test-failure)
(def execution-gates-from-coverage execution/execution-gates-from-coverage)
(def frontend-style-gate frontend/frontend-style-gate)
(def frontend-build-gate frontend/frontend-build-gate)

(defn check-gates
  []
  (let [execution-result (coverage/evaluate-coverage)
        execution-gates (execution-gates-from-coverage execution-result)]
    (into [(run-format-check!)
           (run-static-analysis!)
           (run-structure-governance!)
           (frontend-style-gate)
           (frontend-build-gate)]
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
