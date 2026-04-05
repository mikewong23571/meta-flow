(ns meta-flow-ui.components)

(defn badge [tone label]
  [:span {:className (str "badge badge-" tone)} label])

(defn stat-card [label value note]
  [:article {:className "panel stat-card"}
   [:p {:className "stat-label"} label]
   [:p {:className "stat-value"} value]
   [:p {:className "stat-note"} note]])

(defn swatch-card [label token style]
  [:article {:className "panel swatch-card"}
   [:div {:className "swatch-preview" :style style}]
   [:p {:className "swatch-label"} label]
   [:p {:className "swatch-token"} token]])
