(ns meta-flow.defs.authoring.drafts
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [meta-flow.defs.authoring.kinds :as authoring.kinds]
            [meta-flow.defs.authoring.workspace :as authoring.workspace]
            [meta-flow.defs.source :as defs.source]))

(defn- require-definition-namespace!
  [definition-kind keyword-value label]
  (let [expected-namespace (:expected-namespace
                            (authoring.kinds/definition-kind-settings definition-kind))]
    (when-not (= expected-namespace (namespace keyword-value))
      (throw (ex-info (str label " must use keyword namespace " expected-namespace)
                      {:definition-kind definition-kind
                       :label label
                       :expected-namespace expected-namespace
                       :value keyword-value})))))

(defn- draft-summary
  [definition-kind definition-spec definition draft-path]
  {:definition-kind definition-kind
   :definition/id (get definition (:id-key definition-spec))
   :definition/version (get definition (:version-key definition-spec))
   :definition/name (get definition (:name-key definition-spec))
   :draft-path draft-path})

(defn list-definition-drafts
  [overlay-root definition-kind]
  (let [definition-spec (authoring.kinds/definition-spec definition-kind)
        draft-directory (io/file (str overlay-root
                                      "/drafts/"
                                      (name (:definition-key definition-spec))))
        items (->> (or (seq (.listFiles draft-directory)) [])
                   (filter #(and (.isFile ^java.io.File %)
                                 (str/ends-with? (.getName ^java.io.File %) ".edn")))
                   (map (fn [draft-file]
                          (let [draft-path (.getPath ^java.io.File draft-file)
                                definition (defs.source/load-edn-file! draft-path)]
                            (draft-summary definition-kind definition-spec definition draft-path))))
                   (sort-by (juxt #(str (:definition/id %))
                                  :definition/version))
                   vec)]
    {:definition-kind definition-kind
     :definition-kind/label (:kind-label definition-spec)
     :items items}))

(defn load-definition-draft
  [overlay-root definition-kind definition-ref]
  (let [definition-spec (authoring.kinds/definition-spec definition-kind)
        definition-id (:definition/id definition-ref)
        definition-version (:definition/version definition-ref)
        {:keys [draft-path draft-file]}
        (authoring.workspace/load-draft! definition-spec overlay-root definition-id definition-version)]
    (require-definition-namespace! definition-kind definition-id ":definition/id")
    (when-not (.isFile draft-file)
      (throw (ex-info (str "Draft " (:kind-label definition-spec) " not found at " draft-path)
                      {:definition-kind definition-kind
                       :definition/id definition-id
                       :definition/version definition-version
                       :draft-path draft-path})))
    (let [draft-definition (defs.source/load-edn-file! draft-path)]
      (when-not (= [definition-id definition-version]
                   [(get draft-definition (:id-key definition-spec))
                    (get draft-definition (:version-key definition-spec))])
        (throw (ex-info "Draft file contents do not match the requested definition id/version"
                        {:definition-kind definition-kind
                         :definition/id definition-id
                         :definition/version definition-version
                         :draft-path draft-path
                         :draft-definition draft-definition})))
      {:definition-kind definition-kind
       :definition draft-definition
       :draft-path draft-path})))
