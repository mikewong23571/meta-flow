(ns meta-flow.defs.index
  (:require [meta-flow.defs.source :as defs.source]))

(defn index-definitions!
  [label definitions id-key version-key]
  (reduce (fn [idx definition]
            (let [definition-key [(get definition id-key)
                                  (get definition version-key)]]
              (when (contains? idx definition-key)
                (let [existing (get idx definition-key)]
                  (throw (ex-info (str "Duplicate definition version in " label)
                                  {:label label
                                   :definition-key definition-key
                                   :existing-source (defs.source/definition-source existing)
                                   :duplicate-source (defs.source/definition-source definition)}))))
              (assoc idx definition-key definition)))
          {}
          definitions))

(defn ref-key
  [definition-ref]
  [(:definition/id definition-ref) (:definition/version definition-ref)])

(defn require-ref!
  [label definition-ref index]
  (when-not (get index (ref-key definition-ref))
    (throw (ex-info (str "Missing referenced definition for " label)
                    {:label label
                     :definition-ref definition-ref}))))
