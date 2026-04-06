(ns meta-flow-ui.pages.scheduler.detail
  (:require [clojure.string :as str]
            [meta-flow-ui.components :as components]
            [meta-flow-ui.icons :as icons]
            [meta-flow-ui.routes :as routes]))

(defn- short-state
  [value]
  (some-> value
          str
          (#(str/split % #"/"))
          last))

(defn- format-ref
  [ref-value]
  (when ref-value
    (str (:definition/id ref-value) " v" (:definition/version ref-value))))

(defn- runtime-profile-link
  [ref-value]
  (when ref-value
    [:button {:className "button button-ghost"
              :on-click #(routes/navigate-to-runtime-profile! (:definition/id ref-value)
                                                              (:definition/version ref-value))}
     (format-ref ref-value)]))

(defn- status-tone
  [label]
  (cond
    (or (= label "paused")
        (= label "active cooldown")
        (str/includes? label "failed")) "warning"
    (or (= label "running")
        (= label "awaiting-validation")) "info"
    (or (= label "finalized")
        (= label "completed")
        (= label "runnable")) "success"
    (= label "retryable-failed") "danger"
    :else "info"))

(defn detail-panel
  [{:keys [selected-kind selected-id detail detail-loading? detail-error]}
   on-close]
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
            [components/badge (status-tone (short-state (:task/state detail)))
             (short-state (:task/state detail))]]]
          [components/detail-row "Work key" (:task/work-key detail)]
          [components/detail-row "Task type" (format-ref (:task/task-type-ref detail))]
          [components/detail-row "Task FSM" (format-ref (:task/task-fsm-ref detail))]
          [components/detail-row "Run FSM" (format-ref (:task/run-fsm-ref detail))]
          [components/detail-row "Runtime profile" [runtime-profile-link (:task/runtime-profile-ref detail)]]
          [components/detail-row "Artifact contract" (format-ref (:task/artifact-contract-ref detail))]
          [components/detail-row "Validator" (format-ref (:task/validator-ref detail))]
          [components/detail-row "Resource policy" (format-ref (:task/resource-policy-ref detail))]
          [components/detail-row "Created at" (:task/created-at detail)]
          [components/detail-row "Updated at" (:task/updated-at detail)]]

         :else
         [:dl {:className "detail-list"}
          [components/detail-row "Task id" (:run/task-id detail)]
          [components/detail-row "Attempt" (:run/attempt detail)]
          [components/detail-row "State"
           [:span {:className "inline-badge-wrap"}
            [components/badge (status-tone (short-state (:run/state detail)))
             (short-state (:run/state detail))]]]
          [components/detail-row "Lease id" (:run/lease-id detail)]
          [components/detail-row "Artifact id" (:run/artifact-id detail)]
          [components/detail-row "Artifact root" (:run/artifact-root detail)]
          [components/detail-row "Event count" (:run/event-count detail)]
          [components/detail-row "Last heartbeat" (:run/last-heartbeat detail)]
          [components/detail-row "Created at" (:run/created-at detail)]
          [components/detail-row "Updated at" (:run/updated-at detail)]])])))
