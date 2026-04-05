(ns meta-flow-ui.pages.preview
  (:require [cljs.pprint]
            [meta-flow-ui.components :as components]
            [meta-flow-ui.routes :as routes]
            [meta-flow-ui.state :as state]
            ["@radix-ui/react-checkbox" :as checkbox]
            ["@radix-ui/react-dialog" :as dialog]
            ["@radix-ui/react-switch" :as switch]
            ["@radix-ui/react-tabs" :as tabs]))

(defn preview-hero []
  [:section {:className "hero"}
   [:article {:className "panel panel-strong hero-copy"}
    [:span {:className "eyebrow"} "Example Interface" "Not Product Home"]
    [:h1 {:className "hero-title"} "Component preview sandbox"]
    [:p {:className "hero-subtitle"}
     "This page is a dedicated example surface for UI exploration. It is intentionally "
     "business-free and should be treated as a showcase for tokens, shared components, "
     "and Radix integration rather than as the application's homepage."]
    [:div {:className "button-row hero-actions"}
     [:button {:className "button button-secondary"
               :on-click #(routes/navigate! :home)}
      "Back to sandbox entry"]
     [:button {:className "button button-ghost"
               :on-click #(routes/navigate! :preview)}
      "Refresh preview route"]]]
   [:aside {:className "hero-sidebar"}
    [components/stat-card "Build target" "shadow-cljs" "Browser build with watch and release modes"]
    [components/stat-card "Component layer" "Radix" "Behavior primitives under app-owned visual styling"]]])

(defn controls-panel []
  [:article {:className "panel component-card stack"}
   [:div
    [:h2 {:className "component-title"} "Base controls"]
    [:p {:className "component-copy"}
     "These are app-owned components backed by semantic tokens."]]
   [:div {:className "button-row"}
    [:button {:className "button button-primary"} "Primary action"]
    [:button {:className "button button-secondary"} "Secondary action"]
    [:button {:className "button button-ghost"} "Ghost action"]]
   [:div {:className "field-row"}
    [:input {:className "text-input"
             :value (:query @state/ui-state)
             :placeholder "Type here"
             :on-change #(swap! state/ui-state assoc :query (.. % -target -value))}]]
   [:div {:className "badge-row"}
    [components/badge "success" "Ready"]
    [components/badge "warning" "Preview"]
    [components/badge "danger" "Guardrail"]
    [components/badge "info" "Hello world"]]])

(defn tabs-panel []
  [:article {:className "panel component-card"}
   [:h2 {:className "component-title"} "Radix tabs"]
   [:p {:className "component-copy"}
    "Shared interactive primitives default to Radix. Styling remains local to the app."]
   [:> tabs/Root {:value (:active-tab @state/ui-state)
                  :onValueChange #(swap! state/ui-state assoc :active-tab %)}
    [:> tabs/List {:className "tabs-list" :aria-label "Preview sections"}
     [:> tabs/Trigger {:className "tabs-trigger" :value "tokens"} "Tokens"]
     [:> tabs/Trigger {:className "tabs-trigger" :value "states"} "States"]
     [:> tabs/Trigger {:className "tabs-trigger" :value "notes"} "Notes"]]
    [:> tabs/Content {:className "tabs-content" :value "tokens"}
     [:p {:className "section-copy"}
      "Semantic tokens drive panels, inputs, badges, and status colors."]]
    [:> tabs/Content {:className "tabs-content" :value "states"}
     [:p {:className "section-copy"}
      "Interactive state is local and UI-only. No business data is involved."]]
    [:> tabs/Content {:className "tabs-content" :value "notes"}
     [:p {:className "section-copy"}
      "This preview page is the staging ground for later business screens."]]]])

(defn dialog-panel []
  [:article {:className "panel component-card stack"}
   [:div
    [:h2 {:className "component-title"} "Radix dialog"]
    [:p {:className "component-copy"}
     "The dialog demonstrates behavior-heavy interaction without importing a themed UI kit."]]
   [:> dialog/Root {:open (:dialog-open @state/ui-state)
                    :onOpenChange #(swap! state/ui-state assoc :dialog-open %)}
    [:> dialog/Trigger {:asChild true}
     [:button {:className "button button-primary radix-trigger"} "Open dialog"]]
    [:> dialog/Portal
     [:> dialog/Overlay {:className "dialog-overlay"}]
     [:> dialog/Content {:className "panel panel-strong dialog-content"}
      [:> dialog/Title {:className "dialog-title"} "Preview dialog"]
      [:> dialog/Description {:className "dialog-description"}
       "This modal is intentionally generic. The point is to prove we can layer "
       "Radix behavior under our own tokens, spacing, and typography."]
      [:div {:className "button-row"}
       [:> dialog/Close {:asChild true}
        [:button {:className "button button-primary"} "Close preview"]]
       [:button {:className "button button-secondary"
                 :on-click #(js/console.log "Meta-Flow preview action")}
        "Console action"]]]]]])

(defn toggle-panel []
  [:article {:className "panel component-card stack"}
   [:div
    [:h2 {:className "component-title"} "Switches and checks"]
    [:p {:className "component-copy"}
     "Radix primitives handle state attributes and focus behavior."]]
   [:div {:className "toggle-row"}
    [:label {:className "toggle-label"}
     [:> switch/Root {:className "radix-switch"
                      :checked (:auto-refresh @state/ui-state)
                      :onCheckedChange #(swap! state/ui-state assoc :auto-refresh %)}
      [:> switch/Thumb {:className "radix-switch-thumb"}]]
     "Auto refresh preview"]
    [:label {:className "toggle-label"}
     [:> checkbox/Root {:className "radix-checkbox"
                        :checked (:show-guides @state/ui-state)
                        :onCheckedChange #(swap! state/ui-state assoc :show-guides (not= false %))}
      [:> checkbox/Indicator {} "x"]]
     "Show guardrail hints"]]
   [:p {:className "live-state"}
    [:strong "Live state: "]
    (str "refresh=" (:auto-refresh @state/ui-state)
         ", guides=" (:show-guides @state/ui-state)
         ", tab=" (:active-tab @state/ui-state))]])

(defn token-section []
  [:section {:className "stack"}
   [:div
    [:h2 {:className "section-title"} "Visual tokens"]
    [:p {:className "section-copy"}
     "A small semantic token set is enough to keep the first preview coherent."]]
   [:div {:className "swatch-grid"}
    [components/swatch-card "Canvas" "--color-bg-canvas" {:background "var(--color-bg-canvas)"}]
    [components/swatch-card "Panel" "--color-bg-panel" {:background "var(--color-bg-panel)"}]
    [components/swatch-card "Accent" "--color-bg-accent" {:background "var(--color-bg-accent)"}]
    [components/swatch-card "Info" "--color-status-info-bg" {:background "var(--color-status-info-bg)"}]]])

(defn live-state-section []
  [:section {:className "grid"}
   [:div {:style {:gridColumn "span 12"}}
    [:h2 {:className "section-title"} "Component preview"]
    [:p {:className "section-copy"}
     "The page below is the initial shared component sandbox for future product screens."]]
   [:div {:className "component-grid" :style {:gridColumn "span 12"}}
    [controls-panel]
    [tabs-panel]
    [dialog-panel]
    [toggle-panel]]
   [:div {:style {:gridColumn "span 12"}}
    [:h2 {:className "section-title"} "Current UI state"]
    [:p {:className "section-copy"}
     "A simple local state atom is enough for this first non-business preview."]
    [:pre {:className "code-block"} (with-out-str (cljs.pprint/pprint @state/ui-state))]]])

(defn preview-page []
  [:main {:className "app-shell"}
   [preview-hero]
   [:section {:className "stack"}
    [token-section]
    [live-state-section]]])
