(ns meta-flow.runtime.codex-worker-api.bridge-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.runtime.codex-worker-api.test-support :as worker-api]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.protocol :as store.protocol]))

(deftest helper-bridge-resolves-relative-path-args-before-changing-cwd
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        {:keys [store run workdir]} (worker-api/prepare-dispatched-run! db-path
                                                                        artifacts-dir
                                                                        runs-dir
                                                                        codex-home-dir)
        relative-db-path (str (.relativize (.toPath (io/file workdir))
                                           (.toPath (io/file db-path))))
        script-path (.getCanonicalPath (io/file "script/worker_api.bb"))
        {:keys [exit err]}
        (shell/sh "bb"
                  script-path
                  "worker-started"
                  "--db-path" relative-db-path
                  "--workdir" "."
                  "--token" "worker-started"
                  :dir workdir)]
    (when-not (zero? exit)
      (throw (ex-info "Helper command failed"
                      {:command "worker-started"
                       :exit exit
                       :err err})))
    (testing "the bridge emits events into the original SQLite database and run workdir even from another cwd"
      (is (= [:task.event/worker-started
              :run.event/worker-started]
             (mapv :event/type (store.protocol/list-run-events store (:run/id run))))))))
