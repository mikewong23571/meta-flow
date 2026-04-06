(ns meta-flow.defs.workspace.files
  (:require [clojure.java.io :as io]
            [meta-flow.defs.source :as defs.source])
  (:import (java.nio.file AtomicMoveNotSupportedException Files StandardCopyOption)
           (java.nio.file.attribute FileAttribute)))

(def draft-root-name "drafts")

(defn overlay-file-path
  [overlay-root definition-key]
  (.getPath (io/file overlay-root (defs.source/definition-file definition-key))))

(defn draft-root-path
  [overlay-root]
  (.getPath (io/file overlay-root draft-root-name)))

(defn draft-directory-path
  [overlay-root definition-key]
  (.getPath (io/file (draft-root-path overlay-root)
                     (name definition-key))))

(defn draft-file-name
  [definition-id version]
  (str (namespace definition-id)
       "_"
       (name definition-id)
       "_v"
       version
       ".edn"))

(defn draft-file-path
  [overlay-root definition-key definition-id version]
  (.getPath (io/file (draft-directory-path overlay-root definition-key)
                     (draft-file-name definition-id version))))

(defn ensure-directory!
  [path]
  (.mkdirs (io/file path))
  path)

(defn atomic-write!
  [path content]
  (let [target (io/file path)
        parent (or (.getParentFile target)
                   (throw (ex-info "Atomic write target must have a parent directory"
                                   {:path path})))
        _ (.mkdirs parent)
        temp-path (Files/createTempFile (.toPath parent)
                                        ".meta-flow."
                                        ".tmp"
                                        (make-array FileAttribute 0))
        temp-file (.toFile temp-path)]
    (try
      (spit temp-file content)
      (try
        (Files/move temp-path
                    (.toPath target)
                    (into-array StandardCopyOption
                                [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move temp-path
                      (.toPath target)
                      (into-array StandardCopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      path
      (catch Throwable throwable
        (when (.exists temp-file)
          (.delete temp-file))
        (throw throwable)))))

(defn initialize-overlay!
  ([] (initialize-overlay! defs.source/default-overlay-root))
  ([overlay-root]
   (ensure-directory! overlay-root)
   (ensure-directory! (draft-root-path overlay-root))
   (let [created-files (reduce (fn [paths definition-key]
                                 (let [active-path (overlay-file-path overlay-root definition-key)
                                       draft-path (draft-directory-path overlay-root definition-key)]
                                   (ensure-directory! draft-path)
                                   (if (.exists (io/file active-path))
                                     paths
                                     (do
                                       (atomic-write! active-path "[]\n")
                                       (conj paths active-path)))))
                               []
                               defs.source/additive-definition-keys)]
     {:overlay-root overlay-root
      :draft-root (draft-root-path overlay-root)
      :created-files created-files})))
