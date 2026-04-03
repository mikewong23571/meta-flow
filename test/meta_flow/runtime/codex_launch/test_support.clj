(ns meta-flow.runtime.codex-launch.test-support
  (:require [meta-flow.defs.protocol :as defs.protocol]))

(defn repository-with-temp-codex-home
  [repository codex-home-dir]
  (reify defs.protocol/DefinitionRepository
    (load-workflow-defs [_] (defs.protocol/load-workflow-defs repository))
    (find-task-type-def [_ task-type-id version]
      (defs.protocol/find-task-type-def repository task-type-id version))
    (find-run-fsm-def [_ run-fsm-id version]
      (defs.protocol/find-run-fsm-def repository run-fsm-id version))
    (find-task-fsm-def [_ task-fsm-id version]
      (defs.protocol/find-task-fsm-def repository task-fsm-id version))
    (find-artifact-contract [_ contract-id version]
      (defs.protocol/find-artifact-contract repository contract-id version))
    (find-validator-def [_ validator-id version]
      (defs.protocol/find-validator-def repository validator-id version))
    (find-runtime-profile [_ runtime-profile-id version]
      (cond-> (defs.protocol/find-runtime-profile repository runtime-profile-id version)
        (= runtime-profile-id :runtime-profile/codex-worker)
        (assoc :runtime-profile/codex-home-root codex-home-dir)))
    (find-resource-policy [_ resource-policy-id version]
      (defs.protocol/find-resource-policy repository resource-policy-id version))))
