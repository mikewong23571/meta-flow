(ns meta-flow.defs.generation.description
  (:require [clojure.string :as str]
            [meta-flow.defs.generation.inference :as generation.inference]
            [meta-flow.defs.protocol :as defs.protocol]
            [meta-flow.schema :as schema]))

(defn- not-blank-string?
  [value]
  (and (string? value)
       (not (str/blank? value))))

(defn- require-keyword-namespace!
  [keyword-value expected-namespace label]
  (when-not (= expected-namespace (namespace keyword-value))
    (throw (ex-info (str label " must use keyword namespace " expected-namespace)
                    {:label label
                     :expected-namespace expected-namespace
                     :value keyword-value}))))

(defn- validate-generation-request!
  [request]
  (schema/validate! "task-type generation request"
                    schema/task-type-generation-request-schema
                    request)
  (when-not (not-blank-string? (:generation/description request))
    (throw (ex-info ":generation/description must be a non-blank string"
                    {:value (:generation/description request)})))
  (when-not (or (nil? (:generation/task-type-template-version request))
                (:generation/task-type-template-id request))
    (throw (ex-info ":generation/task-type-template-version requires :generation/task-type-template-id"
                    {:request request})))
  (when-not (or (nil? (:generation/runtime-profile-template-version request))
                (:generation/runtime-profile-template-id request))
    (throw (ex-info ":generation/runtime-profile-template-version requires :generation/runtime-profile-template-id"
                    {:request request})))
  (when-let [task-type-template-id (:generation/task-type-template-id request)]
    (require-keyword-namespace! task-type-template-id "task-type" ":generation/task-type-template-id"))
  (when-let [runtime-profile-template-id (:generation/runtime-profile-template-id request)]
    (require-keyword-namespace! runtime-profile-template-id
                                "runtime-profile"
                                ":generation/runtime-profile-template-id"))
  (when-let [task-type-id (:generation/task-type-id request)]
    (require-keyword-namespace! task-type-id "task-type" ":generation/task-type-id"))
  (when-let [task-type-name (:generation/task-type-name request)]
    (when-not (not-blank-string? task-type-name)
      (throw (ex-info ":generation/task-type-name must be a non-blank string"
                      {:value task-type-name}))))
  (update request :generation/description str/trim))

(defn- latest-definition-by-id
  [definitions id-key version-key definition-id]
  (->> definitions
       (filter #(= definition-id (get % id-key)))
       (sort-by version-key >)
       first))

(defn- resolve-task-type-template!
  [defs-repo request]
  (let [definition-id (generation.inference/select-task-type-template-id request)
        definition-version (:generation/task-type-template-version request)]
    (if definition-version
      (or (defs.protocol/find-task-type-def defs-repo definition-id definition-version)
          (throw (ex-info "Template task type not found"
                          {:definition-kind :task-type
                           :definition/id definition-id
                           :definition/version definition-version})))
      (or (latest-definition-by-id (defs.protocol/list-task-type-defs defs-repo)
                                   :task-type/id
                                   :task-type/version
                                   definition-id)
          (throw (ex-info "Template task type not found"
                          {:definition-kind :task-type
                           :definition/id definition-id}))))))

(defn- resolve-runtime-profile-template!
  [defs-repo task-type-template request]
  (let [task-type-runtime-profile-ref (:task-type/runtime-profile-ref task-type-template)
        task-type-runtime-profile (defs.protocol/find-runtime-profile defs-repo
                                                                      (:definition/id task-type-runtime-profile-ref)
                                                                      (:definition/version task-type-runtime-profile-ref))
        {:definition/keys [id version]}
        (generation.inference/select-runtime-profile-template-ref task-type-runtime-profile request)]
    (if version
      (or (defs.protocol/find-runtime-profile defs-repo id version)
          (throw (ex-info "Template runtime profile not found"
                          {:definition-kind :runtime-profile
                           :definition/id id
                           :definition/version version})))
      (or (latest-definition-by-id (:runtime-profiles (defs.protocol/load-workflow-defs defs-repo))
                                   :runtime-profile/id
                                   :runtime-profile/version
                                   id)
          (throw (ex-info "Template runtime profile not found"
                          {:definition-kind :runtime-profile
                           :definition/id id}))))))

(defn derive-task-type-generation-context!
  [defs-repo request]
  (let [request (validate-generation-request! request)
        task-type-template (resolve-task-type-template! defs-repo request)
        runtime-profile-template (resolve-runtime-profile-template! defs-repo task-type-template request)
        _ (when (and (= :runtime.adapter/mock
                        (:runtime-profile/adapter-id runtime-profile-template))
                     (= :validator/repo-arch
                        (get-in task-type-template [:task-type/validator-ref :definition/id])))
            (throw (ex-info "Repo-arch-derived task types cannot use the mock runtime profile"
                            {:task-type/template-id (:task-type/id task-type-template)
                             :runtime-profile/id (:runtime-profile/id runtime-profile-template)
                             :reason :generation/unsupported-runtime-profile})))
        inferred-task-slug (generation.inference/infer-task-slug request)
        task-slug (if (or (:generation/task-type-id request)
                          (not= (keyword "task-type" inferred-task-slug)
                                (:task-type/id task-type-template)))
                    inferred-task-slug
                    (str inferred-task-slug "-generated"))
        task-type-id (or (:generation/task-type-id request)
                         (keyword "task-type" task-slug))
        task-type-name (generation.inference/inferred-task-type-name request task-slug)
        runtime-profile-request (let [overrides (generation.inference/runtime-profile-overrides runtime-profile-template
                                                                                                (:generation/description request))]
                                  (when (seq overrides)
                                    {:authoring/from-id (:runtime-profile/id runtime-profile-template)
                                     :authoring/from-version (:runtime-profile/version runtime-profile-template)
                                     :authoring/new-id (keyword "runtime-profile" task-slug)
                                     :authoring/new-name (generation.inference/inferred-runtime-profile-name runtime-profile-template
                                                                                                             task-type-name)
                                     :authoring/overrides overrides}))]
    {:request request
     :task-type-template task-type-template
     :task-type-id task-type-id
     :task-type-name task-type-name
     :runtime-profile-template runtime-profile-template
     :runtime-profile-request runtime-profile-request}))
