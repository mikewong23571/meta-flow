(ns meta-flow.ui.http.defs
  (:require [meta-flow.ui.http.defs.handlers :as defs.handlers]
            [meta-flow.ui.http.defs.catalog :as defs.catalog]))

(defn routes
  [defs-repo]
  (into (defs.handlers/routes defs-repo)
        (defs.catalog/routes defs-repo)))
