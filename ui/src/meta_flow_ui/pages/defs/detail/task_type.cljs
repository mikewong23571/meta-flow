(ns meta-flow-ui.pages.defs.detail.task-type
  (:require [meta-flow-ui.components :as components]
            [meta-flow-ui.pages.defs.detail.shared :as shared]
            [meta-flow-ui.pages.defs.presenter :as presenter]
            [meta-flow-ui.pages.defs.state :as defs-state]
            [meta-flow-ui.routes :as routes]))

(defn- task-type-detail-cards
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
  [shared/section
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
    [shared/section
     "Work Key"
     [:dl {:className "detail-list"}
      [components/detail-row "Type" (presenter/work-key-label work-key)]
      (when (= "work-key.type/direct" (:work-key/type work-key))
        [components/detail-row "Field" (some-> work-key :work-key/field str)])
      (when (= "work-key.type/tuple" (:work-key/type work-key))
        [:<>
         [components/detail-row "Tag" (some-> work-key :work-key/tag str)]
         [components/detail-row "Fields"
          [shared/chips work-key-fields "defs-path-item" str]]])]]))

(defn- refs-section
  [detail]
  [shared/section
   "References"
   [:dl {:className "detail-list"}
    [components/detail-row "Task FSM" (or (some-> detail :task-type/task-fsm presenter/ref-label) "n/a")]
    [components/detail-row "Run FSM" (or (some-> detail :task-type/run-fsm presenter/ref-label) "n/a")]
    [components/detail-row "Validator" (or (some-> detail :task-type/validator presenter/ref-label) "n/a")]
    [components/detail-row "Artifact contract" (or (some-> detail :task-type/artifact-contract presenter/ref-label) "n/a")]
    [components/detail-row "Resource policy" (or (some-> detail :task-type/resource-policy presenter/ref-label) "n/a")]]])

(defn- task-type-runtime-section
  [detail]
  (let [runtime-profile (:task-type/runtime-profile detail)
        mcp-servers (:runtime-profile/allowed-mcp-servers runtime-profile)
        env-allowlist (:runtime-profile/env-allowlist runtime-profile)
        skills (:runtime-profile/skills runtime-profile)
        artifact-contract (:runtime-profile/artifact-contract runtime-profile)]
    [shared/section
     "Runtime Profile"
     (if-not runtime-profile
       [:p {:className "scheduler-empty"} "n/a"]
       [:div {:className "defs-detail-stack"}
        [:dl {:className "detail-list"}
         [components/detail-row "Profile" (presenter/ref-label runtime-profile)]
         [components/detail-row "Adapter" (some-> runtime-profile :runtime-profile/adapter-id presenter/seg)]
         [components/detail-row "Dispatch" (some-> runtime-profile :runtime-profile/dispatch-mode presenter/seg)]
         [components/detail-row "Launch mode" (or (some-> runtime-profile :runtime-profile/default-launch-mode presenter/seg) "n/a")]
         [components/detail-row "Codex home" (or (:runtime-profile/codex-home-root runtime-profile) "n/a")]
         [components/detail-row "Prompt path" (or (:runtime-profile/worker-prompt-path runtime-profile) "n/a")]
         [components/detail-row "Helper script" (or (:runtime-profile/helper-script-path runtime-profile) "n/a")]
         [components/detail-row "Artifact contract" (or (some-> artifact-contract presenter/ref-label) "n/a")]
         [components/detail-row "Timeout" (or (presenter/fmt-seconds (:runtime-profile/worker-timeout-seconds runtime-profile)) "n/a")]
         [components/detail-row "Heartbeat" (or (presenter/fmt-seconds (:runtime-profile/heartbeat-interval-seconds runtime-profile)) "n/a")]
         [components/detail-row "MCP"
          (if (seq mcp-servers)
            [shared/chips mcp-servers "defs-path-item" presenter/seg]
            "none")]
         [components/detail-row "Web search"
          (if (= true (:runtime-profile/web-search-enabled? runtime-profile)) "on" "off")]
         [components/detail-row "Skills"
          (if (seq skills)
            [shared/chips skills "defs-path-item" identity]
            "none")]
         [components/detail-row "Env allowlist"
          (if (seq env-allowlist)
            [shared/chips env-allowlist "defs-path-item" identity]
            "none")]]
        [:div {:className "defs-action-row"}
         [:button {:className "button button-primary"
                   :on-click #(routes/navigate-to-runtime-profile! (get runtime-profile :definition/id)
                                                                   (get runtime-profile :definition/version))}
          "Open runtime profile"]]])]))

(defn- policy-section
  [detail]
  (let [resource-policy (:task-type/resource-policy detail)]
    [shared/section
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

(defn- task-type-artifacts-section
  [detail]
  (let [artifact-contract (:task-type/artifact-contract detail)
        required-paths (:artifact-contract/required-paths artifact-contract)
        optional-paths (:artifact-contract/optional-paths artifact-contract)]
    [shared/section
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
         [shared/chips required-paths "defs-path-item" identity]]
        [:div
         [:p {:className "defs-group-label"} "Optional"]
         (if (seq optional-paths)
           [shared/chips optional-paths "defs-path-item defs-path-item-optional" identity]
           [:p {:className "scheduler-empty"} "None"])]])]))

(defn- task-type-detail-content
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
     [task-type-detail-cards detail]
     [shared/detail-columns
      [:<>
       [input-schema-section detail]
       [work-key-section detail]
       [task-type-artifacts-section detail]]
      [:<>
       [task-type-runtime-section detail]
       [policy-section detail]
       [refs-section detail]]]]))

(defn task-type-detail-page
  [route {:keys [detail detail-loading? detail-error]} defs-tabs]
  [shared/detail-topbar {:title (or (:task-type/name detail) "Task Type")
                         :subtitle (or (:task-type/description detail)
                                       (str (:task-type-id route) " v" (:task-type-version route)))
                         :back-label "Back to task types"
                         :back-route :defs
                         :refresh! #(defs-state/load-detail! (:task-type-id route)
                                                             (:task-type-version route))
                         :defs-tabs defs-tabs
                         :active-defs-route :defs}
   [task-type-detail-content detail detail-loading? detail-error]])
