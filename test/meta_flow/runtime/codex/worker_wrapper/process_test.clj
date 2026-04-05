(ns meta-flow.runtime.codex.worker-wrapper.process-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.helper :as codex.helper]
            [meta-flow.runtime.codex.process.launch :as codex.launch]
            [meta-flow.runtime.codex.worker :as codex.worker]
            [meta-flow.runtime.codex.worker-wrapper.support :as support]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest codex-worker-run-codex-worker-wraps-codex-exec-with-helper-events
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-codex-exec-worker"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        workdir (str root "/run-1")
        artifact-root (str root "/artifact")
        ctx {:workdir workdir
             :runtime-profile {:runtime-profile/id :runtime-profile/codex-worker
                               :runtime-profile/web-search-enabled? true}
             :task {:task/id "task-1"}
             :run {:run/id "run-1"}
             :artifact-contract {:artifact-contract/required-paths ["manifest.json" "notes.md" "run.log"]}}
        calls (atom [])]
    (.mkdirs (io/file workdir))
    (spit (str workdir "/worker-prompt.md") "prompt body\n")
    (codex.fs/write-json-file! (str workdir "/process.json")
                               {:status "dispatched"
                                :pid 101
                                :command ["bb" "script/worker_api.bb" "codex-worker"]})
    (binding [codex.fs/*run-root-dir* (str root)]
      (with-redefs [store.sqlite/sqlite-state-store (fn [_] ::store)
                    codex.fs/ensure-directory! (fn [path]
                                                 (.mkdirs (io/file path))
                                                 (swap! calls conj [:ensure-directory path]))
                    codex.helper/emit-worker-started! (fn [_ _ options]
                                                        (swap! calls conj [:worker-started options]))
                    codex.helper/emit-heartbeat! (fn [_ _ options]
                                                   (swap! calls conj [:heartbeat options]))
                    codex.helper/emit-worker-exit! (fn [_ _ options]
                                                     (swap! calls conj [:worker-exit options]))
                    codex.helper/emit-artifact-ready! (fn [_ _ options]
                                                        (swap! calls conj [:artifact-ready options]))
                    codex.worker/current-time-ms (let [times (atom [0 0 0 0])]
                                                   (fn []
                                                     (let [value (or (first @times) 0)]
                                                       (swap! times #(if (next %) (vec (rest %)) %))
                                                       value)))
                    codex.worker/start-codex-process! (fn [workdir-now artifact-root-now runtime-profile input]
                                                        (swap! calls conj [:spawn ["codex" "exec" "-"]
                                                                           {:dir workdir-now
                                                                            :artifact-root artifact-root-now
                                                                            :runtime-profile runtime-profile
                                                                            :in input}])
                                                        (spit (str artifact-root "/manifest.json") "{\"status\":\"completed\"}\n")
                                                        (spit (str artifact-root "/notes.md") "done\n")
                                                        (spit (str artifact-root "/run.log") "log\n")
                                                        (support/fake-process {:wait-results [true]
                                                                               :exit-code 0
                                                                               :pid 202}))]
        (codex.worker/run-codex-worker! ctx "var/test.sqlite3" artifact-root "artifact-1")
        (is (= [:ensure-directory artifact-root]
               (nth @calls 0)))
        (is (= [:worker-started {:token "worker-started"
                                 :at (:at (second (nth @calls 1)))}]
               (nth @calls 1)))
        (is (= [:heartbeat {:token "heartbeat-codex-start"
                            :at (:at (second (nth @calls 2)))
                            :status ":worker.status/running"
                            :stage ":worker.stage/research"
                            :message "Launching real codex exec worker"}]
               (nth @calls 2)))
        (is (= [:spawn ["codex" "exec" "-"]
                {:dir workdir
                 :artifact-root artifact-root
                 :runtime-profile {:runtime-profile/id :runtime-profile/codex-worker
                                   :runtime-profile/web-search-enabled? true}
                 :in (str "prompt body\n"
                          "\n"
                          "\n## Codex Smoke Task\n"
                          "Use shell commands only. Do not call the helper script directly; the runtime wrapper owns control-plane callbacks.\n"
                          "Write the required artifact files under `" artifact-root "`:\n"
                          "  manifest.json, notes.md, run.log\n\n"
                          "Required contents:\n"
                          "- `manifest.json`: valid JSON with `task/id`, `run/id`, and `status` = `completed`.\n"
                          "- `notes.md`: a short note mentioning task `task-1` and run `run-1`.\n"
                          "- `run.log`: append a few plain-text lines describing what you created.\n\n"
                          "Stop after the files are written.")}]
               (nth @calls 3)))
        (is (= [:worker-exit {:token "worker-exited"
                              :exit-code 0
                              :cancelled false
                              :at (:at (second (nth @calls 4)))}]
               (nth @calls 4)))
        (is (= [:artifact-ready {:token "artifact-ready"
                                 :artifact-id "artifact-1"
                                 :artifact-root artifact-root
                                 :at (:at (second (nth @calls 5)))}]
               (nth @calls 5)))
        (is (= {:pid 202
                :wrapperPid 101
                :command (codex.launch/codex-exec-command workdir
                                                          {:runtime-profile/web-search-enabled? true})
                :wrapperCommand ["bb" "script/worker_api.bb" "codex-worker"]}
               (select-keys (codex.fs/read-json-file (str workdir "/process.json"))
                            [:pid :wrapperPid :command :wrapperCommand])))))))

(deftest codex-worker-run-codex-worker-does-not-append-smoke-instructions-for-non-smoke-profiles
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-codex-real-worker"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        workdir (str root "/run-1")
        artifact-root (str root "/artifact")
        ctx {:workdir workdir
             :runtime-profile {:runtime-profile/id :runtime-profile/codex-repo-arch
                               :runtime-profile/web-search-enabled? true}
             :task {:task/id "task-1"}
             :run {:run/id "run-1"}
             :artifact-contract {:artifact-contract/required-paths ["architecture.md" "manifest.json" "run.log" "email-receipt.edn"]}}
        calls (atom [])]
    (.mkdirs (io/file workdir))
    (spit (str workdir "/worker-prompt.md") "real prompt body\n")
    (codex.fs/write-json-file! (str workdir "/process.json")
                               {:status "dispatched"
                                :pid 101
                                :command ["bb" "script/worker_api.bb" "codex-worker"]})
    (binding [codex.fs/*run-root-dir* (str root)]
      (with-redefs [store.sqlite/sqlite-state-store (fn [_] ::store)
                    codex.fs/ensure-directory! (fn [path]
                                                 (.mkdirs (io/file path))
                                                 (swap! calls conj [:ensure-directory path]))
                    codex.helper/emit-worker-started! (fn [_ _ options]
                                                        (swap! calls conj [:worker-started options]))
                    codex.helper/emit-heartbeat! (fn [_ _ options]
                                                   (swap! calls conj [:heartbeat options]))
                    codex.helper/emit-worker-exit! (fn [_ _ options]
                                                     (swap! calls conj [:worker-exit options]))
                    codex.helper/emit-artifact-ready! (fn [_ _ options]
                                                        (swap! calls conj [:artifact-ready options]))
                    codex.worker/current-time-ms (constantly 0)
                    codex.worker/start-codex-process! (fn [workdir-now artifact-root-now runtime-profile input]
                                                        (swap! calls conj [:spawn ["codex" "exec" "-"]
                                                                           {:dir workdir-now
                                                                            :artifact-root artifact-root-now
                                                                            :runtime-profile runtime-profile
                                                                            :in input}])
                                                        (spit (str artifact-root "/architecture.md") "# report\n")
                                                        (spit (str artifact-root "/manifest.json") "{\"status\":\"completed\"}\n")
                                                        (spit (str artifact-root "/run.log") "log\n")
                                                        (spit (str artifact-root "/email-receipt.edn") "{:email/status :sent}\n")
                                                        (support/fake-process {:wait-results [true]
                                                                               :exit-code 0
                                                                               :pid 202}))]
        (codex.worker/run-codex-worker! ctx "var/test.sqlite3" artifact-root "artifact-1")
        (is (= [:spawn ["codex" "exec" "-"]
                {:dir workdir
                 :artifact-root artifact-root
                 :runtime-profile {:runtime-profile/id :runtime-profile/codex-repo-arch
                                   :runtime-profile/web-search-enabled? true}
                 :in "real prompt body\n"}]
               (nth @calls 3)))))))

(deftest codex-worker-start-codex-process-exports-artifact-root-and-workdir
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-codex-process-env"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        workdir (.getCanonicalPath (io/file root "run-1"))
        artifact-root (.getCanonicalPath (io/file root "artifact"))
        env-path (.getCanonicalPath (io/file root "env.txt"))]
    (.mkdirs (io/file workdir))
    (.mkdirs (io/file artifact-root))
    (with-redefs [codex.launch/codex-exec-command (fn [_ _]
                                                    ["/bin/sh"
                                                     "-c"
                                                     (str "cat >/dev/null; printf '%s|%s' \"$ARTIFACT_ROOT\" \"$WORKDIR\" > "
                                                          env-path)])]
      (let [proc (#'codex.worker/start-codex-process! workdir
                                                      artifact-root
                                                      {:runtime-profile/id :runtime-profile/codex-repo-arch}
                                                      "prompt body\n")]
        (is (= 0 (.waitFor ^Process proc)))
        (is (= (str artifact-root "|" workdir)
               (slurp env-path)))))))
