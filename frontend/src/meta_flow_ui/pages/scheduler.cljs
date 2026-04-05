(ns meta-flow-ui.pages.scheduler
  (:require [clojure.string :as str]
            [meta-flow-ui.components :as components]
            [meta-flow-ui.icons :as icons]
            [meta-flow-ui.pages.scheduler.detail :as scheduler-detail]
            [meta-flow-ui.pages.scheduler.state :as scheduler-page-state]
            [meta-flow-ui.routes :as routes]
            [reagent.core :as r]))

(def primary-tabs
  [{:label "Scheduler" :route :scheduler}
   {:label "Tasks" :route :tasks}
   {:label "Defs" :route :defs}
   {:label "Preview" :route :preview}])

(def bucket-config
  [{:rank 1
    :count-key :heartbeat-timeout-count
    :ids-key :heartbeat-timeout-run-ids
    :kind :run
    :label "Heartbeat timeout"
    :tone "danger"}
   {:rank 2
    :count-key :expired-lease-count
    :ids-key :expired-lease-run-ids
    :kind :run
    :label "Expired lease"
    :tone "warning"}
   {:rank 3
    :count-key :awaiting-validation-count
    :ids-key :awaiting-validation-run-ids
    :kind :run
    :label "Awaiting validation"
    :tone "info"}
   {:rank 4
    :count-key :retryable-failed-count
    :ids-key :retryable-failed-task-ids
    :kind :task
    :label "Retryable failed"
    :tone "warning"}
   {:rank 5
    :count-key :runnable-count
    :ids-key :runnable-task-ids
    :kind :task
    :label "Runnable"
    :tone "success"}])

(defn short-ref
  "Strip type-namespace prefix (e.g. resource-policy/default → default v3)."
  [ref-value]
  (when ref-value
    (let [id (-> (:definition/id ref-value) str (str/replace #"^[^/]+/" ""))]
      (str id " v" (:definition/version ref-value)))))

(defn snapshot-time
  "Extract HH:MM:SS from ISO-8601 string."
  [iso-str]
  (when iso-str
    (let [t (.indexOf iso-str "T")]
      (if (>= t 0) (subs iso-str (inc t) (min (+ t 9) (count iso-str))) iso-str))))

(defn summary-value
  [snapshot collection key-name]
  (case key-name
    :dispatch      (if (get-in snapshot [:dispatch-paused?]) "Paused" "Active")
    :cooldown      (if (get-in snapshot [:dispatch-cooldown-active?])
                     (str "Until " (get-in snapshot [:dispatch-cooldown-until]))
                     "Inactive")
    :active-runs   (str (or (:active-run-count snapshot) 0))
    :snapshot-time (or (snapshot-time (:now snapshot)) "n/a")
    :policy        (or (short-ref (:resource-policy-ref collection)) "n/a")))

(defn bucket-row
  [snapshot {:keys [rank count-key ids-key kind label tone]}]
  (let [count-value (get snapshot count-key 0)
        ids         (get snapshot ids-key [])
        sample?     (> count-value (count ids))]
    [:tr {:className (str "scheduler-table-row scheduler-table-row-" tone)}
     [:td {:className "scheduler-table-rank"} (str rank)]
     [:td {:className "scheduler-table-bucket"} label]
     [:td {:className "scheduler-table-kind"} (name kind)]
     [:td {:className "scheduler-table-count"} (str count-value)]
     [:td {:className "scheduler-table-sample"}
      (if (pos? count-value)
        [:div {:className "bucket-id-list"}
         (for [id ids]
           ^{:key (str (name kind) "-" id)}
           [:button {:className "bucket-id-button"
                     :on-click #(scheduler-page-state/load-detail! kind id)}
            id])
         (when sample?
           [:span {:className "scheduler-more-indicator"}
            (str "+" (- count-value (count ids)))])]
        [:span {:className "scheduler-empty"} "—"])]]))

(defn scheduler-page-body []
  (let [page-state (scheduler-page-state/scheduler-state)
        {:keys [overview overview-loading? overview-error poll-interval-ms]} page-state
        snapshot   (:snapshot overview)
        collection (:collection overview)]
    [:main {:className "app-shell"}
     [:section {:className "scheduler-topbar"}
      [:div {:className "scheduler-heading"}
       [:h1 {:className "scheduler-title"} "Scheduler"]]
      [:div {:className "scheduler-topbar-actions"}
       [components/nav-tabs primary-tabs :scheduler routes/navigate!]
       [:button {:className "button button-icon button-primary"
                 :title "Refresh"
                 :on-click scheduler-page-state/load-overview!}
        [icons/refresh]]]]
     [:section {:className "scheduler-summary-strip"}
      (for [[label value-key] [["Dispatch" :dispatch]
                               ["Cooldown" :cooldown]
                               ["Active runs" :active-runs]
                               ["Snapshot" :snapshot-time]
                               ["Policy" :policy]]]
        ^{:key label}
        [:article {:className "panel scheduler-kpi-card"}
         [:p {:className "stat-label"} label]
         [:p {:className "scheduler-kpi-value"} (summary-value snapshot collection value-key)]])
      [:article {:className "panel scheduler-kpi-card"}
       [:p {:className "stat-label"} "Polling"]
       [:div {:className "poll-status"}
        [:span {:className (str "scheduler-kpi-value"
                                (when (scheduler-page-state/stale?) " poll-status-label-stale"))}
         (cond overview-loading? "Refreshing" (scheduler-page-state/stale?) "Stale" :else "Fresh")]
        [:span {:className (str "poll-dot"
                                (cond overview-loading? " poll-dot-loading"
                                      (scheduler-page-state/stale?) " poll-dot-stale"
                                      :else             ""))}]
        [:span {:className "poll-interval"}
         (str (/ poll-interval-ms 1000) "s")]]]]
     (when overview-error
       [:section {:className "scheduler-inline-error"}
        [:article {:className "panel scheduler-error-card"}
         [:p {:className "scheduler-error-copy"} overview-error]]])
     [:section {:className "detail-layout"}
      [:section {:className "panel scheduler-table-panel"}
       [:div {:className "scheduler-table-wrap"}
        [:table {:className "scheduler-table"}
         [:colgroup
          [:col {:className "scheduler-col-rank"}]
          [:col {:className "scheduler-col-bucket"}]
          [:col {:className "scheduler-col-kind"}]
          [:col {:className "scheduler-col-count"}]
          [:col {:className "scheduler-col-sample"}]]
         [:thead
          [:tr
           [:th "P"]
           [:th "Bucket"]
           [:th "Kind"]
           [:th "Count"]
           [:th "Sample ids"]]]
         [:tbody
          (for [bucket bucket-config]
            ^{:key (name (:count-key bucket))}
            [bucket-row snapshot bucket])]]]]
      (when (:selected-id page-state)
        [scheduler-detail/detail-panel page-state scheduler-page-state/clear-detail!])]]))

(defn scheduler-page []
  (r/with-let [_ (scheduler-page-state/ensure-polling!)]
    [scheduler-page-body]
    (finally
      (scheduler-page-state/stop-polling!))))
