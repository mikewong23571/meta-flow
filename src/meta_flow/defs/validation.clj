(ns meta-flow.defs.validation
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [meta-flow.defs.index :as defs.index]
            [meta-flow.schema :as schema]))

(defn validate-fsm-structure!
  [label fsm states-key initial-key terminal-key transitions-key]
  (let [states (set (get fsm states-key))
        initial-state (get fsm initial-key)
        terminal-states (get fsm terminal-key)
        transitions (get fsm transitions-key)]
    (when-not (contains? states initial-state)
      (throw (ex-info (str label " initial state is not listed in states")
                      {:label label
                       :fsm-id (or (:task-fsm/id fsm) (:run-fsm/id fsm))
                       :initial-state initial-state
                       :states states})))
    (when-not (every? states terminal-states)
      (throw (ex-info (str label " terminal states must be a subset of states")
                      {:label label
                       :fsm-id (or (:task-fsm/id fsm) (:run-fsm/id fsm))
                       :terminal-states terminal-states
                       :states states})))
    (doseq [transition transitions]
      (when-not (contains? states (:transition/from transition))
        (throw (ex-info (str label " transition source state is undefined")
                        {:label label
                         :fsm-id (or (:task-fsm/id fsm) (:run-fsm/id fsm))
                         :transition transition
                         :states states})))
      (when-not (contains? states (:transition/to transition))
        (throw (ex-info (str label " transition target state is undefined")
                        {:label label
                         :fsm-id (or (:task-fsm/id fsm) (:run-fsm/id fsm))
                         :transition transition
                         :states states}))))))

(defn require-resource-path!
  [label resource-path]
  (when-not (io/resource resource-path)
    (throw (ex-info (str "Missing resource path for " label)
                    {:label label
                     :resource-path resource-path}))))

(defn require-filesystem-path!
  [label file-path]
  (when-not (.exists (io/file file-path))
    (throw (ex-info (str "Missing filesystem path for " label)
                    {:label label
                     :file-path file-path}))))

(defn validate-runtime-profile!
  [runtime-profile artifact-contract-index]
  (case (:runtime-profile/adapter-id runtime-profile)
    :runtime.adapter/codex
    (do
      (defs.index/require-ref! (str "runtime profile " (:runtime-profile/id runtime-profile) " artifact contract")
                               (:runtime-profile/artifact-contract-ref runtime-profile)
                               artifact-contract-index)
      (require-resource-path! (str "runtime profile " (:runtime-profile/id runtime-profile) " worker prompt")
                              (:runtime-profile/worker-prompt-path runtime-profile))
      (require-filesystem-path! (str "runtime profile " (:runtime-profile/id runtime-profile) " helper script")
                                (:runtime-profile/helper-script-path runtime-profile))
      (when-not (str/starts-with? (:runtime-profile/codex-home-root runtime-profile) "var/")
        (throw (ex-info "Codex runtime profile must keep CODEX_HOME under var/"
                        {:runtime-profile/id (:runtime-profile/id runtime-profile)
                         :runtime-profile/codex-home-root (:runtime-profile/codex-home-root runtime-profile)}))))
    nil))

(defn validate-definition-schemas!
  [{:keys [workflow task-types task-fsms run-fsms artifact-contracts validators runtime-profiles resource-policies]
    :as defs}]
  (schema/validate! "workflow.edn" schema/workflow-schema workflow)
  (schema/validate! "task-types.edn" [:vector schema/task-type-schema] task-types)
  (schema/validate! "task-fsms.edn" [:vector schema/task-fsm-schema] task-fsms)
  (schema/validate! "run-fsms.edn" [:vector schema/run-fsm-schema] run-fsms)
  (schema/validate! "artifact-contracts.edn" [:vector schema/artifact-contract-schema] artifact-contracts)
  (schema/validate! "validators.edn" [:vector schema/validator-schema] validators)
  (schema/validate! "runtime-profiles.edn" [:vector schema/runtime-profile-schema] runtime-profiles)
  (schema/validate! "resource-policies.edn" [:vector schema/resource-policy-schema] resource-policies)
  (schema/validate! "workflow definitions bundle" schema/workflow-definitions-schema defs)
  defs)

