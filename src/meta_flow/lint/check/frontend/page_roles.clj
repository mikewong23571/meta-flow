(ns meta-flow.lint.check.frontend.page-roles
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [meta-flow.lint.check.frontend.shared-ui-support :as support]))

(def frontend-pages-root
  "frontend/src/meta_flow_ui/pages")

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

(defn- normalize-path
  [path]
  (str/replace (str path) "\\" "/"))

(defn- existing-directory?
  [path]
  (.isDirectory (io/file path)))

(defn- page-source-files
  [root]
  (if (existing-directory? root)
    (->> (source-files-under root)
         (map normalize-path)
         sort
         vec)
    []))

(defn- read-top-level-forms
  [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (binding [*read-eval* false]
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

(defn- ns-dependencies
  [path]
  (let [{:keys [forms]} (safe-read-top-level-forms path)
        ns-form (first forms)]
    (->> (rest ns-form)
         (filter seq?)
         (mapcat (fn [clause]
                   (when (= :require (first clause))
                     (rest clause))))
         (keep (fn [spec]
                 (when (and (vector? spec)
                            (symbol? (first spec)))
                   (str (first spec)))))
         vec)))

(defn- authoring-state-file?
  [path]
  (str/ends-with? (normalize-path path) "/authoring/state.cljs"))

(defn- authoring-orchestration-file?
  [path]
  (boolean (re-find #"/authoring/(bootstrap|mutate|read|reset|state)\.cljs$"
                    (normalize-path path))))

(defn- authoring-non-orchestration-file?
  [path]
  (and (boolean (re-find #"/authoring/" (normalize-path path)))
       (not (authoring-orchestration-file? path))))

(defn- allowed-authoring-facade-form?
  [form]
  (or (and (seq? form)
           (= 'ns (first form)))
      (and (seq? form)
           (= 'def (first form))
           (= 3 (count form))
           (symbol? (second form))
           (symbol? (nth form 2)))))

(defn- authoring-facade-findings
  [pages-root]
  (vec
   (mapcat (fn [path]
             (if-not (authoring-state-file? path)
               []
               (let [{:keys [forms error]} (safe-read-top-level-forms path)]
                 (cond
                   error
                   [{:filename path
                     :type :page-role-facade-parse-error
                     :message (str "could not read authoring state facade: " error)}]

                   :else
                   (->> forms
                        (remove allowed-authoring-facade-form?)
                        (map (fn [form]
                               (let [form-name (when (and (seq? form)
                                                          (symbol? (second form)))
                                                 (name (second form)))]
                                 {:filename path
                                  :type :page-role-facade-implementation
                                  :message (str "authoring/state.cljs must stay a thin facade; move "
                                                (if form-name
                                                  (str "`" form-name "`")
                                                  "this form")
                                                " into bootstrap, read, mutate, or reset and re-export it instead")})))
                        vec)))))
           (page-source-files pages-root))))

(defn- page-role-layering-violation
  [dependency]
  (cond
    (= dependency "meta-flow-ui.http")
    "only authoring orchestration files may depend on meta-flow-ui.http; route requests through read/mutate and pass actions downward"

    (or (= dependency "meta-flow-ui.state")
        (str/starts-with? dependency "meta-flow-ui.state."))
    "only authoring orchestration files may depend on meta-flow-ui.state; receive state via the page orchestration layer"

    :else nil))

(defn- authoring-layering-findings
  [pages-root]
  (vec
   (mapcat (fn [path]
             (if-not (authoring-non-orchestration-file? path)
               []
               (->> (ns-dependencies path)
                    (keep (fn [dependency]
                            (when-let [message (page-role-layering-violation dependency)]
                              {:filename path
                               :type :page-role-layering
                               :message message})))
                    vec)))
           (page-source-files pages-root))))

(defn frontend-page-role-gate
  []
  (let [findings (vec (concat (authoring-facade-findings frontend-pages-root)
                              (authoring-layering-findings frontend-pages-root)))]
    {:gate :frontend-page-role-governance
     :label "frontend-page-role-governance"
     :status (if (seq findings) :error :pass)
     :headline (if (seq findings)
                 (str (count findings) " frontend page-role finding(s) require tighter authoring boundaries")
                 "authoring page roles respect facade and orchestration boundaries")
     :findings (support/sorted-findings findings 8)
     :action "Keep authoring/state.cljs as a thin facade, and restrict direct http/global state access to the explicit authoring orchestration files."}))
