(ns meta-flow-ui.pages.defs.detail
  (:require [meta-flow-ui.components :as components]
            [meta-flow-ui.pages.defs.presenter :as presenter]
            [meta-flow-ui.pages.defs.state :as defs-state]
            [meta-flow-ui.routes :as routes]))

(defn- section
  [title body]
  [:section {:className "panel defs-detail-section"}
   [:div {:className "defs-detail-section-head"}
    [:h2 {:className "component-title"} title]]
   [:div {:className "defs-detail-section-body"}
    body]])

(defn- chips
  [values item-class render-fn]
  [:div {:className "defs-inline-list"}
   (for [value values]
     ^{:key (str value)}
     [:span {:className item-class}
      (render-fn value)])])

(defn- detail-cards
  [detail]
  [:section {:className "defs-summary-strip"}
   [:article {:className "panel scheduler-kpi-card"}
    [:p {:className "stat-label"} "Version"]
    [:p {:className "scheduler-kpi-value"} (:task-type/version detail)]]
   [:article {:className "panel scheduler-kpi-card"}
    [:p {:className "stat-label"} "Input"]
    [:p {:className "scheduler-kpi-value"} (count (:task-type/input-schema detail))]]
   [:article {:className "panel scheduler-kpi-card"}
    [:p {:className "stat-label"} "Work key"]
    [:p {:className "scheduler-kpi-value"} (presenter/work-key-label (:task-type/work-key detail))]]
   [:article {:className "panel scheduler-kpi-card"}
    [:p {:className "stat-label"} "Adapter"]
    [:p {:className "scheduler-kpi-value"}
     (or (some-> detail :task-type/runtime-profile :runtime-profile/adapter-id presenter/seg) "n/a")]]
   [:article {:className "panel scheduler-kpi-card"}
    [:p {:className "stat-label"} "Policy"]
    [:p {:className "scheduler-kpi-value"}
     (or (some-> detail :task-type/resource-policy :definition/name) "n/a")]]])

(defn- input-schema-section
  [detail]
  [section
   "Input Schema"
   (if (empty? (:task-type/input-schema detail))
     [:p {:className "scheduler-empty"} "No input fields."]
     [:div {:className "defs-field-list"}
      (for [{:keys [field/id field/label field/type field/required? field/placeholder]}
            (:task-type/input-schema detail)]
        ^{:key (str id)}
        [:article {:className "defs-field-card"}
         [:div {:className "defs-field-card-head"}
          [:h3 {:className "defs-field-card-title"} label]
          [:div {:className "defs-field-meta"}
           [:span {:className "defs-type-chip"} (presenter/seg type)]
           (when required?
             [:span {:className "badge badge-info defs-required-badge"} "req"])]]
         [:dl {:className "detail-list"}
          [components/detail-row "Field id" (str id)]
          [components/detail-row "Placeholder" (or placeholder "n/a")]]])])])

(defn- work-key-section
  [detail]
  (let [work-key (:task-type/work-key detail)
        work-key-fields (:work-key/fields work-key)]
    [section
     "Work Key"
     [:dl {:className "detail-list"}
      [components/detail-row "Type" (presenter/work-key-label work-key)]
      (when (= "work-key.type/direct" (:work-key/type work-key))
        [components/detail-row "Field" (some-> work-key :work-key/field str)])
      (when (= "work-key.type/tuple" (:work-key/type work-key))
        [:<>
         [components/detail-row "Tag" (some-> work-key :work-key/tag str)]
         [components/detail-row "Fields"
          [chips work-key-fields "defs-path-item" str]]])]]))

(defn- refs-section
  [detail]
  [section
   "References"
   [:dl {:className "detail-list"}
    [components/detail-row "Task FSM" (or (some-> detail :task-type/task-fsm presenter/ref-label) "n/a")]
    [components/detail-row "Run FSM" (or (some-> detail :task-type/run-fsm presenter/ref-label) "n/a")]
    [components/detail-row "Validator" (or (some-> detail :task-type/validator presenter/ref-label) "n/a")]
    [components/detail-row "Artifact contract" (or (some-> detail :task-type/artifact-contract presenter/ref-label) "n/a")]
    [components/detail-row "Resource policy" (or (some-> detail :task-type/resource-policy presenter/ref-label) "n/a")]]])

