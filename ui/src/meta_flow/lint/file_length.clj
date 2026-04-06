(ns meta-flow.lint.file-length
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ignored-directory-width-suffixes
  #{".md"})

(def warning-threshold 240)

(def error-threshold 300)

(def directory-warning-threshold 7)

(def directory-error-threshold 12)

(defn ignored-directory-width-file?
  [file]
  (some #(str/ends-with? (.getName file) %)
        ignored-directory-width-suffixes))

(defn clojure-source-file?
  [file]
  (and (.isFile file)
       (not (ignored-directory-width-file? file))
       (let [name (.getName file)]
         (or (str/ends-with? name ".clj")
             (str/ends-with? name ".cljc")
             (str/ends-with? name ".cljs")))))

(defn count-lines
  [path]
  (with-open [reader (io/reader path)]
    (count (line-seq reader))))

(defn classify-line-count
  [line-count]
  (cond
    (> line-count error-threshold) :error
    (> line-count warning-threshold) :warning
    :else nil))

(defn file-stat
  [file]
  (let [path (.getPath file)
        line-count (count-lines path)
        level (classify-line-count line-count)]
    {:kind :file-length
     :path path
     :line-count line-count
     :level level}))

(defn collect-file-stats
  [roots]
  (->> roots
       (map io/file)
       (filter #(.exists %))
       (mapcat file-seq)
       (filter clojure-source-file?)
       (map file-stat)
       (sort-by (juxt (comp - :line-count) :path))))

(defn classify-directory-file-count
  [file-count]
  (cond
    (> file-count directory-error-threshold) :error
    (> file-count directory-warning-threshold) :warning
    :else nil))

(defn directory-stat
  [[path file-count]]
  {:kind :directory-width
   :path path
   :file-count file-count
   :level (classify-directory-file-count file-count)})

(defn collect-directory-stats
  [roots]
  (->> roots
       (map io/file)
       (filter #(.exists %))
       (mapcat file-seq)
       (filter clojure-source-file?)
       (map #(.getPath (.getParentFile ^java.io.File %)))
       frequencies
       (map directory-stat)
       (sort-by (juxt (comp - :file-count) :path))))

(defn governance-issues
  [roots]
  (->> (concat (collect-file-stats roots)
               (collect-directory-stats roots))
       (filter :level)
       vec))
