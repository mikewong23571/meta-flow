(ns meta-flow.runtime.codex.fs
  (:require [cheshire.core :as cheshire]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:dynamic *artifact-root-dir*
  "var/artifacts")

(def ^:dynamic *run-root-dir*
  "var/runs")

(defn absolute-path
  [path]
  (.getCanonicalPath (io/file path)))

(defn ensure-directory!
  [path]
  (.mkdirs (io/file path))
  path)

(defn run-workdir
  [run-id]
  (str *run-root-dir* "/" run-id))

(defn artifact-root-path
  [task-id run-id]
  (str *artifact-root-dir* "/" task-id "/" run-id))

(defn definitions-path
  [run-id]
  (str (run-workdir run-id) "/definitions.edn"))

(defn task-path
  [run-id]
  (str (run-workdir run-id) "/task.edn"))

(defn run-path
  [run-id]
  (str (run-workdir run-id) "/run.edn"))

(defn runtime-profile-path
  [run-id]
  (str (run-workdir run-id) "/runtime-profile.edn"))

(defn artifact-contract-path
  [run-id]
  (str (run-workdir run-id) "/artifact-contract.edn"))

(defn worker-prompt-path
  [run-id]
  (str (run-workdir run-id) "/worker-prompt.md"))

(defn process-path
  [run-id]
  (str (run-workdir run-id) "/process.json"))

(defn run-log-path
  [task-id run-id]
  (str (artifact-root-path task-id run-id) "/run.log"))

(defn write-text-file!
  [path content]
  (when-let [parent (.getParentFile (io/file path))]
    (.mkdirs parent))
  (spit path content)
  path)

(defn append-text-file!
  [path content]
  (when-let [parent (.getParentFile (io/file path))]
    (.mkdirs parent))
  (spit path content :append true)
  path)

(defn write-edn-file!
  [path value]
  (write-text-file! path (pr-str value)))

(defn read-edn-file
  [path]
  (when (.exists (io/file path))
    (edn/read-string (slurp path))))

(defn write-json-file!
  [path value]
  (write-text-file! path (cheshire/generate-string value {:pretty true})))

(defn read-json-file
  [path]
  (when (.exists (io/file path))
    (cheshire/parse-string (slurp path) true)))
