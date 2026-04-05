(ns meta-flow-ui.pages.scheduler.state
  (:require [meta-flow-ui.http :as http]
            [meta-flow-ui.state :as state]))

(defn scheduler-state []
  (:scheduler @state/ui-state))

(defn- error-message
  [error]
  (or (-> error ex-data :payload :error)
      (ex-message error)
      "Request failed"))

(defn load-overview!
  []
  (swap! state/ui-state assoc-in [:scheduler :overview-loading?] true)
  (-> (http/fetch-json "/api/scheduler/overview")
      (.then (fn [payload]
               (swap! state/ui-state
                      (fn [ui-state]
                        (-> ui-state
                            (assoc-in [:scheduler :overview] payload)
                            (assoc-in [:scheduler :overview-loading?] false)
                            (assoc-in [:scheduler :overview-error] nil)
                            (assoc-in [:scheduler :last-success-at-ms] (.now js/Date)))))))
      (.catch (fn [error]
                (swap! state/ui-state
                       (fn [ui-state]
                         (-> ui-state
                             (assoc-in [:scheduler :overview-loading?] false)
                             (assoc-in [:scheduler :overview-error] (error-message error))))))
              nil)))

(defn- detail-path
  [kind id]
  (str "/"
       (case kind
         :task "api/tasks"
         :run "api/runs")
       "/"
       (js/encodeURIComponent id)))

(defn load-detail!
  [kind id]
  (swap! state/ui-state
         (fn [ui-state]
           (-> ui-state
               (assoc-in [:scheduler :selected-kind] kind)
               (assoc-in [:scheduler :selected-id] id)
               (assoc-in [:scheduler :detail] nil)
               (assoc-in [:scheduler :detail-loading?] true)
               (assoc-in [:scheduler :detail-error] nil))))
  (-> (http/fetch-json (detail-path kind id))
      (.then (fn [payload]
               (swap! state/ui-state
                      (fn [ui-state]
                        (-> ui-state
                            (assoc-in [:scheduler :detail] payload)
                            (assoc-in [:scheduler :detail-loading?] false)
                            (assoc-in [:scheduler :detail-error] nil))))))
      (.catch (fn [error]
                (swap! state/ui-state
                       (fn [ui-state]
                         (-> ui-state
                             (assoc-in [:scheduler :detail-loading?] false)
                             (assoc-in [:scheduler :detail-error] (error-message error))))))
              nil)))

(defn clear-detail!
  []
  (swap! state/ui-state
         (fn [ui-state]
           (-> ui-state
               (assoc-in [:scheduler :selected-kind] nil)
               (assoc-in [:scheduler :selected-id] nil)
               (assoc-in [:scheduler :detail] nil)
               (assoc-in [:scheduler :detail-loading?] false)
               (assoc-in [:scheduler :detail-error] nil)))))

(defn ensure-polling!
  []
  (when-not (get-in @state/ui-state [:scheduler :poll-timer-id])
    (load-overview!)
    (let [interval-ms (get-in @state/ui-state [:scheduler :poll-interval-ms])
          timer-id (.setInterval js/window load-overview! interval-ms)]
      (swap! state/ui-state assoc-in [:scheduler :poll-timer-id] timer-id))))

(defn stop-polling!
  []
  (when-let [timer-id (get-in @state/ui-state [:scheduler :poll-timer-id])]
    (.clearInterval js/window timer-id)
    (swap! state/ui-state assoc-in [:scheduler :poll-timer-id] nil)))

(defn stale?
  []
  (let [{:keys [last-success-at-ms poll-interval-ms]} (scheduler-state)]
    (boolean (and last-success-at-ms
                  (> (- (.now js/Date) last-success-at-ms)
                     (* 3 poll-interval-ms))))))