(defn validate-definition-links!
  [{:keys [workflow task-types task-fsms run-fsms artifact-contracts validators runtime-profiles resource-policies]}]
  (let [task-type-index (defs.index/index-definitions! "task-types" task-types :task-type/id :task-type/version)
        task-fsm-index (defs.index/index-definitions! "task-fsms" task-fsms :task-fsm/id :task-fsm/version)
        run-fsm-index (defs.index/index-definitions! "run-fsms" run-fsms :run-fsm/id :run-fsm/version)
        artifact-contract-index (defs.index/index-definitions! "artifact-contracts" artifact-contracts :artifact-contract/id :artifact-contract/version)
        validator-index (defs.index/index-definitions! "validators" validators :validator/id :validator/version)
        runtime-profile-index (defs.index/index-definitions! "runtime-profiles" runtime-profiles :runtime-profile/id :runtime-profile/version)
        resource-policy-index (defs.index/index-definitions! "resource-policies" resource-policies :resource-policy/id :resource-policy/version)]
    (doseq [task-fsm task-fsms]
      (validate-fsm-structure! "Task FSM" task-fsm :task-fsm/states :task-fsm/initial-state :task-fsm/terminal-states :task-fsm/transitions))
    (doseq [run-fsm run-fsms]
      (validate-fsm-structure! "Run FSM" run-fsm :run-fsm/states :run-fsm/initial-state :run-fsm/terminal-states :run-fsm/transitions))
    (defs.index/require-ref! "workflow default task type" (:workflow/default-task-type-ref workflow) task-type-index)
    (defs.index/require-ref! "workflow default task fsm" (:workflow/default-task-fsm-ref workflow) task-fsm-index)
    (defs.index/require-ref! "workflow default run fsm" (:workflow/default-run-fsm-ref workflow) run-fsm-index)
    (defs.index/require-ref! "workflow default runtime profile" (:workflow/default-runtime-profile-ref workflow) runtime-profile-index)
    (defs.index/require-ref! "workflow default artifact contract" (:workflow/default-artifact-contract-ref workflow) artifact-contract-index)
    (defs.index/require-ref! "workflow default validator" (:workflow/default-validator-ref workflow) validator-index)
    (defs.index/require-ref! "workflow default resource policy" (:workflow/default-resource-policy-ref workflow) resource-policy-index)
    (doseq [runtime-profile runtime-profiles]
      (validate-runtime-profile! runtime-profile artifact-contract-index))
    (doseq [task-type task-types]
      (defs.index/require-ref! (str "task type " (:task-type/id task-type) " task fsm")
                               (:task-type/task-fsm-ref task-type)
                               task-fsm-index)
      (defs.index/require-ref! (str "task type " (:task-type/id task-type) " run fsm")
                               (:task-type/run-fsm-ref task-type)
                               run-fsm-index)
      (defs.index/require-ref! (str "task type " (:task-type/id task-type) " runtime profile")
                               (:task-type/runtime-profile-ref task-type)
                               runtime-profile-index)
      (defs.index/require-ref! (str "task type " (:task-type/id task-type) " artifact contract")
                               (:task-type/artifact-contract-ref task-type)
                               artifact-contract-index)
      (defs.index/require-ref! (str "task type " (:task-type/id task-type) " validator")
                               (:task-type/validator-ref task-type)
                               validator-index)
      (defs.index/require-ref! (str "task type " (:task-type/id task-type) " resource policy")
                               (:task-type/resource-policy-ref task-type)
                               resource-policy-index))
    {:task-types task-type-index
     :task-fsms task-fsm-index
     :run-fsms run-fsm-index
     :artifact-contracts artifact-contract-index
     :validators validator-index
     :runtime-profiles runtime-profile-index
     :resource-policies resource-policy-index}))
