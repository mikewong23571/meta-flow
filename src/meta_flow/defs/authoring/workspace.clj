(ns meta-flow.defs.authoring.workspace
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [meta-flow.defs.source :as defs.source]
            [meta-flow.defs.validation :as defs.validation]
            [meta-flow.defs.workspace.files :as workspace.files]))

(declare normalize-edn)

(defn- ordered-map
  [key-order value]
  (let [present-keys (filter #(contains? value %) key-order)
        remaining-keys (->> (keys value)
                            (remove (set key-order))
                            sort)]
    (reduce (fn [ordered key]
              (assoc ordered key (normalize-edn (get value key))))
            (array-map)
            (concat present-keys remaining-keys))))

(defn- definition-ref?
  [value]
  (= #{:definition/id :definition/version}
     (set (keys value))))

(defn- input-field?
  [value]
  (contains? value :field/id))

(defn- work-key-expr?
  [value]
  (contains? value :work-key/type))

(defn- normalize-edn
  [value]
  (cond
    (vector? value)
    (mapv normalize-edn value)

    (and (map? value) (definition-ref? value))
    (ordered-map [:definition/id :definition/version] value)

    (and (map? value) (input-field? value))
    (ordered-map [:field/id :field/label :field/type :field/required? :field/placeholder] value)

    (and (map? value) (work-key-expr? value))
    (ordered-map [:work-key/type :work-key/tag :work-key/field :work-key/fields] value)

    (map? value)
    (ordered-map [] value)

    :else
    value))

(defn- ordered-definition
  [top-level-key-order definition]
  (ordered-map top-level-key-order definition))

(defn- render-edn
  [value]
  (with-out-str
    (pprint/pprint value)))

(defn sort-definitions
  [{:keys [id-key version-key]} definitions]
  (->> definitions
       (sort-by (juxt #(str (get % id-key))
                      #(get % version-key)))
       vec))

(defn- existing-definition
  [{:keys [id-key version-key]} definitions definition]
  (some #(when (= [(get % id-key) (get % version-key)]
                  [(get definition id-key) (get definition version-key)])
           %)
        definitions))

(defn- throw-existing-definition!
  [{:keys [definition-kind kind-label id-key version-key]} definition existing action]
  (throw (ex-info (str "Cannot " action " " kind-label " " (get definition id-key)
                       " version " (get definition version-key)
                       " because that id/version already exists in active definitions")
                  {:action action
                   :definition-kind definition-kind
                   :definition/id (get definition id-key)
                   :definition/version (get definition version-key)
                   :existing/source (defs.source/definition-source existing)})))

(defn add-definition-to-defs!
  [definitions {:keys [definition-key] :as definition-spec} definition action]
  (let [existing (existing-definition definition-spec (get definitions definition-key) definition)]
    (when existing
      (throw-existing-definition! definition-spec definition existing action))
    (update definitions definition-key #(sort-definitions definition-spec (conj (vec %) definition)))))

(defn validate-candidate-definitions!
  [definitions]
  (-> definitions
      defs.validation/validate-definition-schemas!
      defs.validation/validate-definition-links!)
  definitions)

(defn write-draft!
  [{:keys [definition-key id-key version-key top-level-key-order]} overlay-root definition]
  (let [draft-path (workspace.files/draft-file-path overlay-root
                                                    definition-key
                                                    (get definition id-key)
                                                    (get definition version-key))]
    (workspace.files/initialize-overlay! overlay-root)
    (workspace.files/atomic-write! draft-path
                                   (render-edn (ordered-definition top-level-key-order definition)))
    draft-path))

(defn load-draft!
  [{:keys [definition-key]} overlay-root definition-id definition-version]
  (let [draft-path (workspace.files/draft-file-path overlay-root
                                                    definition-key
                                                    definition-id
                                                    definition-version)]
    {:draft-path draft-path
     :draft-file (io/file draft-path)}))

(defn load-active-definitions
  [{:keys [definition-key]} overlay-root]
  (let [active-path (workspace.files/overlay-file-path overlay-root definition-key)
        active-file (io/file active-path)]
    {:active-path active-path
     :definitions (if (.isFile active-file)
                    (defs.source/load-edn-file! active-path)
                    [])}))

(defn write-active-definitions!
  [{:keys [definition-key top-level-key-order]} overlay-root definitions]
  (let [active-path (workspace.files/overlay-file-path overlay-root definition-key)]
    (workspace.files/initialize-overlay! overlay-root)
    (workspace.files/atomic-write! active-path
                                   (render-edn (mapv #(ordered-definition top-level-key-order %)
                                                     definitions)))
    active-path))
