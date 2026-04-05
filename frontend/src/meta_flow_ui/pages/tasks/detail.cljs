(ns meta-flow-ui.pages.tasks.detail
  (:require [meta-flow-ui.components :as components]
            [meta-flow-ui.icons :as icons]))

(defn status-tone
  [label]
  (cond
    (or (= label "retryable-failed")
        (= label "needs-review")
        (= label "finalized")) "warning"
    (or (= label "running")
        (= label "awaiting-validation")
        (= label "dispatched")) "info"
    (or (= label "completed")
        (= label "queued")) "success"
    :else "info"))

(defn detail-panel
  [{:keys [selected-kind selected-id detail detail-loading? detail-error]}
   on-close
   task-state-label]
  (let [title (case selected-kind
                :task "Task detail"
                :run "Run detail"
                "Detail")]
    (when selected-id
      [:aside {:className "panel panel-strong detail-panel"}
       [:div {:className "detail-panel-header"}
        [:div
         [:h2 {:className "component-title"} title]
         [:p {:className "detail-id"} selected-id]]
        [:button {:className "button button-icon button-ghost"
                  :title "Close"
                  :on-click on-close}
         [icons/close]]]
       (cond
         detail-loading?
         [:p {:className "scheduler-empty"} "Loading detail..."]

         detail-error
         [:p {:className "scheduler-error-copy"} detail-error]

         (= selected-kind :task)
         [:dl {:className "detail-list"}
          [components/detail-row "State"
           [:span {:className "inline-badge-wrap"}
            [components/badge (status-tone (task-state-label (:task/state detail)))
             (task-state-label (:task/state detail))]]]
          [components/detail-row "Work key" (:task/work-key detail)]
          [components/detail-row "Task type"
           (str (get-in detail [:task/task-type-ref :definition/id])
                " v"
                (get-in detail [:task/task-type-ref :definition/version]))]
          [components/detail-row "Runtime profile"
           (str (get-in detail [:task/runtime-profile-ref :definition/id])
                " v"
                (get-in detail [:task/runtime-profile-ref :definition/version]))]
          [components/detail-row "Created at" (:task/created-at detail)]
          [components/detail-row "Updated at" (:task/updated-at detail)]]

         :else
         [:dl {:className "detail-list"}
          [components/detail-row "Task id" (:run/task-id detail)]
          [components/detail-row "Attempt" (:run/attempt detail)]
          [components/detail-row "State"
           [:span {:className "inline-badge-wrap"}
            [components/badge (status-tone (task-state-label (:run/state detail)))
             (task-state-label (:run/state detail))]]]
          [components/detail-row "Artifact root" (:run/artifact-root detail)]
          [components/detail-row "Event count" (:run/event-count detail)]
          [components/detail-row "Updated at" (:run/updated-at detail)]])])))
