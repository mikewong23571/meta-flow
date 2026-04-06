(ns meta-flow-ui.pages.tasks.state
  (:require [meta-flow-ui.http :as http]
            [meta-flow-ui.state :as state :refer [default-create-dialog-state]]))

(defn tasks-state []
  (:tasks @state/ui-state))

(defn- error-message
  [error]
  (or (-> error ex-data :payload :error)
      (ex-message error)
      "Request failed"))

(defn load-items!
  []
  (swap! state/ui-state assoc-in [:tasks :loading?] true)
  (-> (http/fetch-json "/api/tasks")
      (.then (fn [payload]
               (swap! state/ui-state
                      (fn [ui-state]
                        (-> ui-state
                            (assoc-in [:tasks :items] (:items payload))
                            (assoc-in [:tasks :loading?] false)
                            (assoc-in [:tasks :error] nil)
                            (assoc-in [:tasks :last-success-at-ms] (.now js/Date)))))))
      (.catch (fn [error]
                (swap! state/ui-state
                       (fn [ui-state]
                         (-> ui-state
                             (assoc-in [:tasks :loading?] false)
                             (assoc-in [:tasks :error] (error-message error))))))
              nil)))

(defn ensure-polling!
  []
  (when-not (get-in @state/ui-state [:tasks :poll-timer-id])
    (load-items!)
    (let [interval-ms (get-in @state/ui-state [:tasks :poll-interval-ms])
          timer-id (.setInterval js/window load-items! interval-ms)]
      (swap! state/ui-state assoc-in [:tasks :poll-timer-id] timer-id))))

(defn stop-polling!
  []
  (when-let [timer-id (get-in @state/ui-state [:tasks :poll-timer-id])]
    (.clearInterval js/window timer-id)
    (swap! state/ui-state assoc-in [:tasks :poll-timer-id] nil)))

(defn update-filter!
  [filter-key value]
  (swap! state/ui-state assoc-in [:tasks :filters filter-key] value))

(defn load-detail!
  [kind id]
  (swap! state/ui-state
         (fn [ui-state]
           (-> ui-state
               (assoc-in [:tasks :selected-kind] kind)
               (assoc-in [:tasks :selected-id] id)
               (assoc-in [:tasks :detail] nil)
               (assoc-in [:tasks :detail-loading?] true)
               (assoc-in [:tasks :detail-error] nil))))
  (-> (http/fetch-json
       (str "/"
            (case kind
              :task "api/tasks"
              :run "api/runs")
            "/"
            (js/encodeURIComponent id)))
      (.then (fn [payload]
               (swap! state/ui-state
                      (fn [ui-state]
                        (-> ui-state
                            (assoc-in [:tasks :detail] payload)
                            (assoc-in [:tasks :detail-loading?] false)
                            (assoc-in [:tasks :detail-error] nil))))))
      (.catch (fn [error]
                (swap! state/ui-state
                       (fn [ui-state]
                         (-> ui-state
                             (assoc-in [:tasks :detail-loading?] false)
                             (assoc-in [:tasks :detail-error] (error-message error))))))
              nil)))

(defn open-create-dialog!
  []
  (swap! state/ui-state assoc-in [:tasks :create-dialog]
         (assoc default-create-dialog-state :open? true :task-types-loading? true))
  (-> (http/fetch-json "/api/task-types/create-options")
      (.then (fn [payload]
               (let [types (:items payload)
                     first-id (-> types first :task-type/id)]
                 (swap! state/ui-state update-in [:tasks :create-dialog] merge
                        {:task-types types
                         :task-types-loading? false
                         :selected-type-id first-id}))))
      (.catch (fn [_]
                (swap! state/ui-state assoc-in [:tasks :create-dialog :task-types-loading?] false)))))

(defn close-create-dialog!
  []
  (swap! state/ui-state assoc-in [:tasks :create-dialog] default-create-dialog-state))

(defn set-create-type!
  [type-id]
  (swap! state/ui-state update-in [:tasks :create-dialog] merge
         {:selected-type-id type-id :form-values {} :form-errors {}}))

(defn set-create-field!
  [field-id value]
  (swap! state/ui-state assoc-in [:tasks :create-dialog :form-values field-id] value))

(defn submit-create-task!
  []
  (let [dialog (get-in @state/ui-state [:tasks :create-dialog])
        {:keys [selected-type-id form-values task-types]} dialog
        task-type (first (filter #(= (:task-type/id %) selected-type-id) task-types))]
    (swap! state/ui-state update-in [:tasks :create-dialog] merge
           {:submitting? true :submit-error nil})
    (-> (http/post-json "/api/tasks"
                        {:task-type-id selected-type-id
                         :task-type-version (:task-type/version task-type)
                         :input form-values})
        (.then (fn [_]
                 (close-create-dialog!)
                 (load-items!)))
        (.catch (fn [error]
                  (swap! state/ui-state update-in [:tasks :create-dialog] merge
                         {:submitting? false
                          :submit-error (error-message error)}))))))

(defn clear-detail!
  []
  (swap! state/ui-state
         (fn [ui-state]
           (-> ui-state
               (assoc-in [:tasks :selected-kind] nil)
               (assoc-in [:tasks :selected-id] nil)
               (assoc-in [:tasks :detail] nil)
               (assoc-in [:tasks :detail-loading?] false)
               (assoc-in [:tasks :detail-error] nil)))))
