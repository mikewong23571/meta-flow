# Bundled Definitions Notes

## Scope

This directory holds the bundled versioned workflow definitions that shape task behavior without changing control-plane code.

This directory is intentionally flat; each file owns one definition family:

- `task-types.edn` for task-type assembly
- `task-fsms.edn` and `run-fsms.edn` for state-machine definitions
- `runtime-profiles.edn` for runtime adapter and worker configuration
- `artifact-contracts.edn` and `validators.edn` for validation inputs
- `resource-policies.edn` for dispatch, attempt, lease, and timeout policy
- `workflow.edn` for workflow-level defaults

## Change Rules

- Prefer reusing existing refs when semantics are unchanged; add a new version when meaning or behavior changes materially.
- Keep refs coherent. A task type should pin task FSM, run FSM, runtime profile, artifact contract, validator, and resource policy that actually agree with each other.
- Do not rely on prompt text or ambient local state to carry structured workflow semantics that belong in definitions.
- If a new field changes runtime, scheduler, or validation behavior, update the owning code in `src/meta_flow/defs/`, related consumers, and tests in the same change.
- This repository does not treat backward compatibility of persisted task-definition semantics as a default requirement; prefer the simplest current definition model unless compatibility is explicitly requested.

## Validation

- Run `bb defs:validate` after any definition change.
- If task-type, runtime-profile, or validator wiring changes, check the related tests under `test/meta_flow/defs*`, `test/meta_flow/ui/`, `test/meta_flow/service/validation_test.clj`, and any affected `scheduler/` or `runtime/` suites.
