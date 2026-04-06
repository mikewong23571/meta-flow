(ns meta-flow.cli.defs.options
  (:require [clojure.string :as str]
            [meta-flow.cli.commands :as commands]
            [meta-flow.defs.protocol :as defs.protocol]))

(def ^:private create-runtime-profile-options
  #{"--from"
    "--from-version"
    "--new-id"
    "--name"
    "--new-version"
    "--worker-prompt-path"
    "--web-search"})

(def ^:private publish-options
  #{"--id"
    "--version"})

(def ^:private create-task-type-options
  #{"--from"
    "--from-version"
    "--new-id"
    "--name"
    "--new-version"
    "--runtime-profile"
    "--runtime-profile-version"})

(def ^:private generate-task-type-options
  #{"--description"
    "--from-task-type"
    "--from-task-type-version"
    "--from-runtime-profile"
    "--from-runtime-profile-version"
    "--new-id"
    "--name"})

(defn- require-text-option!
  [args option-name]
  (let [value (commands/option-value args option-name)]
    (when (or (nil? value)
              (str/starts-with? value "--"))
      (throw (ex-info (str "Missing required option " option-name)
                      {:args args
                       :option option-name})))
    value))

(defn- keyword-option!
  [args option-name]
  (keyword (require-text-option! args option-name)))

(defn- parse-pos-int!
  [option-name text]
  (let [value (try
                (Integer/parseInt text)
                (catch NumberFormatException _
                  (throw (ex-info (str "Option " option-name " must be a positive integer")
                                  {:option option-name
                                   :value text}))))]
    (when-not (pos-int? value)
      (throw (ex-info (str "Option " option-name " must be a positive integer")
                      {:option option-name
                       :value text})))
    value))

(defn- optional-pos-int
  [args option-name]
  (some-> (commands/option-value args option-name)
          (parse-pos-int! option-name)))

(defn- parse-boolean!
  [option-name text]
  (case text
    "true" true
    "false" false
    (throw (ex-info (str "Option " option-name " must be true or false")
                    {:option option-name
                     :value text}))))

(defn- ensure-known-options!
  [args command-label allowed-options]
  (let [unsupported (->> args
                         (filter #(str/starts-with? % "--"))
                         (remove allowed-options)
                         sort
                         vec)]
    (when (seq unsupported)
      (throw (ex-info (str "Unsupported options for " command-label)
                      {:command command-label
                       :unsupported-options unsupported
                       :supported-options (sort allowed-options)})))))

(defn validate-create-runtime-profile-options!
  [args]
  (ensure-known-options! args "defs create-runtime-profile" create-runtime-profile-options))

(defn validate-create-task-type-options!
  [args]
  (ensure-known-options! args "defs create-task-type" create-task-type-options))

(defn validate-generate-task-type-options!
  [args]
  (ensure-known-options! args "defs generate-task-type" generate-task-type-options))

(defn validate-publish-options!
  [args command-label]
  (ensure-known-options! args command-label publish-options))

(defn- latest-runtime-profile-version
  [repository runtime-profile-id]
  (some->> (:runtime-profiles (defs.protocol/load-workflow-defs repository))
           (filter #(= runtime-profile-id (:runtime-profile/id %)))
           (sort-by :runtime-profile/version >)
           first
           :runtime-profile/version))

(defn- resolve-runtime-profile-ref!
  [repository args]
  (let [runtime-profile-id (keyword-option! args "--runtime-profile")
        runtime-profile-version (or (optional-pos-int args "--runtime-profile-version")
                                    (latest-runtime-profile-version repository runtime-profile-id))]
    (when-not runtime-profile-version
      (throw (ex-info (str "Published runtime profile " runtime-profile-id
                           " not found. Publish it before referencing it from a task type.")
                      {:runtime-profile/id runtime-profile-id})))
    {:definition/id runtime-profile-id
     :definition/version runtime-profile-version}))

(defn create-runtime-profile-request
  [args]
  (let [worker-prompt-path (commands/option-value args "--worker-prompt-path")
        web-search-text (commands/option-value args "--web-search")
        overrides (cond-> {}
                    worker-prompt-path (assoc :runtime-profile/worker-prompt-path worker-prompt-path)
                    web-search-text (assoc :runtime-profile/web-search-enabled?
                                           (parse-boolean! "--web-search" web-search-text)))]
    (cond-> {:authoring/from-id (keyword-option! args "--from")
             :authoring/new-id (keyword-option! args "--new-id")
             :authoring/new-name (require-text-option! args "--name")}
      (commands/option-value args "--from-version")
      (assoc :authoring/from-version (parse-pos-int! "--from-version"
                                                     (require-text-option! args "--from-version")))

      (commands/option-value args "--new-version")
      (assoc :authoring/new-version (parse-pos-int! "--new-version"
                                                    (require-text-option! args "--new-version")))

      (seq overrides)
      (assoc :authoring/overrides overrides))))

(defn create-task-type-request
  [repository args]
  (let [runtime-profile-text (commands/option-value args "--runtime-profile")
        runtime-profile-ref (when runtime-profile-text
                              (resolve-runtime-profile-ref! repository args))
        overrides (cond-> {}
                    runtime-profile-ref (assoc :task-type/runtime-profile-ref runtime-profile-ref))]
    (when (and (commands/option-value args "--runtime-profile-version")
               (nil? runtime-profile-text))
      (throw (ex-info "Option --runtime-profile-version requires --runtime-profile"
                      {:option "--runtime-profile-version"})))
    (cond-> {:authoring/from-id (keyword-option! args "--from")
             :authoring/new-id (keyword-option! args "--new-id")
             :authoring/new-name (require-text-option! args "--name")}
      (commands/option-value args "--from-version")
      (assoc :authoring/from-version (parse-pos-int! "--from-version"
                                                     (require-text-option! args "--from-version")))

      (commands/option-value args "--new-version")
      (assoc :authoring/new-version (parse-pos-int! "--new-version"
                                                    (require-text-option! args "--new-version")))

      (seq overrides)
      (assoc :authoring/overrides overrides))))

(defn create-task-type-generation-request
  [args]
  (cond-> {:generation/description (require-text-option! args "--description")}
    (commands/option-value args "--from-task-type")
    (assoc :generation/task-type-template-id (keyword-option! args "--from-task-type"))

    (commands/option-value args "--from-task-type-version")
    (assoc :generation/task-type-template-version
           (parse-pos-int! "--from-task-type-version"
                           (require-text-option! args "--from-task-type-version")))

    (commands/option-value args "--from-runtime-profile")
    (assoc :generation/runtime-profile-template-id (keyword-option! args "--from-runtime-profile"))

    (commands/option-value args "--from-runtime-profile-version")
    (assoc :generation/runtime-profile-template-version
           (parse-pos-int! "--from-runtime-profile-version"
                           (require-text-option! args "--from-runtime-profile-version")))

    (commands/option-value args "--new-id")
    (assoc :generation/task-type-id (keyword-option! args "--new-id"))

    (commands/option-value args "--name")
    (assoc :generation/task-type-name (require-text-option! args "--name"))))

(defn publish-definition-ref
  [args]
  {:definition/id (keyword-option! args "--id")
   :definition/version (parse-pos-int! "--version"
                                       (require-text-option! args "--version"))})
