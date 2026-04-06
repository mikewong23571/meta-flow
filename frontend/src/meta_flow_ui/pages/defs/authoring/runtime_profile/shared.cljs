(ns meta-flow-ui.pages.defs.authoring.runtime-profile.shared
  (:require [clojure.string :as str]
            [cljs.pprint :refer [pprint]]
            [meta-flow-ui.components :as components]
            [meta-flow-ui.pages.defs.state :as defs-state]))

(defn draft-ref
  [draft]
  {:definition/id (:definition/id draft)
   :definition/version (:definition/version draft)})

(defn format-template-value
  [template]
  (str (:definition/id template) "|" (:definition/version template)))

(defn parse-template-value
  [value]
  (let [[definition-id definition-version] (str/split value #"\|" 2)]
    {:definition/id definition-id
     :definition/version (js/parseInt definition-version 10)}))

(defn selected-template
  [templates selected-value]
  (let [template-ref (if (str/blank? selected-value)
                       (draft-ref (first templates))
                       (parse-template-value selected-value))]
    (or (some (fn [template]
                (when (and (= (:definition/id template-ref) (:definition/id template))
                           (= (:definition/version template-ref) (:definition/version template)))
                  template))
              templates)
        (first templates))))

(defn blank-dialog-state
  [template]
  {:open? false
   :selected-template-value (when template
                              (format-template-value template))
   :new-id ""
   :new-name ""
   :new-version ""
   :web-search-mode "inherit"
   :worker-prompt-path ""})

(defn definition-ref-label
  [definition-ref]
  (when definition-ref
    (str (:definition/id definition-ref) " v" (:definition/version definition-ref))))

(defn runtime-definition-ref
  [definition]
  {:definition/id (:runtime-profile/id definition)
   :definition/version (:runtime-profile/version definition)})

(defn current-draft-label
  [definition]
  (definition-ref-label (runtime-definition-ref definition)))

(defn- parse-version
  [value]
  (let [trimmed (some-> value str/trim)]
    (when-not (str/blank? trimmed)
      (when (re-matches #"[1-9]\d*" trimmed)
        (js/parseInt trimmed 10)))))

(defn build-form-errors
  [dialog-state template]
  (let [new-id (some-> (:new-id dialog-state) str/trim)
        new-name (some-> (:new-name dialog-state) str/trim)
        version-text (some-> (:new-version dialog-state) str/trim)]
    (cond-> {}
      (nil? template)
      (assoc :template "Choose a template before validating or creating a draft.")

      (str/blank? new-id)
      (assoc :new-id "Enter a new runtime-profile id.")

      (and (not (str/blank? new-id))
           (not (str/starts-with? new-id "runtime-profile/")))
      (assoc :new-id "Use the runtime-profile/... keyword namespace.")

      (str/blank? new-name)
      (assoc :new-name "Enter a display name for the new runtime profile.")

      (and (not (str/blank? version-text))
           (nil? (parse-version version-text)))
      (assoc :new-version "Use a positive integer version, or leave it blank for v1."))))

(defn build-request
  [dialog-state template]
  (let [version (parse-version (:new-version dialog-state))
        prompt-path (some-> (:worker-prompt-path dialog-state) str/trim not-empty)
        web-search-enabled? (case (:web-search-mode dialog-state)
                              "enabled" true
                              "disabled" false
                              nil)
        overrides (cond-> {}
                    (some? web-search-enabled?)
                    (assoc :runtime-profile/web-search-enabled? web-search-enabled?)

                    prompt-path
                    (assoc :runtime-profile/worker-prompt-path prompt-path))]
    (cond-> {:authoring/from-id (:definition/id template)
             :authoring/from-version (:definition/version template)
             :authoring/new-id (str/trim (:new-id dialog-state))
             :authoring/new-name (str/trim (:new-name dialog-state))}
      version
      (assoc :authoring/new-version version)

      (seq overrides)
      (assoc :authoring/overrides overrides))))

(defn edn-block
  [value]
  (with-out-str (pprint value)))

(defn- status-card
  [tone title body]
  [:article {:className (str "panel defs-authoring-status defs-authoring-status-" tone)}
   [:div {:className "defs-authoring-status-head"}
    [components/badge tone title]]
   [:div {:className "defs-authoring-status-body"}
    body]])

(defn- result-strip
  [authoring runtime-authoring]
  [:div {:className "defs-authoring-status-grid"}
   (when-let [result (:create-result runtime-authoring)]
     [status-card "success"
      "Draft Created"
      [:<>
       [:p {:className "defs-authoring-copy"}
        "Draft saved to the overlay workspace and loaded into the inspection panel."]
       [:p {:className "defs-authoring-meta"}
        (current-draft-label (:definition result))]]])
   (when-let [result (:publish-result runtime-authoring)]
     [status-card "success"
      "Published"
      [:<>
       [:p {:className "defs-authoring-copy"}
        "Published definitions were reloaded into the running repository."]
       [:p {:className "defs-authoring-meta"}
        (current-draft-label (:definition result))]]])
   (when-let [error (:publish-error runtime-authoring)]
     [status-card "danger"
      "Publish Error"
      [:p {:className "defs-authoring-copy"} error]])
   (when-let [error (:reload-error authoring)]
     [status-card "danger"
      "Reload Error"
      [:p {:className "defs-authoring-copy"} error]])])

(defn summary-panel
  [authoring runtime-authoring runtime-items]
  (let [supported-keys (get-in authoring [:contract :runtime-profile :supported-override-keys])]
    [:section {:className "panel defs-authoring-panel"}
     [:div {:className "defs-authoring-panel-head"}
      [:div
       [:h2 {:className "component-title"} "Runtime Profile Authoring"]
       [:p {:className "defs-authoring-copy"}
        "Clone a published template, optionally override the stable runtime-profile fields, and publish the draft into the live defs catalog."]]
      [:div {:className "defs-authoring-panel-actions"}
       [:button {:className "button button-secondary"
                 :on-click defs-state/reload-definitions!
                 :disabled (:reloading? authoring)}
        (if (:reloading? authoring) "Reloading…" "Reload Definitions")]]]
     [:div {:className "defs-authoring-summary-grid"}
      [components/stat-card "Published runtime profiles"
       (count runtime-items)
       "Reload is automatic after publish."]
      [components/stat-card "Runtime templates"
       (count (:templates runtime-authoring))
       "Templates are sourced from bundled defs."]
      [components/stat-card "Runtime drafts"
       (count (:drafts runtime-authoring))
       "Drafts live in the overlay workspace until publish."]]
     [:div {:className "defs-authoring-support"}
      [:div {:className "defs-authoring-support-block"}
       [:p {:className "stat-label"} "Supported overrides"]
       (if (seq supported-keys)
         [:div {:className "defs-authoring-badge-row"}
          (for [override-key supported-keys]
            ^{:key (str override-key)}
            [components/badge "info" (str override-key)])]
         [:p {:className "defs-authoring-copy"} "Loading authoring contract…"])]
      [:div {:className "defs-authoring-support-block"}
       [:p {:className "stat-label"} "Flow"]
       [:p {:className "defs-authoring-copy"}
        "Validate is optional. Create writes a draft. Publish copies that draft into defs and refreshes the published runtime list on this page."]]]
     [result-strip authoring runtime-authoring]]))
