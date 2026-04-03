(ns meta-flow.runtime.codex.process-launch-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.launch.support :as launch.support]
            [meta-flow.runtime.codex.process.launch :as codex.launch]))

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
             (codex.launch/launch-mode runtime-profile)))
      (is (= {:launch/mode :launch.mode/codex-exec
              :launch/ready? true
              :launch/provider-env-keys ["OPENAI_API_KEY"]}
             (codex.launch/launch-support runtime-profile)))
      (is (= ["bb"
              (.getCanonicalPath (io/file "script/worker_api.bb"))
              "codex-worker"
              "--db-path" "var/test.sqlite3"
              "--workdir" (.getCanonicalPath (io/file "var/runs/run-1"))]
             (binding [codex.fs/*run-root-dir* "var/runs"]
               (codex.launch/launch-command "var/test.sqlite3" "run-1" runtime-profile))))
      (is (= ["codex"
              "exec"
              "--dangerously-bypass-approvals-and-sandbox"
              "--skip-git-repo-check"
              "-C" "/tmp/work"
              "--search"
              "-"]
             (codex.launch/codex-exec-command "/tmp/work" runtime-profile)))))
  (with-redefs [launch.support/env-value (constantly nil)
                launch.support/codex-command-available? (constantly false)]
    (is (= {:launch/mode :launch.mode/stub-worker
            :launch/ready? true}
           (codex.launch/launch-support {:runtime-profile/env-allowlist ["PATH"]})))
    (is (= {:launch/mode :launch.mode/stub-worker
            :launch/ready? true}
           (codex.launch/ensure-launch-supported!
            {:runtime-profile/env-allowlist ["OPENAI_API_KEY"]}))))
  (with-redefs [launch.support/env-value (fn [key-name]
                                           (case key-name
                                             "META_FLOW_ENABLE_CODEX_SMOKE" "1"
                                             nil))
                launch.support/codex-command-available? (constantly false)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Codex smoke test cannot start: `codex` command not found"
                          (codex.launch/ensure-launch-supported!
                           {:runtime-profile/env-allowlist ["OPENAI_API_KEY"]}))))
  (with-redefs [launch.support/env-value (fn [key-name]
                                           (case key-name
                                             "META_FLOW_ENABLE_CODEX_SMOKE" "1"
                                             nil))
                launch.support/codex-command-available? (constantly true)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"missing provider credentials"
                          (codex.launch/ensure-launch-supported!
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
           (codex.launch/launch-support {:runtime-profile/env-allowlist ["OPENAI_API_KEY"]})))))
