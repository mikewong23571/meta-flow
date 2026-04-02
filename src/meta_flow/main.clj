(ns meta-flow.main
  (:gen-class)
  (:require [clojure.pprint :as pprint]
            [meta-flow.cli :as cli]))

(defn- print-error!
  [throwable]
  (binding [*out* *err*]
    (println (str "Error: " (.getMessage throwable)))
    (when-let [data (ex-data throwable)]
      (pprint/pprint data))))

(defn- run-command!
  [args]
  (try
    (cli/dispatch-command! (vec args))
    0
    (catch Throwable throwable
      (print-error! throwable)
      1)))

(defn exit!
  [status]
  (System/exit status))

(defn -main
  [& args]
  (let [status (run-command! args)]
    (when (pos? status)
      (exit! status))))
