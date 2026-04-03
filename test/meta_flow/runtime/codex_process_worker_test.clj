(ns meta-flow.runtime.codex-process-worker-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.helper :as codex.helper]
            [meta-flow.runtime.codex.launch.support :as launch.support]
            [meta-flow.runtime.codex.process :as codex.process]
            [meta-flow.runtime.codex.worker :as codex.worker]
            [meta-flow.store.sqlite :as store.sqlite]))

(defn- fake-process
  [{:keys [wait-results exit-code on-destroy pid]
    :or {pid 4242}}]
  (let [wait-state (atom wait-results)
        destroyed? (atom false)]
    (proxy [Process] []
      (getOutputStream []
        (java.io.ByteArrayOutputStream.))
      (getInputStream []
        (java.io.ByteArrayInputStream. (byte-array 0)))
      (getErrorStream []
        (java.io.ByteArrayInputStream. (byte-array 0)))
      (waitFor
        ([]
         (while (not (or @destroyed?
                         (true? (first @wait-state))))
           (Thread/sleep 1))
         (long (or exit-code 0)))
        ([timeout _unit]
         (let [result (if @destroyed?
                        true
                        (boolean (first @wait-state)))]
           (when (seq @wait-state)
             (swap! wait-state #(if (next %) (vec (rest %)) %)))
           result)))
      (exitValue []
        (int (or exit-code 0)))
      (pid []
        (long pid))
      (destroy []
        (reset! destroyed? true)
        (when on-destroy
          (on-destroy)))
      (destroyForcibly []
        (reset! destroyed? true)
        (when on-destroy
          (on-destroy))
        this)
      (isAlive []
        (not (or @destroyed?
                 (true? (first @wait-state))))))))

(deftest codex-process-launch-mode-and-command-follow-the-opt-in-smoke-flag
  (let [runtime-profile {:runtime-profile/web-search-enabled? true
                         :runtime-profile/helper-script-path "script/worker_api.bb"
                         :runtime-profile/env-allowlist ["PATH" "OPENAI_API_KEY"]}]
    (with-redefs [launch.support/env-value (fn [key-name]
                                             (case key-name
                                               "META_FLOW_ENABLE_CODEX_SMOKE" "1"
                                               "OPENAI_API_KEY" "test-key"
                                               "PATH" "/usr/bin"
                                               nil))
                  launch.support/codex-command-available? (constantly true)]
      (is (= :launch.mode/codex-exec
             (codex.process/launch-mode runtime-profile)))
      (is (= {:launch/mode :launch.mode/codex-exec
              :launch/ready? true
              :launch/provider-env-keys ["OPENAI_API_KEY"]}
             (codex.process/launch-support runtime-profile)))
      (is (= ["bb"
              (.getCanonicalPath (io/file "script/worker_api.bb"))
              "codex-worker"
              "--db-path" "var/test.sqlite3"
              "--workdir" (.getCanonicalPath (io/file "var/runs/run-1"))]
             (binding [codex.fs/*run-root-dir* "var/runs"]
               (codex.process/launch-command "var/test.sqlite3" "run-1" runtime-profile))))
      (is (= ["codex"
              "exec"
              "--dangerously-bypass-approvals-and-sandbox"
              "--skip-git-repo-check"
              "-C" "/tmp/work"
              "--search"
              "-"]
             (codex.process/codex-exec-command "/tmp/work" runtime-profile)))))
  (with-redefs [launch.support/env-value (constantly nil)
                launch.support/codex-command-available? (constantly false)]
    (is (= {:launch/mode :launch.mode/stub-worker
            :launch/ready? true}
           (codex.process/launch-support {:runtime-profile/env-allowlist ["PATH"]})))
    (is (= {:launch/mode :launch.mode/stub-worker
            :launch/ready? true}
           (codex.process/ensure-launch-supported!
            {:runtime-profile/env-allowlist ["OPENAI_API_KEY"]}))))
  (with-redefs [launch.support/env-value (fn [key-name]
                                           (case key-name
                                             "META_FLOW_ENABLE_CODEX_SMOKE" "1"
                                             nil))
                launch.support/codex-command-available? (constantly false)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Codex smoke test cannot start: `codex` command not found"
                          (codex.process/ensure-launch-supported!
                           {:runtime-profile/env-allowlist ["OPENAI_API_KEY"]}))))
  (with-redefs [launch.support/env-value (fn [key-name]
                                           (case key-name
                                             "META_FLOW_ENABLE_CODEX_SMOKE" "1"
                                             nil))
                launch.support/codex-command-available? (constantly true)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"missing provider credentials"
                          (codex.process/ensure-launch-supported!
                           {:runtime-profile/env-allowlist ["OPENAI_API_KEY"
                                                            "ANTHROPIC_API_KEY"]}))))
  (with-redefs [launch.support/env-value (fn [key-name]
                                           (case key-name
                                             "META_FLOW_ENABLE_CODEX_SMOKE" "1"
                                             "OPENAI_API_KEY" "   "
                                             nil))
                launch.support/codex-command-available? (constantly true)]
    (is (= {:launch/mode :launch.mode/codex-exec
            :launch/ready? false
            :launch/message "Codex smoke test cannot start: missing provider credentials for configured runtime profile"
            :launch/provider-env-keys ["OPENAI_API_KEY"]}
           (codex.process/launch-support {:runtime-profile/env-allowlist ["OPENAI_API_KEY"]})))))

(deftest codex-worker-run-codex-worker-wraps-codex-exec-with-helper-events
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-codex-exec-worker"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        workdir (str root "/run-1")
        artifact-root (str root "/artifact")
        ctx {:workdir workdir
             :runtime-profile {:runtime-profile/web-search-enabled? true}
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
                    codex.worker/start-codex-process! (fn [workdir-now runtime-profile input]
                                                        (swap! calls conj [:spawn ["codex" "exec" "-"]
                                                                           {:dir workdir-now
                                                                            :runtime-profile runtime-profile
                                                                            :in input}])
                                                        (spit (str artifact-root "/manifest.json") "{\"status\":\"completed\"}\n")
                                                        (spit (str artifact-root "/notes.md") "done\n")
                                                        (spit (str artifact-root "/run.log") "log\n")
                                                        (fake-process {:wait-results [true]
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
                 :runtime-profile {:runtime-profile/web-search-enabled? true}
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
                :command (codex.process/codex-exec-command workdir
                                                           {:runtime-profile/web-search-enabled? true})
                :wrapperCommand ["bb" "script/worker_api.bb" "codex-worker"]}
               (select-keys (codex.fs/read-json-file (str workdir "/process.json"))
                            [:pid :wrapperPid :command :wrapperCommand])))))))

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
                                                        (fake-process {:wait-results [false false false true]
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
                                                        (fake-process {:wait-results [false false true]
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
