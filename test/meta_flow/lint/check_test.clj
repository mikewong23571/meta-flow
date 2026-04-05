(ns meta-flow.lint.check-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [meta-flow.lint.check :as check]
            [meta-flow.lint.file-length :as file-length])
  (:import (java.nio.file Files Path)))

(defn temp-dir-path
  []
  (Files/createTempDirectory "meta-flow-governance-test" (make-array java.nio.file.attribute.FileAttribute 0)))

(defn delete-tree!
  [^Path path]
  (let [file (.toFile path)]
    (when (.exists file)
      (doseq [entry (reverse (file-seq file))]
        (.delete ^java.io.File entry)))))

(defn write-file!
  [^Path root relative-path content]
  (let [target (.resolve root relative-path)]
    (Files/createDirectories (.getParent target) (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (.toFile target) content)
    (.toString target)))

(deftest parse-test-counts-detects-pass-and-failure-shapes
  (is (= {:tests 149 :assertions 804 :errors 0 :failures 0}
         (check/parse-test-counts "149 tests, 804 assertions, 0 failures.")))
  (is (= {:tests 1 :assertions 1 :errors 1 :failures 0}
         (check/parse-test-counts "1 tests, 1 assertions, 1 errors, 0 failures.")))
  (is (= :load-failure
         (check/classify-test-failure "ERROR in unit\nFailed loading tests:\nCaused by: java.io.FileNotFoundException")))
  (is (= :assertion-failure
         (check/classify-test-failure "FAIL in sample\nexpected: ...")))
  (is (= :runtime-error
         (check/classify-test-failure "ERROR in sample\njava.lang.Exception"))))

(deftest format-check-detects-temporary-format-drift
  (let [root (temp-dir-path)]
    (try
      (write-file! root "src/bad.clj"
                   (str "(ns temp.bad)\n"
                        "(defn foo\n"
                        "[x]\n"
                        "(+ x 1))\n"))
      (let [result (check/run-format-check! [(.toString (.resolve root "src"))])]
        (is (= :error (:status result)))
        (is (str/includes? (:headline result) "formatted incorrectly"))
        (is (= 1 (count (:incorrect-files result)))))
      (finally
        (delete-tree! root)))))

(deftest static-analysis-detects-temporary-lint-violations
  (let [root (temp-dir-path)]
    (try
      (write-file! root "temp/bad.clj"
                   (str "(ns temp.bad)\n"
                        "(defn broken [] missing-symbol)\n"))
      (let [result (check/run-static-analysis! ["--lint" (.toString root)])]
        (is (= :error (:status result)))
        (is (= 1 (get-in result [:summary :error])))
        (is (= :unresolved-symbol (:type (first (:findings result)))))
        (is (str/includes? (:message (first (:findings result))) "missing-symbol")))
      (finally
        (delete-tree! root)))))

(deftest structure-governance-detects-temporary-oversized-files-and-directories
  (let [root (temp-dir-path)]
    (try
      (write-file! root "src/meta_flow/huge.clj"
                   (str "(ns meta-flow.huge)\n"
                        (apply str (repeat 241 ";; filler\n"))))
      (doseq [idx (range (inc file-length/directory-warning-threshold))]
        (write-file! root (format "src/meta_flow/wide_%s.clj" idx)
                     (format "(ns meta-flow.wide-%s)\n(def x %s)\n" idx idx)))
      (let [issues (file-length/governance-issues [(.toString (.resolve root "src"))])
            gate (check/run-structure-governance! [(.toString (.resolve root "src"))])]
        (is (= :warning (:status gate)))
        (is (some #(and (= :file-length (:kind %))
                        (= :warning (:level %)))
                  issues))
        (is (some #(and (= :directory-width (:kind %))
                        (= :warning (:level %)))
                  issues)))
      (finally
        (delete-tree! root)))))

(deftest overall-status-prioritizes-blockers-over-warnings
  (is (= :blocked
         (check/overall-status [{:status :pass}
                                {:status :warning}
                                {:status :error}])))
  (is (= :warning
         (check/overall-status [{:status :pass}
                                {:status :warning}])))
  (is (= :pass
         (check/overall-status [{:status :pass}
                                {:status :skipped}]))))

(deftest execution-gates-share-one-coverage-result
  (let [[test-gate coverage-gate]
        (check/execution-gates-from-coverage {:exit 0
                                              :counts {:tests 154}
                                              :combined ""
                                              :summary {:line-coverage 88.94
                                                        :level nil
                                                        :lowest-namespaces []}})]
    (is (= :pass (:status test-gate)))
    (is (= "154 tests passed" (:headline test-gate)))
    (is (= :pass (:status coverage-gate)))
    (is (= "overall line coverage 88.94%" (:headline coverage-gate)))))

(deftest execution-gates-prefer-plain-test-result-when-coverage-runner-is-noisy
  (let [[test-gate coverage-gate]
        (check/execution-gates-from-coverage {:exit 3
                                              :counts {:tests 154
                                                       :errors 3}
                                              :combined "Unexpected end of file from server"
                                              :test-exit 0
                                              :test-counts {:tests 181}
                                              :test-combined ""
                                              :summary {:line-coverage 87.84
                                                        :level :warning
                                                        :lowest-namespaces []}})]
    (is (= :pass (:status test-gate)))
    (is (= "181 tests passed" (:headline test-gate)))
    (is (= :warning (:status coverage-gate)))
    (is (= "overall line coverage 87.84%" (:headline coverage-gate)))))

(deftest main-finishes-with-expected-exit-code
  (let [calls (atom [])]
    (with-redefs [check/check-gates (fn [] [{:status :pass}])
                  check/print-report! (fn [_])
                  check/finish-process! (fn [exit-code]
                                          (swap! calls conj exit-code))]
      (check/-main)
      (is (= [nil] @calls))))
  (let [calls (atom [])]
    (with-redefs [check/check-gates (fn [] [{:status :error}])
                  check/print-report! (fn [_])
                  check/finish-process! (fn [exit-code]
                                          (swap! calls conj exit-code))]
      (check/-main)
      (is (= [1] @calls)))))
