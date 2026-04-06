# Defs Authoring Browser UI Walkthrough

Current command-boundary note (2026-04-07): run browser UI commands from `ui/` with `bb`, and run root `bb ui:api` only for the backend JSON API process.

This document records the Milestone 5 browser proof for `docs/architecture/defs-authoring-browser-ui-execplan.md`. It is intentionally concrete: it shows the exact commands, browser-visible outcomes, and runtime evidence needed to prove that a definition authored from the browser can be used immediately by the Tasks browser UI and then executed by the scheduler with the authored runtime profile.

## Purpose

After the browser authoring milestones landed, the remaining question was not whether the forms rendered, but whether the full user path worked end to end. This walkthrough proves that a user can start the local UI, create and publish a runtime profile from `#/defs/runtimes`, create and publish a task type from `#/defs`, create a task from `#/tasks`, and then run that task to completion while preserving the authored runtime-profile ref.

## Preconditions

Run every command from `/Users/mike/projs/main/meta-flow`.

This repository's default definition overlay lives under the repo-local `defs/` directory. That means browser publish actions will create `defs/runtime-profiles.edn`, `defs/task-types.edn`, and draft files under `defs/drafts/` if they do not already exist. If you are only proving behavior and do not want to keep those authored overlay files, delete `defs/` after the walkthrough.

The proof below used these authored ids:

- `runtime-profile/browser-m5-ui-proof`
- `task-type/browser-m5-ui-proof`
- work key `repo-review/browser-m5-ui-proof`

If those ids already exist in your local overlay, repeat the walkthrough with a fresh suffix.

## Startup

Initialize the local DB and runtime directories once:

    bb init

Observed output:

    Initialized database at var/meta-flow.sqlite3
    Loaded workflow definitions from resources/meta_flow/defs with defs/ overlay support
    Ensured runtime directories: var/artifacts, var/runs, var/codex-home

Compile the browser app:

    cd ui
    bb compile:check

Observed output:

    [:app] Build completed. (174 files, 0 compiled, 0 warnings, 1.05s)

Start the API server:

    bb ui:api

Observed output:

    Meta-Flow UI API listening on http://localhost:8788
    Scheduler overview: http://localhost:8788/api/scheduler/overview
    Using SQLite DB var/meta-flow.sqlite3

In a second shell, start the browser preview:

    cd ui
    bb watch

Observed output:

    shadow-cljs - HTTP server available at http://localhost:8787
    shadow-cljs - watching build :app
    [:app] Build completed. (242 files, 0 compiled, 0 warnings, 1.21s)

Optional contract check:

    curl -s http://localhost:8788/api/defs/contract | jq '."task-type"."supported-override-keys"'

Observed output:

    [
      "task-type/description",
      "task-type/runtime-profile-ref",
      "task-type/input-schema",
      "task-type/work-key-expr"
    ]

## Browser Proof

Open `http://localhost:8787/#/defs/runtimes`.

1. Click `New Runtime Profile`.
2. Select template `Mock worker [runtime-profile/mock-worker v1]`.
3. Enter `runtime-profile/browser-m5-ui-proof` as `New id`.
4. Enter `Browser M5 UI proof worker` as `New name`.
5. Click `Create Draft`.
6. Click `Publish` in the runtime draft card.

Observed browser-visible result:

    Draft Created
    runtime-profile/browser-m5-ui-proof v1

    Published
    runtime-profile/browser-m5-ui-proof v1

    Published runtime profiles: 4

The authored runtime profile then appears in the published runtime table as:

    Browser M5 UI proof worker
    runtime-profile/browser-m5-ui-proof v1

Open `http://localhost:8787/#/defs`.

1. Click `New Task Type`.
2. Select template `Default generic task [task-type/default v1]`.
3. Enter `task-type/browser-m5-ui-proof` as `New id`.
4. Enter `Browser M5 UI proof task` as `New name`.
5. Enter `Browser-authored mock-backed task proof` as `Description override`.
6. Select runtime `Browser M5 UI proof worker [runtime-profile/browser-m5-ui-proof v1]`.
7. Click `Create Draft`.
8. Click `Publish` in the task-type draft card.

Observed browser-visible result:

    Draft Created
    task-type/browser-m5-ui-proof v1

    Published
    task-type/browser-m5-ui-proof v1

    Published task types: 4

The authored task type then appears in the published task-type table as:

    Browser M5 UI proof task
    task-type/browser-m5-ui-proof v1

Open `http://localhost:8787/#/tasks`.

1. Click `New Task`.
2. In the `Task Type` selector, choose `Browser M5 UI proof task`.
3. Enter `repo-review/browser-m5-ui-proof` as `Work Key`.
4. Click `Create`.

Observed browser-visible result:

    task-0a45aa47-f099-45fc-918a-b51f2d6c428e
    repo-review/browser-m5-ui-proof
    queued

Click the new task row to open detail. The detail panel shows:

    Task type: task-type/browser-m5-ui-proof v1
    Runtime profile: runtime-profile/browser-m5-ui-proof v1

This proves the browser-authored task type is visible to the Tasks dialog and that the created task is pinned to the browser-authored runtime profile before any scheduler step runs.

## Scheduler Proof

Advance the scheduler once:

    clojure -M:native-access -m meta-flow.main scheduler once

Observed output:

    Scheduler step completed at 2026-04-06T14:05:28.461270Z
    Created runs: 1
    Runnable tasks before step: 1

Run the scheduler until the authored task completes:

    bb scheduler:run --task-id task-0a45aa47-f099-45fc-918a-b51f2d6c428e

Observed output:

    Running scheduler until task task-0a45aa47-f099-45fc-918a-b51f2d6c428e completes...
      step 0  task=leased  run=dispatched
      step 1  task=running  run=running
      step 2  task=running  run=running
      step 3  task=running  run=exited
      step 4  task=completed  run=finalized
    Done in 4 steps
    Task task-0a45aa47-f099-45fc-918a-b51f2d6c428e -> :task.state/completed
    Run  run-457c749b-de4e-4f5d-b45c-6dda0bf379e5 -> :run.state/finalized
    Artifact root: var/artifacts/task-0a45aa47-f099-45fc-918a-b51f2d6c428e/run-457c749b-de4e-4f5d-b45c-6dda0bf379e5/

After a browser refresh or poll cycle, `#/tasks` shows the same task in `completed` state while still displaying `runtime-profile/browser-m5-ui-proof v1` in the detail panel.

## Runtime File Proof

The run workspace confirms that the finalized run used the authored runtime-profile ref:

    sed -n '1,20p' var/runs/run-457c749b-de4e-4f5d-b45c-6dda0bf379e5/runtime-profile.edn

Observed output:

    #:definition{:id :runtime-profile/browser-m5-ui-proof, :version 1}

This file-level proof matters because it shows the runtime adapter executed with the authored definition ref, not merely that the browser showed a pinned value earlier in the flow.

## Artifacts

Screenshots captured from the live browser run:

- [defs-published.png](assets/defs-authoring-browser-ui/defs-published.png)
- [tasks-completed.png](assets/defs-authoring-browser-ui/tasks-completed.png)

## Cleanup

If you only ran this walkthrough to prove behavior and the repo did not already contain a `defs/` overlay directory, remove the generated overlay afterwards:

    rm -rf defs

This cleanup is safe only when `defs/` was created by the walkthrough and you do not intend to keep the authored overlay definitions.
