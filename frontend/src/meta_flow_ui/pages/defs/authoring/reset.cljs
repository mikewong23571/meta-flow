(ns meta-flow-ui.pages.defs.authoring.reset
  (:require [meta-flow-ui.pages.defs.authoring.read :as authoring-read]
            [meta-flow-ui.state :as state]))

(defn- reset-kind-feedback!
  [definition-kind]
  (let [base-path (authoring-read/authoring-path definition-kind)]
    (swap! state/ui-state
           (fn [ui-state]
             (authoring-read/assoc-in-many ui-state
                                           [[(conj base-path :validation-result) nil]
                                            [(conj base-path :validation-loading?) false]
                                            [(conj base-path :validation-error) nil]
                                            [(conj base-path :create-result) nil]
                                            [(conj base-path :submitting?) false]
                                            [(conj base-path :submit-error) nil]])))))

(defn reset-runtime-profile-authoring-feedback!
  []
  (reset-kind-feedback! :runtime-profile))

(defn reset-task-type-authoring-feedback!
  []
  (reset-kind-feedback! :task-type))

(defn- reset-kind-validation-preview!
  [definition-kind]
  (let [base-path (authoring-read/authoring-path definition-kind)]
    (swap! state/ui-state
           (fn [ui-state]
             (authoring-read/assoc-in-many ui-state
                                           [[(conj base-path :validation-result) nil]
                                            [(conj base-path :validation-loading?) false]
                                            [(conj base-path :validation-error) nil]])))))

(defn reset-runtime-profile-validation-preview!
  []
  (reset-kind-validation-preview! :runtime-profile))

(defn reset-task-type-validation-preview!
  []
  (reset-kind-validation-preview! :task-type))
