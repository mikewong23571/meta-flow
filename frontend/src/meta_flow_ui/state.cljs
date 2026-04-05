(ns meta-flow-ui.state
  (:require [reagent.core :as r]))

(defonce react-root (atom nil))

(defonce ui-state
  (r/atom {:query "preview component states"
           :dialog-open false
           :auto-refresh true
           :show-guides true
           :active-tab "tokens"}))
