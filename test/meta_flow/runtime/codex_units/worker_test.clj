(ns meta-flow.runtime.codex-units.worker-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.helper :as codex.helper]
            [meta-flow.runtime.codex.worker :as codex.worker]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest codex-worker-writes-stub-artifacts-and-respects-cancel-state
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-codex-worker"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        artifact-root (str root "/artifact")
        workdir (str root "/workdir")
        ctx {:workdir workdir
             :task {:task/id "task-1"}
             :run {:run/id "run-1"}
             :artifact-contract {:artifact-contract/required-paths ["manifest.json" "notes.md" "run.log" "extra.txt"]}}]
    (codex.worker/write-stub-artifact! ctx artifact-root)
    (is (.exists (io/file artifact-root "manifest.json")))
    (is (.exists (io/file artifact-root "notes.md")))
    (is (.exists (io/file artifact-root "run.log")))
    (is (.exists (io/file artifact-root "extra.txt")))
    (is (false? (codex.worker/cancelled? workdir)))
    (codex.fs/write-json-file! (str workdir "/process.json")
                               {:status "cancel-requested"})
    (is (true? (codex.worker/cancelled? workdir)))))

(deftest codex-worker-run-stub-worker-drives-success-and-cancel-branches
  (let [ctx {:workdir "/tmp/workdir"
             :task {:task/id "task-1"}
             :run {:run/id "run-1"}
             :artifact-contract {:artifact-contract/required-paths ["manifest.json"]}}
        success-calls (atom [])
        cancel-calls (atom [])]
    (with-redefs [store.sqlite/sqlite-state-store (fn [_] ::store)
                  codex.fs/ensure-directory! (fn [path]
                                               (swap! success-calls conj [:ensure-directory path]))
                  codex.worker/write-stub-artifact! (fn [_ artifact-root]
                                                      (swap! success-calls conj [:write-artifact artifact-root]))
                  codex.worker/cancelled? (let [states (atom [false false false false])]
                                            (fn [_]
                                              (let [current (first @states)]
                                                (swap! states #(if (next %) (vec (rest %)) %))
                                                current)))
                  codex.helper/emit-worker-started! (fn [_ _ options]
                                                      (swap! success-calls conj [:worker-started options]))
                  codex.helper/emit-heartbeat! (fn [_ _ options]
                                                 (swap! success-calls conj [:heartbeat options]))
                  codex.helper/emit-worker-exit! (fn [_ _ options]
                                                   (swap! success-calls conj [:worker-exit options]))
                  codex.helper/emit-artifact-ready! (fn [_ _ options]
                                                      (swap! success-calls conj [:artifact-ready options]))]
      (codex.worker/run-stub-worker! ctx "var/test.sqlite3" "/tmp/artifacts/run-1" "artifact-1")
      (is (= [[:ensure-directory "/tmp/artifacts/run-1"]
              [:worker-started {:token "worker-started"}]
              [:heartbeat {:token "heartbeat-1"
                           :status ":worker.status/running"
                           :stage ":worker.stage/research"}]
              [:heartbeat {:token "progress-1"
                           :status ":worker.status/running"
                           :stage ":worker.stage/synthesizing"
                           :message "Writing managed artifact bundle"}]
              [:write-artifact "/tmp/artifacts/run-1"]
              [:worker-exit {:token "worker-exited"
                             :exit-code 0}]
              [:artifact-ready {:token "artifact-ready"
                                :artifact-id "artifact-1"
                                :artifact-root "/tmp/artifacts/run-1"}]]
             @success-calls)))
    (with-redefs [store.sqlite/sqlite-state-store (fn [_] ::store)
                  codex.fs/ensure-directory! (fn [path]
                                               (swap! cancel-calls conj [:ensure-directory path]))
                  codex.worker/write-stub-artifact! (fn [_ artifact-root]
                                                      (swap! cancel-calls conj [:write-artifact artifact-root]))
                  codex.worker/cancelled? (let [states (atom [true true true true])]
                                            (fn [_]
                                              (let [current (first @states)]
                                                (swap! states #(if (next %) (vec (rest %)) %))
                                                current)))
                  codex.helper/emit-worker-started! (fn [& _]
                                                      (swap! cancel-calls conj [:worker-started]))
                  codex.helper/emit-heartbeat! (fn [& _]
                                                 (swap! cancel-calls conj [:heartbeat]))
                  codex.helper/emit-worker-exit! (fn [_ _ options]
                                                   (swap! cancel-calls conj [:worker-exit options]))
                  codex.helper/emit-artifact-ready! (fn [& _]
                                                      (swap! cancel-calls conj [:artifact-ready]))]
      (codex.worker/run-stub-worker! ctx "var/test.sqlite3" "/tmp/artifacts/run-2" "artifact-2")
      (is (= [[:ensure-directory "/tmp/artifacts/run-2"]
              [:write-artifact "/tmp/artifacts/run-2"]
              [:worker-exit {:token "worker-cancelled"
                             :exit-code 130
                             :cancelled true}]]
             @cancel-calls)))))
