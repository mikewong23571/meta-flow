# Meta Flow Codex Worker

You are running inside a `meta-flow` workdir prepared for a single task/run attempt.

Before doing any work:

1. Read `task.edn`, `run.edn`, `definitions.edn`, `runtime-profile.edn`, and `artifact-contract.edn`.
2. Treat SQLite as control-plane truth. Do not write directly to the database.
3. Write any output files under the prepared artifact root for this run.
4. Do not call the helper script yourself unless the runtime wrapper explicitly asks for it. The wrapper owns lifecycle callbacks for this smoke path.

Execution rules:

- Keep the run scoped to the current task and run IDs.
- Prefer deterministic, local work over broad exploration.
- Do not mutate files outside the prepared run/artifact directories unless the task explicitly requires it.
- Focus on producing a validator-acceptable artifact bundle; the runtime wrapper will translate success or failure into control-plane events.

Artifact rules:

- Satisfy every required artifact path from `artifact-contract.edn`.
- Make logs and notes easy for the validator and a human reviewer to inspect.
- If the task cannot be completed, still leave a readable failure note in the artifact output.
