(ns meta-flow.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [meta-flow.cli :as cli]
            [meta-flow.main :as main]))

(deftest run-command-delegates-cli-args-and-returns-success
  (let [calls (atom [])]
    (with-redefs [cli/dispatch-command! (fn [args]
                                          (swap! calls conj args))]
      (is (= 0 (#'main/run-command! ["defs" "validate"])))
      (is (= [["defs" "validate"]] @calls)))))

(deftest run-command-prints-errors-and-returns-non-zero
  (let [writer (java.io.StringWriter.)
        err-output (binding [*err* writer]
                     (with-redefs [cli/dispatch-command! (fn [_]
                                                           (throw (ex-info "boom"
                                                                           {:command ["bad"]})))]
                       (is (= 1 (#'main/run-command! ["bad"])))
                       (str writer)))]
    (is (str/includes? err-output "Error: boom"))
    (is (str/includes? err-output ":command [\"bad\"]"))))

(deftest main-exits-only-when-run-command-fails
  (let [exit-calls (atom [])]
    (with-redefs [main/exit! (fn [status]
                               (swap! exit-calls conj status)
                               nil)]
      (with-redefs-fn {#'main/run-command! (fn [_] 0)}
        (fn []
          (is (nil? (main/-main "defs" "validate")))))
      (with-redefs-fn {#'main/run-command! (fn [_] 1)}
        (fn []
          (is (nil? (main/-main "broken")))))
      (is (= [1] @exit-calls)))))
