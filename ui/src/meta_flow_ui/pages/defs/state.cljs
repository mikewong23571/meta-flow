(ns meta-flow-ui.pages.defs.state
  (:require [meta-flow-ui.pages.defs.authoring.read :as authoring-read]
            [meta-flow-ui.pages.defs.authoring.state :as authoring-state]
            [meta-flow-ui.pages.defs.catalog.state :as catalog-state]
            [meta-flow-ui.state :as state]))

(defn defs-state
  []
  (:defs @state/ui-state))

(def load-items! catalog-state/load-items!)

(def load-detail! catalog-state/load-detail!)

(def load-runtime-items! catalog-state/load-runtime-items!)

(def load-runtime-detail! catalog-state/load-runtime-detail!)

(def load-authoring-contract! authoring-read/load-authoring-contract!)

(def load-runtime-profile-templates! authoring-read/load-runtime-profile-templates!)

(def load-task-type-templates! authoring-read/load-task-type-templates!)

(def load-runtime-profile-drafts! authoring-read/load-runtime-profile-drafts!)

(def load-task-type-drafts! authoring-read/load-task-type-drafts!)

(def load-runtime-profile-draft-detail! authoring-read/load-runtime-profile-draft-detail!)

(def load-task-type-draft-detail! authoring-read/load-task-type-draft-detail!)

(def validate-runtime-profile-draft! authoring-state/validate-runtime-profile-draft!)

(def validate-task-type-draft! authoring-state/validate-task-type-draft!)

(def create-runtime-profile-draft! authoring-state/create-runtime-profile-draft!)

(def reset-runtime-profile-authoring-feedback!
  authoring-state/reset-runtime-profile-authoring-feedback!)

(def reset-task-type-authoring-feedback!
  authoring-state/reset-task-type-authoring-feedback!)

(def reset-task-type-generation-feedback!
  authoring-state/reset-task-type-generation-feedback!)

(def reset-runtime-profile-validation-preview!
  authoring-state/reset-runtime-profile-validation-preview!)

(def reset-task-type-validation-preview!
  authoring-state/reset-task-type-validation-preview!)

(def create-task-type-draft! authoring-state/create-task-type-draft!)

(def publish-runtime-profile-draft! authoring-state/publish-runtime-profile-draft!)

(def publish-task-type-draft! authoring-state/publish-task-type-draft!)

(def reload-definitions! authoring-state/reload-definitions!)

(def generate-task-type-draft! authoring-state/generate-task-type-draft!)

(def load-authoring-bootstrap! authoring-state/load-authoring-bootstrap!)
