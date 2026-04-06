(ns meta-flow-ui.pages.defs.authoring.read
  (:require [meta-flow-ui.http :as http]
            [meta-flow-ui.state :as state]))

(defn error-message
  [error]
  (or (-> error ex-data :payload :error)
      (ex-message error)
      "Request failed"))

(defn assoc-in-many
  [ui-state entries]
  (reduce (fn [acc [path value]]
            (assoc-in acc path value))
          ui-state
          entries))

(defn authoring-path
  [definition-kind]
  [:defs :authoring definition-kind])

(defn definition-endpoint-segment
  [definition-kind]
  (case definition-kind
    :runtime-profile "runtime-profiles"
    :task-type "task-types"))

(defn definition-ref
  [definition-kind definition]
  (when definition
    (case definition-kind
      :runtime-profile {:definition/id (:runtime-profile/id definition)
                        :definition/version (:runtime-profile/version definition)}
      :task-type {:definition/id (:task-type/id definition)
                  :definition/version (:task-type/version definition)})))

(defn- select-default-template
  [ui-state definition-kind templates]
  (let [base-path (authoring-path definition-kind)
        selected-id (get-in ui-state (conj base-path :selected-template-id))
        selected-version (get-in ui-state (conj base-path :selected-template-version))
        selected-template? (some (fn [template]
                                   (and (= selected-id (:definition/id template))
                                        (= selected-version (:definition/version template))))
                                 templates)
        first-template (first templates)]
    (cond
      selected-template?
      ui-state

      first-template
      (-> ui-state
          (assoc-in (conj base-path :selected-template-id) (:definition/id first-template))
          (assoc-in (conj base-path :selected-template-version) (:definition/version first-template)))

      :else
      (-> ui-state
          (assoc-in (conj base-path :selected-template-id) nil)
          (assoc-in (conj base-path :selected-template-version) nil)))))

(defn load-authoring-contract!
  []
  (swap! state/ui-state
         (fn [ui-state]
           (assoc-in-many ui-state
                          [[[:defs :authoring :contract-loading?] true]
                           [[:defs :authoring :contract-error] nil]])))
  (-> (http/fetch-json "/api/defs/contract")
      (.then
       (fn [payload]
         (swap! state/ui-state
                (fn [ui-state]
                  (assoc-in-many ui-state
                                 [[[:defs :authoring :contract] payload]
                                  [[:defs :authoring :contract-loading?] false]
                                  [[:defs :authoring :contract-error] nil]])))
         payload))
      (.catch
       (fn [error]
         (swap! state/ui-state
                (fn [ui-state]
                  (assoc-in-many ui-state
                                 [[[:defs :authoring :contract-loading?] false]
                                  [[:defs :authoring :contract-error] (error-message error)]])))
         nil))))

(defn- load-templates!
  [definition-kind]
  (let [base-path (authoring-path definition-kind)
        path (str "/api/defs/" (definition-endpoint-segment definition-kind) "/templates")]
    (swap! state/ui-state
           (fn [ui-state]
             (assoc-in-many ui-state
                            [[(conj base-path :templates-loading?) true]
                             [(conj base-path :templates-error) nil]])))
    (-> (http/fetch-json path)
        (.then
         (fn [payload]
           (swap! state/ui-state
                  (fn [ui-state]
                    (-> ui-state
                        (assoc-in-many [[(conj base-path :templates) (:templates payload)]
                                        [(conj base-path :templates-loading?) false]
                                        [(conj base-path :templates-error) nil]])
                        (select-default-template definition-kind (:templates payload)))))
           payload))
        (.catch
         (fn [error]
           (swap! state/ui-state
                  (fn [ui-state]
                    (assoc-in-many ui-state
                                   [[(conj base-path :templates-loading?) false]
                                    [(conj base-path :templates-error) (error-message error)]])))
           nil)))))

(defn load-runtime-profile-templates!
  []
  (load-templates! :runtime-profile))

(defn load-task-type-templates!
  []
  (load-templates! :task-type))

(defn- load-drafts!
  [definition-kind]
  (let [base-path (authoring-path definition-kind)
        path (str "/api/defs/" (definition-endpoint-segment definition-kind) "/drafts")]
    (swap! state/ui-state
           (fn [ui-state]
             (assoc-in-many ui-state
                            [[(conj base-path :drafts-loading?) true]
                             [(conj base-path :drafts-error) nil]])))
    (-> (http/fetch-json path)
        (.then
         (fn [payload]
           (swap! state/ui-state
                  (fn [ui-state]
                    (assoc-in-many ui-state
                                   [[(conj base-path :drafts) (:items payload)]
                                    [(conj base-path :drafts-loading?) false]
                                    [(conj base-path :drafts-error) nil]])))
           payload))
        (.catch
         (fn [error]
           (swap! state/ui-state
                  (fn [ui-state]
                    (assoc-in-many ui-state
                                   [[(conj base-path :drafts-loading?) false]
                                    [(conj base-path :drafts-error) (error-message error)]])))
           nil)))))

(defn load-runtime-profile-drafts!
  []
  (load-drafts! :runtime-profile))

(defn load-task-type-drafts!
  []
  (load-drafts! :task-type))

(defn- load-draft-detail!
  [definition-kind definition-id definition-version]
  (let [base-path (authoring-path definition-kind)
        path (str "/api/defs/"
                  (definition-endpoint-segment definition-kind)
                  "/drafts/detail?definition-id="
                  (js/encodeURIComponent definition-id)
                  "&definition-version="
                  definition-version)]
    (swap! state/ui-state
           (fn [ui-state]
             (assoc-in-many ui-state
                            [[(conj base-path :draft-detail) nil]
                             [(conj base-path :draft-detail-loading?) true]
                             [(conj base-path :draft-detail-error) nil]])))
    (-> (http/fetch-json path)
        (.then
         (fn [payload]
           (swap! state/ui-state
                  (fn [ui-state]
                    (assoc-in-many ui-state
                                   [[(conj base-path :draft-detail) payload]
                                    [(conj base-path :draft-detail-loading?) false]
                                    [(conj base-path :draft-detail-error) nil]])))
           payload))
        (.catch
         (fn [error]
           (swap! state/ui-state
                  (fn [ui-state]
                    (assoc-in-many ui-state
                                   [[(conj base-path :draft-detail-loading?) false]
                                    [(conj base-path :draft-detail-error) (error-message error)]])))
           nil)))))

(defn load-runtime-profile-draft-detail!
  [definition-id definition-version]
  (load-draft-detail! :runtime-profile definition-id definition-version))

(defn load-task-type-draft-detail!
  [definition-id definition-version]
  (load-draft-detail! :task-type definition-id definition-version))
