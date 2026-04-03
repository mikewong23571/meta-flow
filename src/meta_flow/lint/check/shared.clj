(ns meta-flow.lint.check.shared
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def ansi-pattern
  #"\u001b\[[0-9;]*m")

(defn strip-ansi
  [text]
  (str/replace (or text "") ansi-pattern ""))

(defn non-blank-lines
  [text]
  (->> (str/split-lines (strip-ansi text))
       (map str/trim)
       (remove str/blank?)
       vec))

(defn first-matching-line
  [text pattern]
  (some #(when (re-find pattern %) %) (non-blank-lines text)))

(defn run-command!
  [command]
  (let [{:keys [exit out err]} (apply shell/sh command)]
    {:command command
     :exit exit
     :out out
     :err err
     :combined (str out err)}))
