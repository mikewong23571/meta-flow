(ns meta-flow-ui.pages.defs.state
  (:require [meta-flow-ui.http :as http]
            [meta-flow-ui.state :as state]))

(defn defs-state []
  (:defs @state/ui-state))

(defn- error-message [error]
  (or (-> error ex-data :payload :error)
      (ex-message error)
      "Request failed"))

(defn load-items!
  []
  (swap! state/ui-state assoc-in [:defs :loading?] true)
  (-> (http/fetch-json "/api/task-types")
      (.then (fn [payload]
               (swap! state/ui-state
                      (fn [ui-state]
                        (-> ui-state
                            (assoc-in [:defs :items] (:items payload))
                            (assoc-in [:defs :loading?] false)
                            (assoc-in [:defs :error] nil))))))
      (.catch (fn [error]
                (swap! state/ui-state
                       (fn [ui-state]
                         (-> ui-state
                             (assoc-in [:defs :loading?] false)
                             (assoc-in [:defs :error] (error-message error)))))))))

(defn load-detail!
  [task-type-id task-type-version]
  (swap! state/ui-state
         (fn [ui-state]
           (-> ui-state
               (assoc-in [:defs :detail] nil)
               (assoc-in [:defs :detail-loading?] true)
               (assoc-in [:defs :detail-error] nil))))
  (-> (http/fetch-json
       (str "/api/task-types/detail?task-type-id="
            (js/encodeURIComponent task-type-id)
            "&task-type-version="
            task-type-version))
      (.then (fn [payload]
               (swap! state/ui-state
                      (fn [ui-state]
                        (-> ui-state
                            (assoc-in [:defs :detail] payload)
                            (assoc-in [:defs :detail-loading?] false)
                            (assoc-in [:defs :detail-error] nil))))))
      (.catch (fn [error]
                (swap! state/ui-state
                       (fn [ui-state]
                         (-> ui-state
                             (assoc-in [:defs :detail-loading?] false)
                             (assoc-in [:defs :detail-error] (error-message error))))))
              nil)))
