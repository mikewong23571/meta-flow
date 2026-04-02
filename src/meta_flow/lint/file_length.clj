(ns meta-flow.lint.file-length
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def warning-threshold 240)
(def error-threshold 300)
(def directory-warning-threshold 7)
(def directory-error-threshold 12)

(def governance-intent
  (str "Intent: use file length as a governance signal to avoid a single namespace "
       "accumulating too much responsibility. When this warning or error appears, "
       "consider splitting the file by responsibility into smaller namespaces or "
       "extracting helpers/orchestration layers to satisfy lint."))

(def directory-governance-intent
  (str "Intent: use directory width as a governance signal to avoid one layer "
       "accumulating too many sibling namespaces and mixed responsibilities. When "
       "this warning or error appears, consider introducing subdirectories by "
       "responsibility, subdomain, or implementation layer instead of continuing "
       "to add source files at the same level."))

(defn clojure-source-file?
  [file]
  (and (.isFile file)
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

(defn issue-message
  [{:keys [kind path line-count file-count level]}]
  (let [kind (or kind :file-length)
        level-label (str/upper-case (name level))]
    (case kind
      :file-length
      (let [threshold (case level
                        :error error-threshold
                        :warning warning-threshold)
            consequence (case level
                          :error "This blocks lint until the namespace is split down below the error threshold."
                          :warning "This does not fail lint yet, but it should trigger a responsibility review before the file grows further.")]
        (str level-label " [file-length-governance] " path " has " line-count
             " lines, exceeding the " (name level) " threshold of " threshold " lines.\n"
             governance-intent "\n"
             consequence))

      :directory-width
      (let [threshold (case level
                        :error directory-error-threshold
                        :warning directory-warning-threshold)
            consequence (case level
                          :error "This blocks lint until the directory is narrowed below the error threshold by splitting responsibilities into smaller directory layers."
                          :warning "This does not fail lint yet, but it should trigger a directory-level responsibility review before more sibling namespaces are added here.")]
        (str level-label " [directory-width-governance] " path " contains " file-count
             " direct Clojure source files, exceeding the " (name level) " threshold of " threshold " files.\n"
             directory-governance-intent "\n"
             consequence)))))

(defn print-issues!
  [issues]
  (doseq [issue issues]
    (binding [*out* *err*]
      (println (issue-message issue))
      (println))))

(defn print-summary!
  [issues]
  (let [warnings (count (filter #(= :warning (:level %)) issues))
        errors (count (filter #(= :error (:level %)) issues))
        file-length-issues (count (filter #(= :file-length (:kind %)) issues))
        directory-width-issues (count (filter #(= :directory-width (:kind %)) issues))]
    (binding [*out* *err*]
      (println (str "governance summary: "
                    warnings " warning(s), "
                    errors " error(s), "
                    file-length-issues " file-length issue(s), "
                    directory-width-issues " directory-width issue(s).")))))

(defn run-check!
  []
  (let [issues (->> (concat (collect-file-stats ["src"])
                            (collect-directory-stats ["src"]))
                    (filter :level)
                    vec)]
    (when (seq issues)
      (print-issues! issues)
      (print-summary! issues))
    {:issues issues
     :warning-count (count (filter #(= :warning (:level %)) issues))
     :error-count (count (filter #(= :error (:level %)) issues))}))

(defn -main
  [& _]
  (let [{:keys [error-count]} (run-check!)]
    (when (pos? error-count)
      (System/exit 1))))
