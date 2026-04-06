(ns meta-flow.lint.check.frontend.shared-ui
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def frontend-ui-root
  "frontend/src/meta_flow_ui")

(def shared-ui-root
  (str frontend-ui-root "/ui"))

(def shared-component-facade-file
  (str frontend-ui-root "/components.cljs"))

(def shared-component-facade-warning-threshold 80)
(def shared-component-facade-error-threshold 120)

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

(defn- count-lines
  [path]
  (with-open [reader (io/reader path)]
    (count (line-seq reader))))

(defn- source-files-under
  [root]
  (->> (io/file root)
       file-seq
       (filter cljs-file?)
       (map #(.getPath ^java.io.File %))))

(defn- top-findings
  [findings limit]
  (->> findings
       (sort-by (juxt :filename #(or (:row %) 0) :message))
       (take limit)
       vec))

(defn- shared-ui-layer-specs
  []
  [{:layer :layout
    :path-prefix (normalize-path (str shared-ui-root "/layout"))
    :file-path (normalize-path (str shared-ui-root "/layout.cljs"))
    :ns-prefix "meta-flow-ui.ui.layout"}
   {:layer :patterns
    :path-prefix (normalize-path (str shared-ui-root "/patterns"))
    :file-path (normalize-path (str shared-ui-root "/patterns.cljs"))
    :ns-prefix "meta-flow-ui.ui.patterns"}
   {:layer :primitives
    :path-prefix (normalize-path (str shared-ui-root "/primitives"))
    :file-path (normalize-path (str shared-ui-root "/primitives.cljs"))
    :ns-prefix "meta-flow-ui.ui.primitives"}])

(defn- shared-ui-layer-spec-for-path
  [path]
  (let [normalized (normalize-path path)]
    (some (fn [{:keys [path-prefix file-path] :as spec}]
            (when (or (= normalized file-path)
                      (str/starts-with? normalized (str path-prefix "/")))
              spec))
          (shared-ui-layer-specs))))

(defn- shared-ui-source-files
  []
  (if (existing-directory? shared-ui-root)
    (->> (source-files-under shared-ui-root)
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

(defn- namespace-name
  [path]
  (let [{:keys [forms]} (safe-read-top-level-forms path)
        ns-form (first forms)]
    (when (and (seq? ns-form)
               (= 'ns (first ns-form))
               (symbol? (second ns-form)))
      (str (second ns-form)))))

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

(defn- shared-ui-placement-findings
  []
  (vec
   (mapcat (fn [path]
             (let [spec (shared-ui-layer-spec-for-path path)]
               (cond
                 (nil? spec)
                 [{:filename path
                   :type :shared-ui-placement
                   :message "shared ui implementation must live under ui/layout, ui/patterns, or ui/primitives"}]

                 :else
                 (let [ns-name (namespace-name path)
                       expected-prefix (:ns-prefix spec)]
                   (when (and ns-name
                              (not (or (= ns-name expected-prefix)
                                       (str/starts-with? ns-name (str expected-prefix ".")))))
                     [{:filename path
                       :type :shared-ui-namespace-placement
                       :message (str "namespace `" ns-name
                                     "` does not match shared ui placement; move it under `"
                                     expected-prefix
                                     "`")}])))))
           (shared-ui-source-files))))

(defn- facade-target-shared-ui?
  [target]
  (when-let [namespace-part (namespace target)]
    (some (fn [{:keys [ns-prefix]}]
            (or (= namespace-part ns-prefix)
                (str/starts-with? namespace-part (str ns-prefix "."))))
          (shared-ui-layer-specs))))

(defn- allowed-facade-form?
  [form]
  (or (and (seq? form)
           (= 'ns (first form)))
      (and (seq? form)
           (= 'def (first form))
           (= 3 (count form))
           (symbol? (second form))
           (symbol? (nth form 2))
           (facade-target-shared-ui? (nth form 2)))))

(defn- invalid-facade-findings
  []
  (if-not (existing-file? shared-component-facade-file)
    []
    (let [{:keys [forms error]} (safe-read-top-level-forms shared-component-facade-file)]
      (cond
        error
        [{:filename shared-component-facade-file
          :type :shared-component-facade-parse-error
          :message (str "could not read components facade: " error)}]
        :else
        (->> forms
             (remove allowed-facade-form?)
             (map (fn [form]
                    (let [form-name (when (and (seq? form)
                                               (symbol? (second form)))
                                      (name (second form)))]
                      {:filename shared-component-facade-file
                       :type :shared-component-facade-implementation
                       :message (str "components.cljs must stay a thin facade; move "
                                     (if form-name
                                       (str "`" form-name "`")
                                       "this form")
                                     " into ui/layout, ui/patterns, or ui/primitives and re-export it instead")})))
             vec)))))

(defn- shared-component-facade-issues
  []
  (if-not (existing-file? shared-component-facade-file)
    []
    (let [line-count (count-lines shared-component-facade-file)]
      (cond
        (> line-count shared-component-facade-error-threshold)
        [{:kind :shared-component-facade-length
          :path shared-component-facade-file
          :line-count line-count
          :level :error
          :threshold shared-component-facade-error-threshold}]
        (> line-count shared-component-facade-warning-threshold)
        [{:kind :shared-component-facade-length
          :path shared-component-facade-file
          :line-count line-count
          :level :warning
          :threshold shared-component-facade-warning-threshold}]
        :else
        []))))

(defn- ui-layering-violation
  [layer dependency]
  (cond
    (str/starts-with? dependency "meta-flow-ui.pages.")
    (str "shared ui layer `" (name layer)
         "` must not depend on page namespaces like `" dependency "`")
    (and (= layer :layout)
         (or (= dependency "meta-flow-ui.state")
             (str/starts-with? dependency "meta-flow-ui.state.")))
    (str "ui/layout must not depend on state namespaces like `" dependency "`")
    :else nil))

(defn- shared-ui-layering-findings
  []
  (vec
   (mapcat (fn [path]
             (when-let [{:keys [layer]} (shared-ui-layer-spec-for-path path)]
               (mapcat (fn [dependency]
                         (when-let [message (ui-layering-violation layer dependency)]
                           [{:filename path
                             :type :shared-ui-layering
                             :message message}]))
                       (ns-dependencies path))))
           (shared-ui-source-files))))

(defn frontend-shared-component-placement-gate
  []
  (let [findings (shared-ui-placement-findings)]
    {:gate :frontend-shared-component-placement-governance
     :label "frontend-shared-component-placement-governance"
     :status (if (seq findings) :error :pass)
     :headline (if (seq findings)
                 (str (count findings) " shared component placement finding(s) require ui/layout, ui/patterns, or ui/primitives")
                 "shared component implementation lives under ui/layout, ui/patterns, or ui/primitives")
     :findings (top-findings findings 8)
     :action "Place shared UI implementation in ui/layout, ui/patterns, or ui/primitives, and keep namespace prefixes aligned with that placement."}))

(defn frontend-shared-component-facade-gate
  []
  (let [findings (invalid-facade-findings)
        issues (shared-component-facade-issues)
        error-count (count (filter #(= :error (:level %)) issues))]
    {:gate :frontend-shared-component-facade-governance
     :label "frontend-shared-component-facade-governance"
     :status (cond
               (seq findings) :error
               (pos? error-count) :error
               (seq issues) :warning
               :else :pass)
     :headline (cond
                 (seq findings)
                 (str (count findings) " components facade finding(s) require moving implementation out of components.cljs")
                 (seq issues)
                 (str (count issues) " components facade issue(s) require a thinner facade")
                 :else
                 "components.cljs is a thin shared-ui facade")
     :findings (top-findings findings 5)
     :issues (->> issues
                  (sort-by (juxt #(case (:level %)
                                    :error 0
                                    :warning 1
                                    2)
                                 :path))
                  vec)
     :action "Keep components.cljs as a thin re-export facade only; move shared implementation into ui/layout, ui/patterns, or ui/primitives."}))

(defn frontend-ui-layering-gate
  []
  (let [findings (shared-ui-layering-findings)]
    {:gate :frontend-ui-layering-governance
     :label "frontend-ui-layering-governance"
     :status (if (seq findings) :error :pass)
     :headline (if (seq findings)
                 (str (count findings) " shared ui layering finding(s) require dependency cleanup")
                 "shared ui layers respect page/state dependency boundaries")
     :findings (top-findings findings 8)
     :action "Keep ui/primitives, ui/layout, and ui/patterns independent from page namespaces, and keep ui/layout independent from frontend state namespaces."}))
