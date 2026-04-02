(ns meta-flow.defs.loader
  (:require [meta-flow.defs.repository :as defs.repository]))

(defn definitions-summary
  [{:keys [task-types task-fsms run-fsms runtime-profiles artifact-contracts validators resource-policies]}]
  {:task-types (count task-types)
   :task-fsms (count task-fsms)
   :run-fsms (count run-fsms)
   :runtime-profiles (count runtime-profiles)
   :artifact-contracts (count artifact-contracts)
   :validators (count validators)
   :resource-policies (count resource-policies)})

(defn filesystem-definition-repository
  ([] (defs.repository/filesystem-definition-repository))
  ([resource-base]
   (defs.repository/filesystem-definition-repository resource-base)))
