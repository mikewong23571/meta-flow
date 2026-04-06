(ns meta-flow-ui.pages.defs.authoring.mutate
  (:require [meta-flow-ui.http :as http]
            [meta-flow-ui.pages.defs.authoring.read :as authoring-read]
            [meta-flow-ui.pages.defs.catalog.state :as catalog-state]
            [meta-flow-ui.state :as state]))

(defn- validate-draft!
  [definition-kind request]
  (let [base-path (authoring-read/authoring-path definition-kind)
        path (str "/api/defs/"
                  (authoring-read/definition-endpoint-segment definition-kind)
                  "/drafts/validate")]
    (swap! state/ui-state
           (fn [ui-state]
             (authoring-read/assoc-in-many ui-state
                                           [[(conj base-path :validation-result) nil]
                                            [(conj base-path :validation-loading?) true]
                                            [(conj base-path :validation-error) nil]])))
    (-> (http/post-json path request)
        (.then
         (fn [payload]
           (swap! state/ui-state
                  (fn [ui-state]
                    (let [ui-state* (authoring-read/assoc-in-many ui-state
                                                                  [[(conj base-path :validation-result) payload]
                                                                   [(conj base-path :validation-loading?) false]
                                                                   [(conj base-path :validation-error) nil]])
                          template (:template payload)]
                      (if template
                        (-> ui-state*
                            (assoc-in (conj base-path :selected-template-id) (:definition/id template))
                            (assoc-in (conj base-path :selected-template-version) (:definition/version template)))
                        ui-state*))))
           payload))
        (.catch
         (fn [error]
           (swap! state/ui-state
                  (fn [ui-state]
                    (authoring-read/assoc-in-many ui-state
                                                  [[(conj base-path :validation-loading?) false]
                                                   [(conj base-path :validation-error) (authoring-read/error-message error)]])))
           nil)))))

(defn validate-runtime-profile-draft!
  [request]
  (validate-draft! :runtime-profile request))

(defn validate-task-type-draft!
  [request]
  (validate-draft! :task-type request))

(defn- create-draft!
  [definition-kind request]
  (let [base-path (authoring-read/authoring-path definition-kind)
        path (str "/api/defs/"
                  (authoring-read/definition-endpoint-segment definition-kind)
                  "/drafts")]
    (swap! state/ui-state
           (fn [ui-state]
             (authoring-read/assoc-in-many ui-state
                                           [[(conj base-path :create-result) nil]
                                            [(conj base-path :submitting?) true]
                                            [(conj base-path :submit-error) nil]])))
    (-> (http/post-json path request)
        (.then
         (fn [payload]
           (let [created-ref (authoring-read/definition-ref definition-kind (:definition payload))
                 template (:template payload)]
             (swap! state/ui-state
                    (fn [ui-state]
                      (let [ui-state* (authoring-read/assoc-in-many ui-state
                                                                    [[(conj base-path :create-result) payload]
                                                                     [(conj base-path :submitting?) false]
                                                                     [(conj base-path :submit-error) nil]])]
                        (if template
                          (-> ui-state*
                              (assoc-in (conj base-path :selected-template-id) (:definition/id template))
                              (assoc-in (conj base-path :selected-template-version) (:definition/version template)))
                          ui-state*))))
             ((case definition-kind
                :runtime-profile authoring-read/load-runtime-profile-drafts!
                :task-type authoring-read/load-task-type-drafts!))
             (when created-ref
               ((case definition-kind
                  :runtime-profile authoring-read/load-runtime-profile-draft-detail!
                  :task-type authoring-read/load-task-type-draft-detail!)
                (:definition/id created-ref)
                (:definition/version created-ref)))
             payload)))
        (.catch
         (fn [error]
           (swap! state/ui-state
                  (fn [ui-state]
                    (authoring-read/assoc-in-many ui-state
                                                  [[(conj base-path :submitting?) false]
                                                   [(conj base-path :submit-error) (authoring-read/error-message error)]])))
           nil)))))

(defn create-runtime-profile-draft!
  [request]
  (create-draft! :runtime-profile request))

(defn create-task-type-draft!
  [request]
  (create-draft! :task-type request))

