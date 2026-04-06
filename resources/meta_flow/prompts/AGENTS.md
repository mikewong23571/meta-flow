# Prompt Assets Notes

## Scope

This directory holds bundled prompt assets consumed by runtime profiles and worker flows.

Today these files act as runtime inputs, not as authoritative workflow state. The structure and truth of task behavior still live in versioned definitions, code, and persisted events.

## Change Rules

- Treat prompts as execution assets. Use them to shape worker instructions, not to encode hidden scheduler policy, validation rules, or persistence semantics.
- Keep prompt filenames and intended consumers stable and obvious. If a runtime profile depends on a prompt, the mapping should remain explicit in `runtime-profiles.edn`.
- Prefer small, reviewable prompt changes over large rewrites that silently alter task semantics.
- If a prompt requires new structured inputs, add those inputs through definitions and runtime materialization code instead of relying on undocumented free text.
- When a prompt change materially changes expected artifacts or worker flow, update the corresponding runtime or validation expectations in code and tests.

## Validation

- After changing a prompt path or its wiring, run `bb defs:validate`.
- For Codex worker prompt changes, check the nearby runtime coverage under `test/meta_flow/runtime/codex/`, `test/meta_flow/runtime/codex_units/`, and `test/meta_flow/runtime/codex_worker_api/` when behavior expectations shift.
- If prompt changes alter UI-visible task/run outcomes, check the affected `test/meta_flow/ui/` or `test/meta_flow/scheduler/` suites too.
