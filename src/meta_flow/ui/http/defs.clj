(ns meta-flow.ui.http.defs
  (:require [meta-flow.ui.defs.authoring :as ui.defs.authoring]
            [meta-flow.ui.http.defs.catalog :as defs.catalog]))

(def ^:private definition-draft-detail-query-params
  [:map
   [:definition-id :string]
   [:definition-version :int]])

(def ^:private definition-ref-body
  [:map
   [:definition/id :string]
   [:definition/version :int]])

(def ^:private input-field-body
  [:map
   [:field/id :string]
   [:field/label :string]
   [:field/type :string]
   [:field/required? :boolean]
   [:field/placeholder {:optional true} :string]])

(def ^:private work-key-expr-body
  [:multi {:dispatch :work-key/type}
   ["work-key.type/direct"
    [:map
     [:work-key/type [:= "work-key.type/direct"]]
     [:work-key/field :string]]]
   ["work-key.type/tuple"
    [:map
     [:work-key/type [:= "work-key.type/tuple"]]
     [:work-key/tag :string]
     [:work-key/fields [:vector :string]]]]])

(def ^:private runtime-profile-draft-request-body
  [:map
   [:authoring/from-id :string]
   [:authoring/from-version {:optional true} :int]
   [:authoring/new-id :string]
   [:authoring/new-name :string]
   [:authoring/new-version {:optional true} :int]
   [:authoring/overrides {:optional true}
    [:map
     [:runtime-profile/web-search-enabled? {:optional true} :boolean]
     [:runtime-profile/worker-prompt-path {:optional true} :string]]]])

(def ^:private task-type-draft-request-body
  [:map
   [:authoring/from-id :string]
   [:authoring/from-version {:optional true} :int]
   [:authoring/new-id :string]
   [:authoring/new-name :string]
   [:authoring/new-version {:optional true} :int]
   [:authoring/overrides {:optional true}
    [:map
     [:task-type/runtime-profile-ref {:optional true} definition-ref-body]
     [:task-type/input-schema {:optional true} [:vector input-field-body]]
     [:task-type/work-key-expr {:optional true} work-key-expr-body]]]])

(defn- normalize-definition-ref
  [definition-ref]
  {:definition/id (keyword (:definition/id definition-ref))
   :definition/version (:definition/version definition-ref)})

(defn- normalize-input-field
  [input-field]
  (-> input-field
      (update :field/id keyword)
      (update :field/type keyword)))

