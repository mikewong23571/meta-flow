(ns meta-flow.runtime.mock.fs
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:dynamic *artifact-root-dir*
  "var/artifacts")

(def ^:dynamic *run-root-dir*
  "var/runs")

(defn run-workdir
  [run-id]
  (str *run-root-dir* "/" run-id))

(defn artifact-root-path
  [task-id run-id]
  (str *artifact-root-dir* "/" task-id "/" run-id))

(defn runtime-state-path
  [run-id]
  (str (run-workdir run-id) "/runtime-state.edn"))

(defn write-edn-file!
  [path value]
  (spit path (pr-str value)))

(defn read-edn-file
  [path]
  (when (.exists (io/file path))
    (edn/read-string (slurp path))))

(defn write-runtime-state!
  [run-id runtime-state]
  (write-edn-file! (runtime-state-path run-id) runtime-state))

(defn read-runtime-state
  [run-id]
  (read-edn-file (runtime-state-path run-id)))
