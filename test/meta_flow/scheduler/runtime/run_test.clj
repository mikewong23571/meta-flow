(ns meta-flow.scheduler.runtime.run-test
  (:require [clojure.test :refer [deftest is]]
            [meta-flow.scheduler.runtime.run :as scheduler.run]))

(deftest max-run-steps-respects-the-scheduler-run-poll-interval
  (is (= 915
         (scheduler.run/max-run-steps
          {:runtime-profile/worker-timeout-seconds 1800})))
  (is (= 1815
         (scheduler.run/max-run-steps
          {:runtime-profile/worker-timeout-seconds 3600}))))
