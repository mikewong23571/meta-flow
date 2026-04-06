(ns meta-flow.lint.check.frontend.semantics
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [meta-flow.lint.check.frontend.shared-ui-support :as support]))

(def frontend-source-roots
  ["frontend/src"])

(defn- cljs-file?
  [file]
  (and (.isFile file)
       (str/ends-with? (.getName ^java.io.File file) ".cljs")))

(defn- source-files-under
  [root]
  (->> (io/file root)
       file-seq
       (filter cljs-file?)
       (map #(.getPath ^java.io.File %))
       sort
       vec))

(defn- existing-directory?
  [path]
  (.isDirectory (io/file path)))

(defn- page-source-files
  [root]
  (if (existing-directory? root)
    (source-files-under root)
    []))

(defn- read-top-level-forms
  [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (binding [*read-eval* false
              *default-data-reader-fn* (fn [_ value] value)]
      (loop [forms []]
        (let [form (read {:eof ::eof} reader)]
          (if (= ::eof form)
            forms
            (recur (conj forms form))))))))

(defn- safe-read-top-level-forms
  [path]
  (try
    {:forms (read-top-level-forms path)}
    (catch Throwable throwable
      {:error (.getMessage throwable)})))

(defn- hiccup-node?
  [form]
  (and (vector? form)
       (or (keyword? (first form))
           (symbol? (first form)))))

(defn- element-attrs
  [node]
  (when (and (hiccup-node? node)
             (map? (second node)))
    (second node)))

(defn- element-children
  [node]
  (if (map? (second node))
    (nnext node)
    (next node)))

(defn- class-tokens
  [attrs]
  (->> [(get attrs :className)
        (get attrs :class)]
       (filter string?)
       (mapcat #(str/split % #"\s+"))
       (remove str/blank?)
       set))

(defn- visible-text-literals
  [form]
  (cond
    (string? form)
    (let [trimmed (str/trim form)]
      (if (str/blank? trimmed) [] [trimmed]))

    (vector? form)
    (mapcat visible-text-literals (element-children form))

    (seq? form)
    (mapcat visible-text-literals form)

    (coll? form)
    (mapcat visible-text-literals form)

    :else
    []))

(defn- button-icon-text-findings-in-form
  [path form]
  (letfn [(scan [node]
            (lazy-seq
             (concat
              (when (vector? node)
                (let [attrs (element-attrs node)
                      classes (class-tokens attrs)
                      texts (vec (visible-text-literals node))]
                  (concat
                   (when (and (= :button (first node))
                              (contains? classes "button-icon")
                              (seq texts))
                     [{:filename path
                       :type :button-icon-visible-text
                       :message (str "icon-sized buttons using `.button-icon` must not render visible text; "
                                     "remove `button-icon` or switch to an icon-only control (`"
                                     (first texts) "` found)")}])
                   (mapcat scan (element-children node)))))
              (when (seq? node)
                (mapcat scan node))
              (when (and (coll? node)
                         (not (vector? node))
                         (not (seq? node)))
                (mapcat scan node)))))]
    (vec (scan form))))

(defn button-icon-text-findings
  ([] (button-icon-text-findings frontend-source-roots))
  ([roots]
   (->> roots
        (mapcat page-source-files)
        (mapcat (fn [path]
                  (let [{:keys [forms error]} (safe-read-top-level-forms path)]
                    (if error
                      [{:filename path
                        :type :frontend-semantics-parse-error
                        :message (str "could not read frontend source for semantic checks: " error)}]
                      (mapcat #(button-icon-text-findings-in-form path %) forms)))))
        vec)))

(defn frontend-semantics-gate
  ([] (frontend-semantics-gate frontend-source-roots))
  ([roots]
   (let [findings (button-icon-text-findings roots)]
     {:gate :frontend-semantics-governance
      :label "frontend-semantics-governance"
      :status (if (seq findings) :error :pass)
      :headline (if (seq findings)
                  (str (count findings) " frontend semantic finding(s) require tighter component usage")
                  "frontend semantic component usage looks consistent")
      :findings (support/sorted-findings findings 8)
      :action "Keep `button-icon` reserved for icon-only controls; use standard button variants for visible text labels."})))
