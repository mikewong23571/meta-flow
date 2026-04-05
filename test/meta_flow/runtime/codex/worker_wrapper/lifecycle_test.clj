(ns meta-flow.runtime.codex.worker-wrapper.lifecycle-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.helper :as codex.helper]
            [meta-flow.runtime.codex.worker :as codex.worker]
            [meta-flow.runtime.codex.worker-wrapper.support :as support]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest codex-worker-emits-periodic-heartbeats-while-the-real-process-is-still-running
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-codex-heartbeat-worker"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        workdir (str root "/run-1")
        artifact-root (str root "/artifact")
        ctx {:workdir workdir
             :runtime-profile {:runtime-profile/heartbeat-interval-seconds 1}
             :task {:task/id "task-1"}
             :run {:run/id "run-1"}
             :artifact-contract {:artifact-contract/required-paths ["manifest.json"]}}
        heartbeats (atom [])
        current-ms (atom [0 0 1000 1000 2000 2000])]
    (.mkdirs (io/file workdir))
    (spit (str workdir "/worker-prompt.md") "prompt body\n")
    (binding [codex.fs/*run-root-dir* (str root)]
      (with-redefs [store.sqlite/sqlite-state-store (fn [_] ::store)
                    codex.fs/ensure-directory! (fn [_])
                    codex.helper/emit-worker-started! (fn [& _])
                    codex.helper/emit-heartbeat! (fn [_ _ options]
                                                   (swap! heartbeats conj options))
                    codex.helper/emit-worker-exit! (fn [& _])
                    codex.helper/emit-artifact-ready! (fn [& _])
                    codex.worker/current-time-ms (fn []
                                                   (let [value (or (first @current-ms) 2000)]
                                                     (swap! current-ms #(if (next %) (vec (rest %)) %))
                                                     value))
                    codex.worker/start-codex-process! (fn [& _]
                                                        (support/fake-process {:wait-results [false false false true]
                                                                               :exit-code 0}))
                    codex.worker/wait-poll-ms 1]
        (codex.worker/run-codex-worker! ctx "var/test.sqlite3" artifact-root "artifact-1")
        (is (= ["heartbeat-codex-start"
                "heartbeat-codex-wait-1"
                "heartbeat-codex-wait-2"]
               (mapv :token @heartbeats)))))))

(deftest codex-worker-propagates-cancel-requests-to-the-running-process
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-codex-cancel-worker"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        workdir (str root "/run-1")
        artifact-root (str root "/artifact")
        ctx {:workdir workdir
             :runtime-profile {:runtime-profile/heartbeat-interval-seconds 30}
             :task {:task/id "task-1"}
             :run {:run/id "run-1"}
             :artifact-contract {:artifact-contract/required-paths ["manifest.json"]}}
        destroyed? (atom false)
        exits (atom [])
        cancelled-state (atom [false true true])]
    (.mkdirs (io/file workdir))
    (spit (str workdir "/worker-prompt.md") "prompt body\n")
    (binding [codex.fs/*run-root-dir* (str root)]
      (with-redefs [store.sqlite/sqlite-state-store (fn [_] ::store)
                    codex.fs/ensure-directory! (fn [_])
                    codex.helper/emit-worker-started! (fn [& _])
                    codex.helper/emit-heartbeat! (fn [& _])
                    codex.helper/emit-worker-exit! (fn [_ _ options]
                                                     (swap! exits conj options))
                    codex.helper/emit-artifact-ready! (fn [& _])
                    codex.worker/cancelled? (fn [_]
                                              (let [value (first @cancelled-state)]
                                                (swap! cancelled-state #(if (next %) (vec (rest %)) %))
                                                value))
                    codex.worker/start-codex-process! (fn [& _]
                                                        (support/fake-process {:wait-results [false false true]
                                                                               :exit-code 0
                                                                               :on-destroy #(reset! destroyed? true)}))
                    codex.worker/wait-poll-ms 1]
        (codex.worker/run-codex-worker! ctx "var/test.sqlite3" artifact-root "artifact-1")
        (is @destroyed?)
        (is (= [{:token "worker-cancelled"
                 :exit-code 130
                 :cancelled true
                 :at (:at (first @exits))}]
               @exits))))))
