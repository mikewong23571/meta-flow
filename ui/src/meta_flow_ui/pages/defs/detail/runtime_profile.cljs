(ns meta-flow-ui.pages.defs.detail.runtime-profile
  (:require [meta-flow-ui.components :as components]
            [meta-flow-ui.pages.defs.detail.shared :as shared]
            [meta-flow-ui.pages.defs.presenter :as presenter]
            [meta-flow-ui.pages.defs.state :as defs-state]))

(defn- runtime-detail-cards
  [detail]
  [:section {:className "defs-summary-strip"}
   [:article {:className "panel scheduler-kpi-card"}
    [:p {:className "stat-label"} "Version"]
    [:p {:className "scheduler-kpi-value"} (:runtime-profile/version detail)]]
   [:article {:className "panel scheduler-kpi-card"}
    [:p {:className "stat-label"} "Adapter"]
    [:p {:className "scheduler-kpi-value"}
     (or (some-> detail :runtime-profile/adapter-id presenter/seg) "n/a")]]
   [:article {:className "panel scheduler-kpi-card"}
    [:p {:className "stat-label"} "Dispatch"]
    [:p {:className "scheduler-kpi-value"}
     (or (some-> detail :runtime-profile/dispatch-mode presenter/seg) "n/a")]]
   [:article {:className "panel scheduler-kpi-card"}
    [:p {:className "stat-label"} "Task types"]
    [:p {:className "scheduler-kpi-value"} (count (:runtime-profile/task-types detail))]]
   [:article {:className "panel scheduler-kpi-card"}
    [:p {:className "stat-label"} "Timeout"]
    [:p {:className "scheduler-kpi-value"}
     (or (presenter/fmt-seconds (:runtime-profile/worker-timeout-seconds detail)) "n/a")]]])

(defn- runtime-config-section
  [detail]
  [shared/section
   "Execution Config"
   [:dl {:className "detail-list"}
    [components/detail-row "Profile" (str (:runtime-profile/id detail) " v" (:runtime-profile/version detail))]
    [components/detail-row "Launch mode" (or (some-> detail :runtime-profile/default-launch-mode presenter/seg) "n/a")]
    [components/detail-row "Codex home" (or (:runtime-profile/codex-home-root detail) "n/a")]
    [components/detail-row "Prompt path" (or (:runtime-profile/worker-prompt-path detail) "n/a")]
    [components/detail-row "Helper script" (or (:runtime-profile/helper-script-path detail) "n/a")]
    [components/detail-row "Heartbeat" (or (presenter/fmt-seconds (:runtime-profile/heartbeat-interval-seconds detail)) "n/a")]]])

(defn- runtime-access-section
  [detail]
  (let [mcp-servers (:runtime-profile/allowed-mcp-servers detail)
        env-allowlist (:runtime-profile/env-allowlist detail)
        skills (:runtime-profile/skills detail)]
    [shared/section
     "Access"
     [:dl {:className "detail-list"}
      [components/detail-row "MCP"
       (if (seq mcp-servers)
         [shared/chips mcp-servers "defs-path-item" presenter/seg]
         "none")]
      [components/detail-row "Web search"
       (if (= true (:runtime-profile/web-search-enabled? detail)) "on" "off")]
      [components/detail-row "Skills"
       (if (seq skills)
         [shared/chips skills "defs-path-item" identity]
         "none")]
      [components/detail-row "Env allowlist"
       (if (seq env-allowlist)
         [shared/chips env-allowlist "defs-path-item" identity]
         "none")]]]))

(defn- runtime-artifacts-section
  [detail]
  (let [artifact-contract (:runtime-profile/artifact-contract detail)
        required-paths (:artifact-contract/required-paths artifact-contract)
        optional-paths (:artifact-contract/optional-paths artifact-contract)]
    [shared/section
     "Artifact Contract"
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

(defn- runtime-task-types-section
  [detail]
  [shared/section
   "Referenced Task Types"
   [shared/task-type-links (:runtime-profile/task-types detail)]])

(defn- runtime-detail-content
  [detail detail-loading? detail-error]
  (cond
    detail-loading?
    [:p {:className "scheduler-empty"} "Loading runtime profile detail..."]

    detail-error
    [:section {:className "scheduler-inline-error"}
     [:article {:className "panel scheduler-error-card"}
      [:p {:className "scheduler-error-copy"} detail-error]]]

    (nil? detail)
    [:p {:className "scheduler-empty"} "Runtime profile detail is unavailable."]

    :else
    [:div {:className "defs-detail-body"}
     [runtime-detail-cards detail]
     [shared/detail-columns
      [:<>
       [runtime-config-section detail]
       [runtime-access-section detail]
       [runtime-artifacts-section detail]]
      [:<>
       [runtime-task-types-section detail]]]]))

(defn runtime-profile-detail-page
  [route {:keys [runtime-detail runtime-detail-loading? runtime-detail-error]} defs-tabs]
  [shared/detail-topbar {:title (or (:runtime-profile/name runtime-detail) "Runtime Profile")
                         :subtitle (or (some-> runtime-detail :runtime-profile/id str)
                                       (str (:runtime-profile-id route) " v" (:runtime-profile-version route)))
                         :back-label "Back to runtime profiles"
                         :back-route :defs-runtimes
                         :refresh! #(defs-state/load-runtime-detail! (:runtime-profile-id route)
                                                                     (:runtime-profile-version route))
                         :defs-tabs defs-tabs
                         :active-defs-route :defs-runtimes}
   [runtime-detail-content runtime-detail runtime-detail-loading? runtime-detail-error]])
