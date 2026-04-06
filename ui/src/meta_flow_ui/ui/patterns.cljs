(ns meta-flow-ui.ui.patterns)

(defn badge
  [tone label]
  [:span {:className (str "badge badge-" tone)} label])

(defn stat-card
  [label value note]
  [:article {:className "panel stat-card"}
   [:p {:className "stat-label"} label]
   [:p {:className "stat-value"} (or value "n/a")]
   [:p {:className "stat-note"} note]])

(defn swatch-card
  [label token style]
  [:article {:className "panel swatch-card"}
   [:div {:className "swatch-preview" :style style}]
   [:p {:className "swatch-label"} label]
   [:p {:className "swatch-token"} token]])

(defn detail-row
  [label value]
  [:div {:className "detail-row"}
   [:dt {:className "detail-label"} label]
   [:dd {:className "detail-value"} (or value "n/a")]])
