# Developer Guide

## Prerequisites

- JDK 21 (Temurin LTS recommended, via sdkman)
- Clojure CLI 1.12+
- Babashka (`bb`)
- Node.js + npm

All other tools (kaocha, clj-kondo, cljfmt, nREPL) are pulled from `deps.edn` automatically — no global installs required.

## Task runner

All development commands go through `bb`. Run `bb tasks` to see the full list.

### Command reference

| Command | What it does |
|---------|-------------|
| `bb repl` | Start nREPL server on port 7888 (JVM stays warm) |
| `bb test` | Run full test suite once (kaocha); supports `--focus my.ns` |
| `bb test:watch` | Rerun tests automatically on file change |
| `bb lint` | Static analysis via clj-kondo, plus `src/` and `test/` governance: file length over 240 warns and over 300 fails; directory width over 7 direct source files warns and over 12 fails |
| `bb coverage` | Run Kaocha + Cloverage and enforce coverage governance: overall line coverage below 88% warns and below 85% fails |
| `bb fmt` | Reformat all source files in place (cljfmt fix) |
| `bb fmt:check` | Check formatting without modifying files |
| `bb check` | Run the unified governance gate with concise output: format, static analysis, structure, frontend, executable correctness, and coverage |
| `bb check:verbose` | Run the step-by-step human-debugging gate: fmt:check → lint → ui:governance → test → coverage |
| `bb init` | Initialize SQLite database and runtime directories |
| `bb defs:validate` | Validate bundled EDN workflow definitions |
| `bb ui:install` | Install frontend npm dependencies |
| `bb ui:watch` | Start the frontend preview at `http://localhost:8787` |
| `bb ui:release` | Build the frontend preview release bundle |

### Daily development loop

Start a persistent nREPL server so the JVM stays warm:

    bb repl

Port 7888. Connect with your editor (Calva, CIDER, etc.) and run tests from
there. The inner loop is REPL-eval → `(clojure.test/run-tests)`, not a
repeated JVM cold-start.

When you want automatic reruns on file change instead:

    bb test:watch

For the browser preview:

    bb ui:install
    bb ui:watch

Then open `http://localhost:8787`.

### Full pipeline

    bb check       # unified governance gate (same as pre-commit)

Run this before opening a PR to confirm everything is green.

## Pre-commit hook

`.git/hooks/pre-commit` runs `bb check` automatically on every `git commit`.
If it fails, the commit is aborted and the output tells you what to fix.

To bypass in an emergency (not recommended):

    git commit --no-verify

The hook is not versioned — if you clone fresh, re-create it:

    cp .git/hooks/pre-commit.sample .git/hooks/pre-commit  # not needed
    # just write the one-liner manually:
    echo '#!/usr/bin/env bash\nset -euo pipefail\nexec bb check' > .git/hooks/pre-commit
    chmod +x .git/hooks/pre-commit

## Project initialization

Create the SQLite database and runtime directories before first use:

    bb ui:install
    bb init

Validate that the bundled workflow definitions are well-formed:

    bb defs:validate

Initialize the project-level Codex runtime home when working on the real Codex path:

    clojure -M -m meta-flow.main runtime init-codex-home

For the full operational CLI, see:

    docs/cli-reference.md

For Codex runtime behavior and smoke testing, see:

    docs/codex-runtime.md

## Key source locations

    src/meta_flow/        application source
    test/meta_flow/       test suite
    resources/meta_flow/  EDN definitions (task types, FSMs, policies, etc.)
    docs/                 current architecture and operations documents
    docs/archive/         historical plans and superseded material
    deps.edn              dependency and alias declarations
    bb.edn                task runner
    tests.edn             kaocha test runner configuration