(defn- publish-draft!
  [definition-kind definition-ref-body]
  (let [base-path (authoring-read/authoring-path definition-kind)
        path (str "/api/defs/"
                  (authoring-read/definition-endpoint-segment definition-kind)
                  "/drafts/publish")]
    (swap! state/ui-state
           (fn [ui-state]
             (authoring-read/assoc-in-many ui-state
                                           [[(conj base-path :publish-result) nil]
                                            [(conj base-path :publishing-ref) definition-ref-body]
                                            [(conj base-path :publish-error) nil]])))
    (-> (http/post-json path definition-ref-body)
        (.then
         (fn [payload]
           (swap! state/ui-state
                  (fn [ui-state]
                    (let [detail-ref (authoring-read/definition-ref definition-kind
                                       (get-in ui-state (conj base-path :draft-detail :definition)))]
                      (cond-> (authoring-read/assoc-in-many ui-state
                                                            [[(conj base-path :publish-result) payload]
                                                             [(conj base-path :publishing-ref) nil]
                                                             [(conj base-path :publish-error) nil]])
                        (= detail-ref definition-ref-body)
                        (assoc-in (conj base-path :draft-detail) nil)

                        (= detail-ref definition-ref-body)
                        (assoc-in (conj base-path :draft-detail-loading?) false)

                        (= detail-ref definition-ref-body)
                        (assoc-in (conj base-path :draft-detail-error) nil)))))
           ((case definition-kind
              :runtime-profile authoring-read/load-runtime-profile-drafts!
              :task-type authoring-read/load-task-type-drafts!))
           ((case definition-kind
              :runtime-profile authoring-read/load-runtime-profile-templates!
              :task-type authoring-read/load-task-type-templates!))
           (case definition-kind
             :runtime-profile (catalog-state/load-runtime-items!)
             :task-type (catalog-state/load-items!))
           payload))
        (.catch
         (fn [error]
           (swap! state/ui-state
                  (fn [ui-state]
                    (authoring-read/assoc-in-many ui-state
                                                  [[(conj base-path :publishing-ref) nil]
                                                   [(conj base-path :publish-error) (authoring-read/error-message error)]])))
           nil)))))

(defn publish-runtime-profile-draft!
  [definition-ref-body]
  (publish-draft! :runtime-profile definition-ref-body))

(defn publish-task-type-draft!
  [definition-ref-body]
  (publish-draft! :task-type definition-ref-body))

(defn reload-definitions!
  []
  (swap! state/ui-state
         (fn [ui-state]
           (authoring-read/assoc-in-many ui-state
                                         [[[:defs :authoring :reload-result] nil]
                                          [[:defs :authoring :reloading?] true]
                                          [[:defs :authoring :reload-error] nil]])))
  (-> (http/post-json "/api/defs/reload" {})
      (.then
       (fn [payload]
         (swap! state/ui-state
                (fn [ui-state]
                  (authoring-read/assoc-in-many ui-state
                                                [[[:defs :authoring :reload-result] payload]
                                                 [[:defs :authoring :reloading?] false]
                                                 [[:defs :authoring :reload-error] nil]])))
         (authoring-read/load-runtime-profile-templates!)
         (authoring-read/load-task-type-templates!)
         (catalog-state/load-items!)
         (catalog-state/load-runtime-items!)
         payload))
      (.catch
       (fn [error]
         (swap! state/ui-state
                (fn [ui-state]
                  (authoring-read/assoc-in-many ui-state
                                                [[[:defs :authoring :reloading?] false]
                                                 [[:defs :authoring :reload-error] (authoring-read/error-message error)]])))
         nil))))

(defn generate-task-type-draft!
  [request]
  (swap! state/ui-state
         (fn [ui-state]
           (authoring-read/assoc-in-many ui-state
                                         [[[:defs :authoring :generation :result] nil]
                                          [[:defs :authoring :generation :submitting?] true]
                                          [[:defs :authoring :generation :submit-error] nil]])))
  (-> (http/post-json "/api/defs/task-types/generate" request)
      (.then
       (fn [payload]
         (swap! state/ui-state
                (fn [ui-state]
                  (authoring-read/assoc-in-many ui-state
                                                [[[:defs :authoring :generation :result] payload]
                                                 [[:defs :authoring :generation :submitting?] false]
                                                 [[:defs :authoring :generation :submit-error] nil]])))
         (authoring-read/load-runtime-profile-drafts!)
         (authoring-read/load-task-type-drafts!)
         (when-let [task-definition (get-in payload [:task-type :definition])]
           (authoring-read/load-task-type-draft-detail!
            (:task-type/id task-definition)
            (:task-type/version task-definition)))
         payload))
      (.catch
       (fn [error]
         (swap! state/ui-state
                (fn [ui-state]
                  (authoring-read/assoc-in-many ui-state
                                                [[[:defs :authoring :generation :submitting?] false]
                                                 [[:defs :authoring :generation :submit-error] (authoring-read/error-message error)]])))
         nil))))
