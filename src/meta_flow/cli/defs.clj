(ns meta-flow.cli.defs
  (:require [meta-flow.cli.defs.options :as defs.options]
            [meta-flow.defs.authoring :as defs.authoring]
            [meta-flow.defs.generation.core :as defs.generation]
            [meta-flow.defs.loader :as defs.loader]))

(defn- reload-repository!
  [repository result]
  (try
    (defs.loader/reload-filesystem-definition-repository! repository)
    (println "Reloaded definitions cache")
    (catch Throwable throwable
      (throw (ex-info "Published definition on disk but failed to reload definitions cache"
                      {:published-path (:published-path result)
                       :definition (:definition result)}
                      throwable)))))

(defn run-create-runtime-profile!
  [args]
  (defs.options/validate-create-runtime-profile-options! args)
  (let [repository (defs.loader/filesystem-definition-repository)
        request (defs.options/create-runtime-profile-request args)
        result (defs.authoring/create-runtime-profile-draft! repository request)
        runtime-profile (:definition result)]
    (println (str "Wrote draft runtime profile "
                  (:runtime-profile/id runtime-profile)
                  " version "
                  (:runtime-profile/version runtime-profile)
                  " to "
                  (:draft-path result)))
    (println "Validation: OK")))

(defn run-create-task-type!
  [args]
  (defs.options/validate-create-task-type-options! args)
  (let [repository (defs.loader/filesystem-definition-repository)
        request (defs.options/create-task-type-request repository args)
        result (defs.authoring/create-task-type-draft! repository request)
        task-type (:definition result)]
    (println (str "Wrote draft task type "
                  (:task-type/id task-type)
                  " version "
                  (:task-type/version task-type)
                  " to "
                  (:draft-path result)))
    (println "Validation: OK")))

(defn run-generate-task-type!
  [args]
  (defs.options/validate-generate-task-type-options! args)
  (let [repository (defs.loader/filesystem-definition-repository)
        request (defs.options/create-task-type-generation-request args)
        result (defs.generation/generate-task-type-draft! repository request)
        runtime-profile (:runtime-profile result)
        task-type (:task-type result)]
    (println "Generated draft request from description")
    (when runtime-profile
      (println (str "Wrote draft runtime profile "
                    (get-in runtime-profile [:definition :runtime-profile/id])
                    " version "
                    (get-in runtime-profile [:definition :runtime-profile/version])
                    " to "
                    (:draft-path runtime-profile))))
    (println (str "Wrote draft task type "
                  (get-in task-type [:definition :task-type/id])
                  " version "
                  (get-in task-type [:definition :task-type/version])
                  " to "
                  (:draft-path task-type)))
    (println "Validation: OK")
    (doseq [note (:notes result)]
      (println note))))

(defn run-publish-runtime-profile!
  [args]
  (defs.options/validate-publish-options! args "defs publish-runtime-profile")
  (let [repository (defs.loader/filesystem-definition-repository)
        definition-ref (defs.options/publish-definition-ref args)
        result (defs.authoring/publish-runtime-profile-draft! repository definition-ref)
        runtime-profile (:definition result)]
    (println (str "Published "
                  (:runtime-profile/id runtime-profile)
                  " version "
                  (:runtime-profile/version runtime-profile)
                  " to "
                  (:published-path result)))
    (reload-repository! repository result)))

(defn run-publish-task-type!
  [args]
  (defs.options/validate-publish-options! args "defs publish-task-type")
  (let [repository (defs.loader/filesystem-definition-repository)
        definition-ref (defs.options/publish-definition-ref args)
        result (defs.authoring/publish-task-type-draft! repository definition-ref)
        task-type (:definition result)]
    (println (str "Published "
                  (:task-type/id task-type)
                  " version "
                  (:task-type/version task-type)
                  " to "
                  (:published-path result)))
    (reload-repository! repository result)))