(defn- runtime-section
  [detail]
  (let [runtime-profile (:task-type/runtime-profile detail)
        mcp-servers (:runtime-profile/allowed-mcp-servers runtime-profile)
        env-allowlist (:runtime-profile/env-allowlist runtime-profile)]
    [section
     "Runtime Profile"
     (if-not runtime-profile
       [:p {:className "scheduler-empty"} "n/a"]
       [:dl {:className "detail-list"}
        [components/detail-row "Profile" (presenter/ref-label runtime-profile)]
        [components/detail-row "Adapter" (some-> runtime-profile :runtime-profile/adapter-id presenter/seg)]
        [components/detail-row "Dispatch" (some-> runtime-profile :runtime-profile/dispatch-mode presenter/seg)]
        [components/detail-row "Launch mode" (or (some-> runtime-profile :runtime-profile/default-launch-mode presenter/seg) "n/a")]
        [components/detail-row "Timeout" (or (presenter/fmt-seconds (:runtime-profile/worker-timeout-seconds runtime-profile)) "n/a")]
        [components/detail-row "Heartbeat" (or (presenter/fmt-seconds (:runtime-profile/heartbeat-interval-seconds runtime-profile)) "n/a")]
        [components/detail-row "MCP"
         (if (seq mcp-servers)
           [chips mcp-servers "defs-path-item" presenter/seg]
           "none")]
        [components/detail-row "Web search"
         (if (= true (:runtime-profile/web-search-enabled? runtime-profile)) "on" "off")]
        [components/detail-row "Env allowlist"
         (if (seq env-allowlist)
           [chips env-allowlist "defs-path-item" identity]
           "none")]])]))

(defn- policy-section
  [detail]
  (let [resource-policy (:task-type/resource-policy detail)]
    [section
     "Resource Policy"
     (if-not resource-policy
       [:p {:className "scheduler-empty"} "n/a"]
       [:dl {:className "detail-list"}
        [components/detail-row "Policy" (presenter/ref-label resource-policy)]
        [components/detail-row "Max active runs" (:resource-policy/max-active-runs resource-policy)]
        [components/detail-row "Max attempts" (:resource-policy/max-attempts resource-policy)]
        [components/detail-row "Lease duration" (or (presenter/fmt-seconds (:resource-policy/lease-duration-seconds resource-policy)) "n/a")]
        [components/detail-row "Heartbeat timeout" (or (presenter/fmt-seconds (:resource-policy/heartbeat-timeout-seconds resource-policy)) "n/a")]
        [components/detail-row "Queue order" (some-> resource-policy :resource-policy/queue-order presenter/seg)]])]))

(defn- artifacts-section
  [detail]
  (let [artifact-contract (:task-type/artifact-contract detail)
        required-paths (:artifact-contract/required-paths artifact-contract)
        optional-paths (:artifact-contract/optional-paths artifact-contract)]
    [section
     "Artifacts"
     (if-not artifact-contract
       [:p {:className "scheduler-empty"} "n/a"]
       [:div {:className "defs-artifact-groups"}
        [:dl {:className "detail-list"}
         [components/detail-row "Contract" (presenter/ref-label artifact-contract)]
         [components/detail-row "Required paths" (:artifact-contract/required-path-count artifact-contract)]
         [components/detail-row "Optional paths" (:artifact-contract/optional-path-count artifact-contract)]]
        [:div
         [:p {:className "defs-group-label"} "Required"]
         [chips required-paths "defs-path-item" identity]]
        [:div
         [:p {:className "defs-group-label"} "Optional"]
         (if (seq optional-paths)
           [chips optional-paths "defs-path-item defs-path-item-optional" identity]
           [:p {:className "scheduler-empty"} "None"])]])]))

(defn- detail-content
  [detail detail-loading? detail-error]
  (cond
    detail-loading?
    [:p {:className "scheduler-empty"} "Loading task type detail..."]

    detail-error
    [:section {:className "scheduler-inline-error"}
     [:article {:className "panel scheduler-error-card"}
      [:p {:className "scheduler-error-copy"} detail-error]]]

    (nil? detail)
    [:p {:className "scheduler-empty"} "Task type detail is unavailable."]

    :else
    [:div {:className "defs-detail-body"}
     [detail-cards detail]
     [:section {:className "defs-detail-grid"}
      [refs-section detail]
      [work-key-section detail]
      [runtime-section detail]
      [policy-section detail]
      [artifacts-section detail]
      [input-schema-section detail]]]))

(defn detail-page
  [route {:keys [detail detail-loading? detail-error]} primary-tabs]
  [:main {:className "app-shell"}
   [:section {:className "scheduler-topbar"}
    [:div {:className "scheduler-heading"}
     [:div {:className "defs-detail-back-row"}
      [:button {:className "button button-ghost"
                :on-click #(routes/navigate! :defs)}
       "Back to list"]]
     [:h1 {:className "scheduler-title"}
      (or (:task-type/name detail) "Task Type")]
     [:p {:className "scheduler-subtitle"}
      (or (:task-type/description detail)
          (str (:task-type-id route) " v" (:task-type-version route)))]]
    [:div {:className "scheduler-topbar-actions"}
     [components/nav-tabs primary-tabs :defs routes/navigate!]
     [:button {:className "button button-primary"
               :on-click #(defs-state/load-detail! (:task-type-id route)
                                                   (:task-type-version route))}
      "Refresh"]]]
   [detail-content detail detail-loading? detail-error]])
