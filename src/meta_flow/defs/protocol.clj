(ns meta-flow.defs.protocol)

(defprotocol DefinitionRepository
  (load-workflow-defs [repo])
  (find-task-type-def [repo task-type-id version])
  (find-run-fsm-def [repo run-fsm-id version])
  (find-task-fsm-def [repo task-fsm-id version])
  (find-artifact-contract [repo contract-id version])
  (find-validator-def [repo validator-id version])
  (find-runtime-profile [repo runtime-profile-id version])
  (find-resource-policy [repo resource-policy-id version]))
