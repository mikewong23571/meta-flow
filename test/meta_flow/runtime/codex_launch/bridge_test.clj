(ns meta-flow.runtime.codex-launch.bridge-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]))

(deftest worker-api-bridge-preserves-allowlisted-env-vars
  (let [script-path (.getCanonicalPath (io/file "script/worker_api.bb"))
        temp-dir (.getCanonicalPath
                  (.toFile (java.nio.file.Files/createTempDirectory
                            "meta-flow-worker-api-env"
                            (make-array java.nio.file.attribute.FileAttribute 0))))
        fake-bin (str temp-dir "/bin")
        env-dump (str temp-dir "/env.txt")
        fake-clojure (str fake-bin "/clojure")
        path-value (str fake-bin ":" (or (System/getenv "PATH") ""))]
    (.mkdirs (io/file fake-bin))
    (spit fake-clojure
          (str "#!/usr/bin/env bash\n"
               "printenv > \"" env-dump "\"\n"
               "exit 0\n"))
    (.setExecutable (io/file fake-clojure) true)
    (let [{:keys [exit err]} (shell/sh "bb"
                                       script-path
                                       "worker-started"
                                       "--db-path" "var/meta-flow.sqlite3"
                                       "--workdir" "."
                                       "--token" "worker-started"
                                       :env {"PATH" path-value
                                             "HOME" (System/getProperty "user.home")
                                             "JAVA_HOME" (or (System/getenv "JAVA_HOME")
                                                             (System/getProperty "java.home"))
                                             "OPENAI_API_KEY" "test-openai-key"
                                             "ANTHROPIC_API_KEY" "test-anthropic-key"})]
      (when-not (zero? exit)
        (throw (ex-info "worker_api.bb env bridge failed"
                        {:exit exit
                         :err err})))
      (let [env-text (slurp env-dump)]
        (testing "the bb-to-clojure bridge preserves incoming credentials"
          (is (.contains env-text "OPENAI_API_KEY=test-openai-key"))
          (is (.contains env-text "ANTHROPIC_API_KEY=test-anthropic-key")))))))
