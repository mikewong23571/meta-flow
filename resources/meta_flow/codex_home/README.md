# Meta Flow CODEX_HOME

This directory is an isolated project-level `CODEX_HOME` for `meta-flow` Codex runs.

- It is safe to recreate with `clojure -M -m meta-flow.main runtime init-codex-home`.
- Deleting it does not change SQLite control-plane state.
- Keep machine-specific or long-lived secrets out of this directory unless you intend to manage them here.
