(ns meta-flow-ui.components)

(defn badge [tone label]
  [:span {:className (str "badge badge-" tone)} label])

(defn stat-card [label value note]
  [:article {:className "panel stat-card"}
   [:p {:className "stat-label"} label]
   [:p {:className "stat-value"} (or value "n/a")]
   [:p {:className "stat-note"} note]])

(defn swatch-card [label token style]
  [:article {:className "panel swatch-card"}
   [:div {:className "swatch-preview" :style style}]
   [:p {:className "swatch-label"} label]
   [:p {:className "swatch-token"} token]])

(defn detail-row [label value]
  [:div {:className "detail-row"}
   [:dt {:className "detail-label"} label]
   [:dd {:className "detail-value"} (or value "n/a")]])

(defn nav-tabs
  [items active-route on-navigate]
  [:nav {:className "nav-tabs" :aria-label "Primary"}
   (for [{:keys [label route]} items]
     ^{:key (name route)}
     [:button {:className (str "nav-tab"
                               (when (= active-route route)
                                 " nav-tab-active"))
               :aria-current (when (= active-route route) "page")
               :on-click #(on-navigate route)}
      label])])
