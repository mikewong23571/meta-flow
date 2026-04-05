(ns meta-flow.lint.coverage-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.lint.coverage :as coverage]))

(def sample-output
  (str "|----------------------------------------------+---------+---------|\n"
       "|                                    Namespace | % Forms | % Lines |\n"
       "|----------------------------------------------+---------+---------|\n"
       "|                               meta-flow.main |    6.98 |   27.27 |\n"
       "|                                meta-flow.cli |   53.53 |   61.16 |\n"
       "|                   meta-flow.lint.file-length |   28.33 |   44.55 |\n"
       "|                          meta-flow.scheduler |   91.89 |   93.75 |\n"
       "|                                    ALL FILES |   79.95 |   87.71 |\n"
       "|----------------------------------------------+---------+---------|\n"))

(deftest parse-summary-extracts-overall-and-lowest-covered-namespaces
  (let [summary (coverage/parse-summary sample-output)]
    (is (= 87.71 (:line-coverage summary)))
    (is (= :warning (:level summary)))
    (is (= ["meta-flow.main" "meta-flow.cli" "meta-flow.scheduler"]
           (mapv :namespace (take 3 (:lowest-namespaces summary)))))))

(deftest classify-line-coverage-uses-warning-and-error-thresholds
  (is (nil? (coverage/classify-line-coverage 88.0)))
  (is (= :warning (coverage/classify-line-coverage 87.99)))
  (is (= :warning (coverage/classify-line-coverage 85.0)))
  (is (= :error (coverage/classify-line-coverage 84.99))))

(deftest counts-from-totals-extracts-kaocha-keys
  (is (= {:tests 3 :passes 10 :errors 1 :failures 2 :pending 0}
         (coverage/counts-from-totals {:kaocha.result/count 3
                                       :kaocha.result/pass 10
                                       :kaocha.result/error 1
                                       :kaocha.result/fail 2
                                       :kaocha.result/pending 0}))))

(deftest issue-message-explains-coverage-governance-intent
  (testing "warning copy explains that coverage drift should trigger responsibility review"
    (let [message (coverage/issue-message {:line-coverage 87.71
                                           :level :warning
                                           :lowest-namespaces [{:namespace "meta-flow.main"
                                                                :line-coverage 27.27}
                                                               {:namespace "meta-flow.cli"
                                                                :line-coverage 61.16}]})]
      (is (str/includes? message "WARNING [coverage-governance]"))
      (is (str/includes? message "overall line coverage is 87.71%"))
      (is (str/includes? message "threshold of 88.00%"))
      (is (str/includes? message "keep behavior and responsibility aligned with executable checks"))
      (is (str/includes? message "Lowest-covered namespaces: meta-flow.main 27.27%, meta-flow.cli 61.16%"))
      (is (str/includes? message "responsibilities are changing without matching executable checks"))))
  (testing "error copy explains that governance is blocked until coverage recovers"
    (let [message (coverage/issue-message {:line-coverage 82.50
                                           :level :error
                                           :lowest-namespaces [{:namespace "meta-flow.main"
                                                                :line-coverage 27.27}]})]
      (is (str/includes? message "ERROR [coverage-governance]"))
      (is (str/includes? message "threshold of 85.00%"))
      (is (str/includes? message "blocks coverage governance")))))

(deftest main-finishes-with-expected-exit-code
  (let [calls (atom [])]
    (with-redefs [coverage/evaluate-coverage (fn []
                                               {:exit 0
                                                :summary (coverage/parse-summary sample-output)})
                  coverage/finish-process! (fn [exit-code]
                                             (swap! calls conj exit-code))]
      (coverage/-main)
      (is (= [nil] @calls))))
  (let [calls (atom [])]
    (with-redefs [coverage/evaluate-coverage (fn []
                                               {:exit 0
                                                :summary (coverage/parse-summary
                                                          (str/replace sample-output "87.71" "84.50"))})
                  coverage/finish-process! (fn [exit-code]
                                             (swap! calls conj exit-code))]
      (coverage/-main)
      (is (= [1] @calls)))))

(deftest main-prefers-plain-test-exit-over-coverage-runner-exit
  (let [calls (atom [])
        stderr (java.io.StringWriter.)]
    (binding [*err* stderr]
      (with-redefs [coverage/evaluate-coverage (fn []
                                                 {:exit 3
                                                  :err "coverage runner noise"
                                                  :test-exit 1
                                                  :test-err "plain test failure"
                                                  :summary (coverage/parse-summary sample-output)})
                    coverage/finish-process! (fn [exit-code]
                                               (swap! calls conj exit-code))]
        (coverage/-main)))
    (is (= [1] @calls))
    (is (str/includes? (str stderr) "plain test failure"))))
