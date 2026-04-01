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

(defn -main
  [& args]
  (try
    (cli/dispatch-command! (vec args))
    (catch Throwable throwable
      (print-error! throwable)
      (System/exit 1))))
