(ns meta-flow-ui.pages.defs.authoring.state
  (:require [meta-flow-ui.pages.defs.authoring.bootstrap :as bootstrap]
            [meta-flow-ui.pages.defs.authoring.mutate :as mutate]
            [meta-flow-ui.pages.defs.authoring.reset :as reset]))

(def validate-runtime-profile-draft! mutate/validate-runtime-profile-draft!)
(def validate-task-type-draft! mutate/validate-task-type-draft!)
(def create-runtime-profile-draft! mutate/create-runtime-profile-draft!)
(def create-task-type-draft! mutate/create-task-type-draft!)
(def publish-runtime-profile-draft! mutate/publish-runtime-profile-draft!)
(def publish-task-type-draft! mutate/publish-task-type-draft!)
(def reload-definitions! mutate/reload-definitions!)
(def generate-task-type-draft! mutate/generate-task-type-draft!)
(def reset-runtime-profile-authoring-feedback! reset/reset-runtime-profile-authoring-feedback!)
(def reset-task-type-authoring-feedback! reset/reset-task-type-authoring-feedback!)
(def reset-runtime-profile-validation-preview! reset/reset-runtime-profile-validation-preview!)
(def reset-task-type-validation-preview! reset/reset-task-type-validation-preview!)
(def load-authoring-bootstrap! bootstrap/load-authoring-bootstrap!)
