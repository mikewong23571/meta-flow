# Developer Guide

## Prerequisites

- JDK 21 (Temurin LTS recommended, via sdkman)
- Clojure CLI 1.12+
- Babashka (`bb`)

All other tools (kaocha, clj-kondo, cljfmt, nREPL) are pulled from `deps.edn` automatically — no global installs required.

## Task runner

All development commands go through `bb`. Run `bb tasks` to see the full list.

### Daily development loop

Start a persistent nREPL server so the JVM stays warm:

    bb repl

Port 7888. Connect with your editor (Calva, CIDER, etc.) and run tests from
there. The inner loop is REPL-eval → `(clojure.test/run-tests)`, not a
repeated JVM cold-start.

When you want automatic reruns on file change instead:

    bb test:watch

### Running checks manually

    bb test        # run the full test suite once
    bb lint        # static analysis (clj-kondo)
    bb fmt:check   # verify formatting without modifying files
    bb fmt         # reformat all source files in place

### Full pipeline

    bb check       # fmt:check → lint → test (same as pre-commit)

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

    bb init

Validate that the bundled workflow definitions are well-formed:

    bb defs:validate

## Key source locations

    src/meta_flow/        application source
    test/meta_flow/       test suite
    resources/meta_flow/  EDN definitions (task types, FSMs, policies, etc.)
    docs/                 architecture and ExecPlan documents
    deps.edn              dependency and alias declarations
    bb.edn                task runner
    tests.edn             kaocha test runner configuration
