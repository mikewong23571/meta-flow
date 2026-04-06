(ns meta-flow.defs.generation.inference
  (:require [clojure.string :as str]))

(def ^:private stop-words
  #{"a"
    "an"
    "and"
    "by"
    "create"
    "emits"
    "for"
    "from"
    "in"
    "into"
    "of"
    "report"
    "task"
    "that"
    "the"
    "to"
    "use"
    "uses"
    "with"})

(defn- description-matches?
  [description pattern]
  (boolean (re-find pattern (str/lower-case description))))

(defn select-task-type-template-id
  [request]
  (or (:generation/task-type-template-id request)
      (cond
        (description-matches? (:generation/description request)
                              #"\b(repo|repository)\b")
        :task-type/repo-arch-investigation

        (description-matches? (:generation/description request)
                              #"\bcve\b")
        :task-type/cve-investigation

        :else
        :task-type/default)))

(defn select-runtime-profile-template-ref
  [task-type-runtime-profile request]
  (let [description (:generation/description request)
        definition-id (or (:generation/runtime-profile-template-id request)
                          (cond
                            (description-matches? description #"\bmock\b")
                            :runtime-profile/mock-worker

                            (or (description-matches? description #"\bcodex\b")
                                (description-matches? description #"web search")
                                (description-matches? description #"context7"))
                            (if (= :runtime.adapter/codex
                                   (:runtime-profile/adapter-id task-type-runtime-profile))
                              (:runtime-profile/id task-type-runtime-profile)
                              :runtime-profile/codex-worker)

                            :else
                            (:runtime-profile/id task-type-runtime-profile)))
        definition-version (or (:generation/runtime-profile-template-version request)
                               (when (and (nil? (:generation/runtime-profile-template-id request))
                                          (= definition-id (:runtime-profile/id task-type-runtime-profile)))
                                 (:runtime-profile/version task-type-runtime-profile)))]
    {:definition/id definition-id
     :definition/version definition-version}))

(defn infer-task-slug
  [request]
  (or (some-> (:generation/task-type-id request) name)
      (let [description (:generation/description request)]
        (cond
          (description-matches? description #"\b(repo|repository)\b.*\breview\b")
          "repo-review"

          (and (description-matches? description #"\b(repo|repository)\b")
               (description-matches? description #"\barchitecture\b"))
          "repo-arch"

          (description-matches? description #"\bcve\b")
          "cve-investigation"

          :else
          (or (->> (str/lower-case description)
                   (re-seq #"[a-z0-9]+")
                   (remove stop-words)
                   distinct
                   (take 4)
                   seq
                   (str/join "-"))
              "generated-task")))))

(defn- titleize-token
  [token]
  (if (= token "cve")
    "CVE"
    (str/capitalize token)))

(defn inferred-task-type-name
  [request slug]
  (or (:generation/task-type-name request)
      (->> (str/split slug #"-")
           (map titleize-token)
           (str/join " "))))

(defn inferred-runtime-profile-name
  [runtime-profile-template task-type-name]
  (str (case (:runtime-profile/adapter-id runtime-profile-template)
         :runtime.adapter/codex "Codex"
         :runtime.adapter/mock "Mock"
         "Generated")
       " "
       (str/lower-case task-type-name)
       " worker"))

(defn- web-search-setting
  [description]
  (cond
    (or (description-matches? description #"without web search")
        (description-matches? description #"no web search")
        (description-matches? description #"web search disabled")
        (description-matches? description #"disable[s]? web search"))
    false

    (or (description-matches? description #"with web search")
        (description-matches? description #"web search enabled")
        (description-matches? description #"enable[s]? web search"))
    true

    :else
    nil))

(defn runtime-profile-overrides
  [runtime-profile-template description]
  (let [web-search? (web-search-setting description)]
    (when (and (some? web-search?)
               (not= :runtime.adapter/codex (:runtime-profile/adapter-id runtime-profile-template)))
      (throw (ex-info "Description requested web-search changes but the selected runtime profile template is not a Codex adapter"
                      {:runtime-profile/id (:runtime-profile/id runtime-profile-template)
                       :runtime-profile/adapter-id (:runtime-profile/adapter-id runtime-profile-template)})))
    (cond-> {}
      (some? web-search?)
      (assoc :runtime-profile/web-search-enabled? web-search?))))
