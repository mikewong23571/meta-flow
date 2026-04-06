(ns meta-flow.ui.defs.authoring
  (:require [meta-flow.defs.authoring :as defs.authoring]
            [meta-flow.defs.generation.core :as defs.generation]
            [meta-flow.defs.loader :as defs.loader]))

(defn authoring-contract
  []
  (defs.authoring/authoring-contract))

(defn list-runtime-profile-templates
  ([] (list-runtime-profile-templates (defs.loader/filesystem-definition-repository)))
  ([defs-repo]
   (defs.authoring/list-definition-templates defs-repo :runtime-profile)))

(defn list-task-type-templates
  ([] (list-task-type-templates (defs.loader/filesystem-definition-repository)))
  ([defs-repo]
   (defs.authoring/list-definition-templates defs-repo :task-type)))

(defn list-runtime-profile-drafts
  ([] (list-runtime-profile-drafts (defs.loader/filesystem-definition-repository)))
  ([defs-repo]
   (defs.authoring/list-definition-drafts defs-repo :runtime-profile)))

(defn load-runtime-profile-draft
  ([definition-id definition-version]
   (load-runtime-profile-draft (defs.loader/filesystem-definition-repository)
                               definition-id
                               definition-version))
  ([defs-repo definition-id definition-version]
   (defs.authoring/load-definition-draft defs-repo
                                         :runtime-profile
                                         {:definition/id definition-id
                                          :definition/version definition-version})))

(defn validate-runtime-profile-draft-request!
  [defs-repo request]
  (defs.authoring/prepare-runtime-profile-draft-request! defs-repo request))

(defn create-runtime-profile-draft!
  [defs-repo request]
  (defs.authoring/create-runtime-profile-draft! defs-repo request))

(defn list-task-type-drafts
  ([] (list-task-type-drafts (defs.loader/filesystem-definition-repository)))
  ([defs-repo]
   (defs.authoring/list-definition-drafts defs-repo :task-type)))

(defn load-task-type-draft
  ([definition-id definition-version]
   (load-task-type-draft (defs.loader/filesystem-definition-repository)
                         definition-id
                         definition-version))
  ([defs-repo definition-id definition-version]
   (defs.authoring/load-definition-draft defs-repo
                                         :task-type
                                         {:definition/id definition-id
                                          :definition/version definition-version})))

(defn validate-task-type-draft-request!
  [defs-repo request]
  (defs.authoring/prepare-task-type-draft-request! defs-repo request))

(defn create-task-type-draft!
  [defs-repo request]
  (defs.authoring/create-task-type-draft! defs-repo request))

(defn generate-task-type-draft!
  [defs-repo request]
  (defs.generation/generate-task-type-draft! defs-repo request))

(defn reload-definition-repository!
  [defs-repo]
  (let [definitions (defs.loader/reload-filesystem-definition-repository! defs-repo)]
    {:status "ok"
     :definitions (defs.loader/definitions-summary definitions)}))

(defn publish-runtime-profile-draft!
  [defs-repo definition-ref]
  (let [result (defs.authoring/publish-runtime-profile-draft! defs-repo definition-ref)]
    (assoc result :reload (reload-definition-repository! defs-repo))))

(defn publish-task-type-draft!
  [defs-repo definition-ref]
  (let [result (defs.authoring/publish-task-type-draft! defs-repo definition-ref)]
    (assoc result :reload (reload-definition-repository! defs-repo))))
