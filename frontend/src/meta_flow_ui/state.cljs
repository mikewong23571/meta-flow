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
   :detail-error nil})

(defonce ui-state
  (r/atom {:preview default-preview-state
           :scheduler default-scheduler-state
           :tasks default-tasks-state}))
