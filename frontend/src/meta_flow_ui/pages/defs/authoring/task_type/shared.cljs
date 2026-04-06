(ns meta-flow-ui.pages.defs.authoring.task-type.shared
  (:require [clojure.string :as str]
            [cljs.pprint :refer [pprint]]
            [meta-flow-ui.components :as components]
            [meta-flow-ui.pages.defs.state :as defs-state]))

(def publish-order-rule-id "publish-order/task-type-runtime-profile")

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

(defn format-runtime-value
  [runtime-item]
  (str (:runtime-profile/id runtime-item) "|" (:runtime-profile/version runtime-item)))

(defn parse-runtime-value
  [value]
  (let [[definition-id definition-version] (str/split value #"\|" 2)]
    {:definition/id definition-id
     :definition/version (js/parseInt definition-version 10)}))

(defn blank-dialog-state
  [template]
  {:open? false
   :selected-template-value (when template
                              (format-template-value template))
   :new-id ""
   :new-name ""
   :new-version ""
   :description ""
   :selected-runtime-profile-value ""})

(defn definition-ref-label
  [definition-ref]
  (when definition-ref
    (str (:definition/id definition-ref) " v" (:definition/version definition-ref))))

(defn task-definition-ref
  [definition]
  {:definition/id (:task-type/id definition)
   :definition/version (:task-type/version definition)})

(defn current-draft-label
  [definition]
  (definition-ref-label (task-definition-ref definition)))

(defn runtime-option-label
  [runtime-item]
  (str (:runtime-profile/name runtime-item)
       "  ["
       (:runtime-profile/id runtime-item)
       " v"
       (:runtime-profile/version runtime-item)
       "]"))

(defn selected-runtime-ref
  [dialog-state]
  (when-let [selected-value (some-> (:selected-runtime-profile-value dialog-state) str/trim not-empty)]
    (parse-runtime-value selected-value)))

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
      (assoc :new-id "Enter a new task-type id.")

      (and (not (str/blank? new-id))
           (not (str/starts-with? new-id "task-type/")))
      (assoc :new-id "Use the task-type/... keyword namespace.")

      (str/blank? new-name)
      (assoc :new-name "Enter a display name for the new task type.")

      (and (not (str/blank? version-text))
           (nil? (parse-version version-text)))
      (assoc :new-version "Use a positive integer version, or leave it blank for v1."))))

(defn build-request
  [dialog-state template]
  (let [version (parse-version (:new-version dialog-state))
        description (some-> (:description dialog-state) str/trim not-empty)
        runtime-profile-ref (selected-runtime-ref dialog-state)
        overrides (cond-> {}
                    description
                    (assoc :task-type/description description)

                    runtime-profile-ref
                    (assoc :task-type/runtime-profile-ref runtime-profile-ref))]
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

(defn publish-order-message
  [authoring]
  (or (some (fn [rule]
              (when (= publish-order-rule-id (:rule/id rule))
                (:rule/message rule)))
            (:publish-order-rules (:contract authoring)))
      "Only published runtime profiles may be selected for task-type runtime overrides in the browser UI."))

(defn- status-card
  [tone title body]
  [:article {:className (str "panel defs-authoring-status defs-authoring-status-" tone)}
   [:div {:className "defs-authoring-status-head"}
    [components/badge tone title]]
   [:div {:className "defs-authoring-status-body"}
    body]])

(defn- result-strip
  [authoring task-authoring runtime-error]
  [:div {:className "defs-authoring-status-grid"}
   (when-let [error (:templates-error task-authoring)]
     [status-card "danger"
      "Template Load Error"
      [:p {:className "defs-authoring-copy"} error]])
   (when runtime-error
     [status-card "danger"
      "Runtime Load Error"
      [:p {:className "defs-authoring-copy"} runtime-error]])
   (when-let [result (:create-result task-authoring)]
     [status-card "success"
      "Draft Created"
      [:<>
       [:p {:className "defs-authoring-copy"}
        "Draft saved to the overlay workspace and loaded into the inspection panel."]
       [:p {:className "defs-authoring-meta"}
        (current-draft-label (:definition result))]]])
   (when-let [result (:publish-result task-authoring)]
     [status-card "success"
      "Published"
      [:<>
       [:p {:className "defs-authoring-copy"}
        "Published definitions were reloaded into the running repository."]
       [:p {:className "defs-authoring-meta"}
        (current-draft-label (:definition result))]]])
   (when-let [error (:publish-error task-authoring)]
     [status-card "danger"
      "Publish Error"
      [:p {:className "defs-authoring-copy"} error]])
   (when-let [error (:reload-error authoring)]
     [status-card "danger"
      "Reload Error"
      [:p {:className "defs-authoring-copy"} error]])])

(defn summary-panel
  [authoring task-authoring runtime-items runtime-error task-items]
  (let [supported-keys (get-in authoring [:contract :task-type :supported-override-keys])]
    [:section {:className "panel defs-authoring-panel"}
     [:div {:className "defs-authoring-panel-head"}
      [:div
       [:h2 {:className "component-title"} "Task Type Authoring"]
       [:p {:className "defs-authoring-copy"}
        "Clone a published task-type template, optionally override the description or runtime profile, and publish the draft into the live defs catalog."]]
      [:div {:className "defs-authoring-panel-actions"}
       [:button {:className "button button-secondary"
                 :on-click defs-state/reload-definitions!
                 :disabled (:reloading? authoring)}
        (if (:reloading? authoring) "Reloading…" "Reload Definitions")]]]
     [:div {:className "defs-authoring-summary-grid"}
      [components/stat-card "Published task types"
       (count task-items)
       "Reload is automatic after publish."]
      [components/stat-card "Task-type templates"
       (count (:templates task-authoring))
       "Templates are sourced from bundled defs."]
      [components/stat-card "Published runtimes"
       (count runtime-items)
       (if runtime-error
         "Runtime list failed to load. See the error below before using runtime overrides."
         "Only published runtimes can be selected in the dialog.")]]
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
       [:p {:className "stat-label"} "Publish order"]
       [:p {:className "defs-authoring-copy"}
        (publish-order-message authoring)]]
      [:div {:className "defs-authoring-support-block"}
       [:p {:className "stat-label"} "Flow"]
       [:p {:className "defs-authoring-copy"}
        "Validate is optional. Create writes a draft. Publish copies that draft into defs and refreshes the published task-type list on this page."]]
      [:div {:className "defs-authoring-support-block"}
       [:p {:className "stat-label"} "First release fields"]
       [:p {:className "defs-authoring-copy"}
        "This browser pass exposes template, id, name, version, description, and published runtime selection. Schema and work-key overrides remain backend-supported but editor-free for now."]]]
     [result-strip authoring task-authoring runtime-error]]))
