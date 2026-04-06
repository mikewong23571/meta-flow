(ns meta-flow-ui.pages.defs.presenter
  (:require [clojure.string :as str]))

(defn seg
  [value]
  (some-> value str (str/split #"/") last))

(defn fmt-seconds
  [n]
  (when n
    (let [n (int n)]
      (cond
        (>= n 3600) (str (quot n 3600) "h")
        (>= n 60) (str (quot n 60) "m")
        :else (str n "s")))))

(defn ref-label
  [ref-map]
  (when ref-map
    (str (seg (:definition/id ref-map))
         " v"
         (:definition/version ref-map))))

(defn work-key-label
  [work-key]
  (case (:work-key/type work-key)
    "work-key.type/direct" "direct"
    "work-key.type/tuple" "tuple"
    "n/a"))

(defn input-preview
  [item]
  (let [labels (:task-type/input-labels item)
        preview (take 2 labels)]
    (cond
      (empty? labels) "No input"
      (> (count labels) 2) (str (str/join ", " preview) " +" (- (count labels) 2))
      :else (str/join ", " preview))))
