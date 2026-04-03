#!/usr/bin/env bb

(require '[babashka.process :as process]
         '[clojure.java.io :as io])

(def ^:private path-options
  #{"--db-path" "--workdir" "--artifact-root"})

(defn absolutize-path-args
  [args]
  (loop [remaining args
         normalized []]
    (if (empty? remaining)
      normalized
      (let [[arg & rest] remaining]
        (if (contains? path-options arg)
          (let [[path-value & after-path] rest]
            (when (nil? path-value)
              (throw (ex-info (str "Missing value for " arg)
                              {:args args
                               :option arg})))
            (recur after-path
                   (conj normalized arg (.getCanonicalPath (io/file path-value)))))
          (recur rest (conj normalized arg)))))))

(let [script-file (io/file *file*)
      repo-root (.getCanonicalPath (.getParentFile (.getParentFile script-file)))
      java-home (System/getProperty "java.home")
      [subcommand & cli-args] *command-line-args*
      env (assoc (into {} (System/getenv))
                 "HOME" (System/getProperty "user.home")
                 "JAVA_HOME" (or (System/getenv "JAVA_HOME")
                                 java-home))
      command (into ["clojure" "-M" "-m" "meta-flow.runtime.codex.worker-api" subcommand]
                    (absolutize-path-args cli-args))
      proc (process/process command
                            {:dir repo-root
                             :inherit true
                             :env env})
      result @proc
      exit-code (if (map? result)
                  (:exit result)
                  result)]
  (System/exit (long (or exit-code 0))))
