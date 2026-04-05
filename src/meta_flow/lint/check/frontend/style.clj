(ns meta-flow.lint.check.frontend.style
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def frontend-style-roots
  ["frontend/public/styles"])

(def raw-color-allowed-files
  #{"tokens.css" "theme.css"})

(def style-directory-warning-threshold 6)
(def style-directory-error-threshold 10)

(def shared-style-warning-threshold 320)
(def shared-style-error-threshold 420)

(def token-style-warning-threshold 240)
(def token-style-error-threshold 320)

(def raw-color-pattern
  #"(?i)(#[0-9a-f]{3,8}\b|rgba?\([^)]*\)|hsla?\([^)]*\))")

(defn- css-file?
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".css")))

(defn- strip-css-comments
  [content]
  (str/replace content
               #"(?s)/\*.*?\*/"
               (fn [match]
                 (apply str (repeat (count (re-seq #"\n" match))
                                    "\n")))))

(defn- file-allows-raw-colors?
  [file]
  (contains? raw-color-allowed-files (.getName ^java.io.File file)))

(defn- token-style-file?
  [file]
  (contains? raw-color-allowed-files (.getName ^java.io.File file)))

(defn- style-files
  [roots]
  (->> roots
       (map io/file)
       (filter #(.exists %))
       (mapcat file-seq)
       (filter css-file?)
       (sort-by #(.getPath ^java.io.File %))))

(defn- count-lines
  [file]
  (with-open [reader (io/reader file)]
    (count (line-seq reader))))

(defn- classify-style-line-count
  [file line-count]
  (if (token-style-file? file)
    (cond
      (> line-count token-style-error-threshold) {:level :error
                                                  :threshold token-style-error-threshold}
      (> line-count token-style-warning-threshold) {:level :warning
                                                    :threshold token-style-warning-threshold}
      :else nil)
    (cond
      (> line-count shared-style-error-threshold) {:level :error
                                                   :threshold shared-style-error-threshold}
      (> line-count shared-style-warning-threshold) {:level :warning
                                                     :threshold shared-style-warning-threshold}
      :else nil)))

(defn style-length-issues
  ([] (style-length-issues frontend-style-roots))
  ([roots]
   (->> (style-files roots)
        (keep (fn [file]
                (let [line-count (count-lines file)]
                  (when-let [{:keys [level threshold]} (classify-style-line-count file line-count)]
                    {:kind :css-file-length
                     :path (.getPath ^java.io.File file)
                     :line-count line-count
                     :level level
                     :threshold threshold}))))
        vec)))

(defn style-directory-issues
  ([] (style-directory-issues frontend-style-roots))
  ([roots]
   (->> (style-files roots)
        (map #(.getPath (.getParentFile ^java.io.File %)))
        frequencies
        (keep (fn [[path file-count]]
                (cond
                  (> file-count style-directory-error-threshold)
                  {:kind :css-directory-width
                   :path path
                   :file-count file-count
                   :level :error
                   :threshold style-directory-error-threshold}

                  (> file-count style-directory-warning-threshold)
                  {:kind :css-directory-width
                   :path path
                   :file-count file-count
                   :level :warning
                   :threshold style-directory-warning-threshold}

                  :else nil)))
        vec)))

(defn raw-color-findings
  ([] (raw-color-findings frontend-style-roots))
  ([roots]
   (->> (style-files roots)
        (remove file-allows-raw-colors?)
        (mapcat (fn [file]
                  (let [path (.getPath ^java.io.File file)
                        content (strip-css-comments (slurp file))]
                    (mapcat (fn [[row line]]
                              (map (fn [literal]
                                     {:filename path
                                      :row row
                                      :type :raw-style-literal
                                      :message (str "raw color literal " literal
                                                    " should be moved into tokens.css/theme.css and consumed via semantic CSS variables")})
                                   (re-seq raw-color-pattern line)))
                            (map-indexed (fn [idx line]
                                           [(inc idx) line])
                                         (str/split-lines content))))))
        vec)))

(defn frontend-style-gate
  ([] (frontend-style-gate frontend-style-roots))
  ([roots]
   (let [findings (raw-color-findings roots)
         issues (vec (concat (style-length-issues roots)
                             (style-directory-issues roots)))
         error-count (count (filter #(= :error (:level %)) issues))
         top-findings (->> findings
                           (sort-by (juxt :filename :row :message))
                           (take 5)
                           vec)
         top-issues (->> issues
                         (sort-by (juxt #(case (:level %)
                                           :error 0
                                           :warning 1
                                           2)
                                        :path))
                         (take 5)
                         vec)]
     {:gate :frontend-style-governance
      :label "frontend-style-governance"
      :status (cond
                (seq findings) :error
                (pos? error-count) :error
                (seq issues) :warning
                :else :pass)
      :headline (cond
                  (seq findings)
                  (str (count findings) " frontend style finding(s) require tokenization")

                  (seq issues)
                  (str (count (filter #(= :warning (:level %)) issues)) " warning(s), "
                       error-count " error(s) in CSS structure governance")

                  :else
                  "no frontend style governance findings")
      :findings top-findings
      :issues top-issues
      :action "Move raw color literals into tokens.css/theme.css, and split oversized CSS files or directories by styling responsibility."})))