(defn- normalize-work-key-expr
  [work-key-expr]
  (cond-> (update work-key-expr :work-key/type keyword)
    (contains? work-key-expr :work-key/field)
    (update :work-key/field keyword)

    (contains? work-key-expr :work-key/tag)
    (update :work-key/tag keyword)

    (contains? work-key-expr :work-key/fields)
    (update :work-key/fields #(mapv keyword %))))

(defn- normalize-runtime-profile-draft-request
  [request]
  (-> request
      (update :authoring/from-id keyword)
      (update :authoring/new-id keyword)
      (update :authoring/overrides #(or % {}))))

(defn- normalize-task-type-draft-request
  [request]
  (cond-> (-> request
              (update :authoring/from-id keyword)
              (update :authoring/new-id keyword)
              (update :authoring/overrides #(or % {})))
    (get-in request [:authoring/overrides :task-type/runtime-profile-ref])
    (update-in [:authoring/overrides :task-type/runtime-profile-ref] normalize-definition-ref)

    (get-in request [:authoring/overrides :task-type/input-schema])
    (update-in [:authoring/overrides :task-type/input-schema]
               #(mapv normalize-input-field %))

    (get-in request [:authoring/overrides :task-type/work-key-expr])
    (update-in [:authoring/overrides :task-type/work-key-expr] normalize-work-key-expr)))

(defn- defs-authoring-contract-handler
  [_]
  {:status 200
   :body (ui.defs.authoring/authoring-contract)})

(defn- runtime-profile-templates-handler
  [defs-repo _]
  {:status 200
   :body (ui.defs.authoring/list-runtime-profile-templates defs-repo)})

(defn- task-type-templates-handler
  [defs-repo _]
  {:status 200
   :body (ui.defs.authoring/list-task-type-templates defs-repo)})

(defn- runtime-profile-drafts-handler
  [defs-repo _]
  {:status 200
   :body (ui.defs.authoring/list-runtime-profile-drafts defs-repo)})

(defn- runtime-profile-draft-detail-handler
  [defs-repo {{{:keys [definition-id definition-version]} :query} :parameters}]
  {:status 200
   :body (ui.defs.authoring/load-runtime-profile-draft defs-repo
                                                       (keyword definition-id)
                                                       definition-version)})

(defn- validate-runtime-profile-draft-handler
  [defs-repo {{{:as body} :body} :parameters}]
  {:status 200
   :body (ui.defs.authoring/validate-runtime-profile-draft-request!
          defs-repo
          (normalize-runtime-profile-draft-request body))})

(defn- create-runtime-profile-draft-handler
  [defs-repo {{{:as body} :body} :parameters}]
  {:status 201
   :body (ui.defs.authoring/create-runtime-profile-draft!
          defs-repo
          (normalize-runtime-profile-draft-request body))})

(defn- publish-runtime-profile-draft-handler
  [defs-repo {{{:as body} :body} :parameters}]
  {:status 200
   :body (ui.defs.authoring/publish-runtime-profile-draft!
          defs-repo
          (normalize-definition-ref body))})

(defn- task-type-drafts-handler
  [defs-repo _]
  {:status 200
   :body (ui.defs.authoring/list-task-type-drafts defs-repo)})

(defn- task-type-draft-detail-handler
  [defs-repo {{{:keys [definition-id definition-version]} :query} :parameters}]
  {:status 200
   :body (ui.defs.authoring/load-task-type-draft defs-repo
                                                 (keyword definition-id)
                                                 definition-version)})

(defn- validate-task-type-draft-handler
  [defs-repo {{{:as body} :body} :parameters}]
  {:status 200
   :body (ui.defs.authoring/validate-task-type-draft-request!
          defs-repo
          (normalize-task-type-draft-request body))})

(defn- create-task-type-draft-handler
  [defs-repo {{{:as body} :body} :parameters}]
  {:status 201
   :body (ui.defs.authoring/create-task-type-draft!
          defs-repo
          (normalize-task-type-draft-request body))})

(defn- publish-task-type-draft-handler
  [defs-repo {{{:as body} :body} :parameters}]
  {:status 200
   :body (ui.defs.authoring/publish-task-type-draft!
          defs-repo
          (normalize-definition-ref body))})

(defn- defs-reload-handler
  [defs-repo _]
  {:status 200
   :body (ui.defs.authoring/reload-definition-repository! defs-repo)})

(defn routes
  [defs-repo]
  (into [["/defs"
          ["/contract"
           {:get {:handler defs-authoring-contract-handler}}]
          ["/runtime-profiles/templates"
           {:get {:handler (partial runtime-profile-templates-handler defs-repo)}}]
          ["/runtime-profiles/drafts"
           {:get {:handler (partial runtime-profile-drafts-handler defs-repo)}
            :post {:parameters {:body runtime-profile-draft-request-body}
                   :handler (partial create-runtime-profile-draft-handler defs-repo)}}]
          ["/runtime-profiles/drafts/detail"
           {:get {:parameters {:query definition-draft-detail-query-params}
                  :handler (partial runtime-profile-draft-detail-handler defs-repo)}}]
          ["/runtime-profiles/drafts/validate"
           {:post {:parameters {:body runtime-profile-draft-request-body}
                   :handler (partial validate-runtime-profile-draft-handler defs-repo)}}]
          ["/runtime-profiles/drafts/publish"
           {:post {:parameters {:body definition-ref-body}
                   :handler (partial publish-runtime-profile-draft-handler defs-repo)}}]
          ["/task-types/templates"
           {:get {:handler (partial task-type-templates-handler defs-repo)}}]
          ["/task-types/drafts"
           {:get {:handler (partial task-type-drafts-handler defs-repo)}
            :post {:parameters {:body task-type-draft-request-body}
                   :handler (partial create-task-type-draft-handler defs-repo)}}]
          ["/task-types/drafts/detail"
           {:get {:parameters {:query definition-draft-detail-query-params}
                  :handler (partial task-type-draft-detail-handler defs-repo)}}]
          ["/task-types/drafts/validate"
           {:post {:parameters {:body task-type-draft-request-body}
                   :handler (partial validate-task-type-draft-handler defs-repo)}}]
          ["/task-types/drafts/publish"
           {:post {:parameters {:body definition-ref-body}
                   :handler (partial publish-task-type-draft-handler defs-repo)}}]
          ["/reload"
           {:post {:handler (partial defs-reload-handler defs-repo)}}]]]
        (defs.catalog/routes defs-repo)))
