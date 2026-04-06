(ns meta-flow-ui.pages.defs.authoring.bootstrap
  (:require [meta-flow-ui.pages.defs.authoring.read :as authoring-read]))

(defn load-authoring-bootstrap!
  []
  (authoring-read/load-authoring-contract!)
  (authoring-read/load-runtime-profile-templates!)
  (authoring-read/load-task-type-templates!)
  (authoring-read/load-runtime-profile-drafts!)
  (authoring-read/load-task-type-drafts!))
