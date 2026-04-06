(ns meta-flow-ui.state
  (:require [reagent.core :as r]))

(defonce react-root (atom nil))

(def default-preview-state
  {:query "preview component states"
   :dialog-open false
   :auto-refresh true
   :show-guides true
   :active-tab "tokens"})

(def default-scheduler-state
  {:overview nil
   :overview-loading? false
   :overview-error nil
   :last-success-at-ms nil
   :poll-interval-ms 5000
   :poll-timer-id nil
   :selected-kind nil
   :selected-id nil
   :detail nil
   :detail-loading? false
   :detail-error nil})

(def default-create-dialog-state
  {:open? false
   :task-types []
   :task-types-loading? false
   :selected-type-id nil
   :form-values {}
   :form-errors {}
   :submitting? false
   :submit-error nil})

(def default-tasks-state
  {:items []
   :loading? false
   :error nil
   :last-success-at-ms nil
   :poll-interval-ms 5000
   :poll-timer-id nil
   :filters {:task-state "all"
             :task-type "all"
             :query ""}
   :selected-kind nil
   :selected-id nil
   :detail nil
   :detail-loading? false
   :detail-error nil
   :create-dialog default-create-dialog-state})

(def default-defs-authoring-kind-state
  {:templates []
   :templates-loading? false
   :templates-error nil
   :selected-template-id nil
   :selected-template-version nil
   :form-values {}
   :validation-result nil
   :validation-loading? false
   :validation-error nil
   :drafts []
   :drafts-loading? false
   :drafts-error nil
   :draft-detail nil
   :draft-detail-loading? false
   :draft-detail-error nil
   :create-result nil
   :submitting? false
   :submit-error nil
   :publish-result nil
   :publishing-ref nil
   :publish-error nil})

(def default-defs-generation-state
  {:form-values {}
   :result nil
   :submitting? false
   :submit-error nil})

(def default-defs-authoring-state
  {:contract nil
   :contract-loading? false
   :contract-error nil
   :reload-result nil
   :reloading? false
   :reload-error nil
   :runtime-profile default-defs-authoring-kind-state
   :task-type default-defs-authoring-kind-state
   :generation default-defs-generation-state})

(def default-defs-state
  {:items []
   :loading? false
   :error nil
   :detail nil
   :detail-loading? false
   :detail-error nil
   :runtime-items []
   :runtime-loading? false
   :runtime-error nil
   :runtime-detail nil
   :runtime-detail-loading? false
   :runtime-detail-error nil
   :authoring default-defs-authoring-state})

(defonce ui-state
  (r/atom {:preview default-preview-state
           :scheduler default-scheduler-state
           :tasks default-tasks-state
           :defs default-defs-state}))
