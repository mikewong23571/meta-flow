(ns meta-flow.lint.coverage.execution
  (:require [meta-flow.lint.coverage.summary :as summary]))

(def governed-runner
  ::kaocha)

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

(defn summary-from-forms
  [forms]
  (let [rows (->> (call! 'cloverage.report/file-stats forms)
                  (map summary/file-stat->coverage-row)
                  vec)
        totals (call! 'cloverage.report/total-stats forms)
        line-coverage (:percent-lines-covered totals 0.0)]
    {:line-coverage line-coverage
     :lowest-namespaces (summary/lowest-covered-namespaces rows)
     :level (summary/classify-line-coverage line-coverage)}))

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
                  (fn [opts]
                    (fn [_test-nses]
                      (let [config (assoc (:kaocha.plugin.cloverage/config opts)
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
       :counts (summary/counts-from-totals totals)
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
