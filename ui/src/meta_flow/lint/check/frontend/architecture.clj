(ns meta-flow.lint.check.frontend.architecture
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [meta-flow.lint.check.frontend.shared-ui :as shared-ui]))

(def shared-frontend-source-files
  [shared-ui/shared-component-facade-file])

(def shared-frontend-source-roots
  [shared-ui/shared-ui-root])

(def shared-frontend-style-files
  ["public/styles/base.css"
   "public/styles/components.css"
   "public/styles/shared/interactive.css"])

(def page-specific-class-pattern
  #"\b(?:scheduler|tasks|defs|preview)-[a-z0-9-]+\b")

(defn- normalize-path
  [path]
  (-> path
      str
      (str/replace "\\" "/")))

(defn- existing-file?
  [path]
  (.isFile (io/file path)))

(defn- existing-directory?
  [path]
  (.isDirectory (io/file path)))

(defn- cljs-file?
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName ^java.io.File file) ".cljs")))

(defn- source-files-under
  [root]
  (->> (io/file root)
       file-seq
       (filter cljs-file?)
       (map #(.getPath ^java.io.File %))))

(defn- shared-source-files-to-scan
  []
  (->> (concat (filter existing-file? shared-frontend-source-files)
               (mapcat (fn [root]
                         (when (existing-directory? root)
                           (source-files-under root)))
                       shared-frontend-source-roots))
       (map normalize-path)
       distinct
       sort
       vec))

(defn- first-page-specific-class
  [line]
  (some->> line
           (re-find page-specific-class-pattern)
           first))

(defn- top-findings
  [findings limit]
  (->> findings
       (sort-by (juxt :filename #(or (:row %) 0) :message))
       (take limit)
       vec))

(defn- shared-source-findings
  []
  (vec
   (mapcat (fn [path]
             (keep (fn [[idx line]]
                     (let [trimmed (str/trim line)
                           class-name (first-page-specific-class line)]
                       (when (and (not (str/starts-with? trimmed ";;"))
                                  class-name)
                         {:filename path
                          :row (inc idx)
                          :type :shared-ui-page-specific-class
                          :message (str "shared frontend source should not reference page-specific class `"
                                        class-name
                                        "`; rename it to a neutral shared class or move the markup into a page namespace")})))
                   (map-indexed vector
                                (str/split-lines (slurp path)))))
           (shared-source-files-to-scan))))

(defn- strip-css-comments
  [content]
  (str/replace content
               #"(?s)/\*.*?\*/"
               (fn [match]
                 (apply str (repeat (count (re-seq #"\n" match))
                                    "\n")))))

(defn- shared-style-findings
  []
  (vec
   (mapcat (fn [path]
             (keep (fn [[idx line]]
                     (when-let [class-name (first-page-specific-class line)]
                       {:filename path
                        :row (inc idx)
                        :type :shared-style-page-specific-class
                        :message (str "shared stylesheet should not define or depend on page-specific class `"
                                      class-name
                                      "`; rename it to a neutral shared selector or move the rule into a page stylesheet")}))
                   (map-indexed vector
                                (str/split-lines (strip-css-comments (slurp path))))))
           (filter existing-file? shared-frontend-style-files))))

(defn frontend-architecture-gate
  []
  (let [findings (vec (concat (shared-source-findings)
                              (shared-style-findings)))]
    {:gate :frontend-architecture-governance
     :label "frontend-architecture-governance"
     :status (if (seq findings) :error :pass)
     :headline (if (seq findings)
                 (str (count findings) " shared-frontend architecture finding(s) require neutral naming")
                 "shared frontend layers use neutral, reusable naming")
     :findings (top-findings findings 8)
     :action "Keep shared UI layers domain-neutral: use page-/detail-/ui-style naming in shared code, and move scheduler/tasks/defs/preview-specific markup or selectors back into page namespaces when needed."}))
