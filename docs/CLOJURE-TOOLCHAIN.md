# Clojure Toolchain Notes

Last updated: 2026-03-31

## Current machine state

- OS: Debian 13 (`trixie`) x86_64
- JDK: Temurin 21.0.10 LTS via `sdkman`
- Clojure CLI: 1.12.4.1618
- Installed standalone CLIs:
  - `bb` -> babashka v1.12.217
  - `clj-kondo` -> v2026.01.19
  - `clojure-lsp` -> 2026.02.20-16.08.58
  - `bbin` -> 0.2.5
  - `neil` -> 0.3.69

## Current configuration files

- Global Clojure aliases: [~/.clojure/deps.edn](/home/mikewong/.clojure/deps.edn)
- Installed Clojure tools:
  - [~/.clojure/tools/cljfmt.edn](/home/mikewong/.clojure/tools/cljfmt.edn)
  - [~/.clojure/tools/jet.edn](/home/mikewong/.clojure/tools/jet.edn)
  - [~/.clojure/tools/new.edn](/home/mikewong/.clojure/tools/new.edn)
  - [~/.clojure/tools/tools.edn](/home/mikewong/.clojure/tools/tools.edn)
- Bash command dispatcher: [~/.bashrc](/home/mikewong/.bashrc)

## What is configured right now

### Global aliases in `~/.clojure/deps.edn`

- `:rebel`
  - terminal REPL via Rebel Readline
  - runs as `clojure -M:rebel`
- `:nrepl`
  - plain nREPL server
  - runs as `clojure -M:nrepl`
- `:portal`
  - starts a REPL with Portal on the classpath
  - runs as `clojure -M:portal`
- `:clj-new`
  - legacy scaffolding via `clj-new`
  - runs as `clojure -X:clj-new`
- `:outdated`
  - dependency update checker via `antq`
  - runs as `clojure -M:outdated`
- `:build`
  - project build alias via `tools.build`
  - runs as `clojure -T:build`
  - only works meaningfully inside a project that defines a `build` namespace or `build.clj`
- `:kaocha`
  - richer test runner than plain `clojure.test`
  - runs as `clojure -M:kaocha`
  - works best inside a project with tests and optionally `tests.edn`

### Installed Clojure tools in `~/.clojure/tools`

- `new`
  - this is `deps-new`
  - runs as `clojure -Tnew`
- `tools`
  - this is the official `tools.tools` helper
  - runs as `clojure -Ttools`
- `cljfmt`
  - formatter / formatting checker
  - runs as `clojure -Tcljfmt`
- `jet`
  - data transformation tool
  - runs as `clojure -Tjet`

## How Bash alias dispatch works

Interactive Bash is configured to treat unknown commands with special prefixes as Clojure invocations:

- `clj.foo`
  - maps to `clojure -M:foo`
  - only if `:foo` exists in local `./deps.edn` or global `~/.clojure/deps.edn`

- `cljx.foo`
  - maps to `clojure -X:foo`
  - only if `:foo` exists in local `./deps.edn` or global `~/.clojure/deps.edn`

- `cljt.foo`
  - first tries Clojure tool mode: `clojure -Tfoo`
  - if no tool named `foo` is installed, falls back to alias mode: `clojure -T:foo`

This means:

- `cljt.new` uses the installed `deps-new` tool
- `cljt.build` uses the `:build` alias from `deps.edn`

## Commands you can use now

### REPL and editor support

```bash
clj.rebel
clj.nrepl
clj.portal
```

Typical Portal REPL bootstrap after `clj.portal`:

```clojure
(require '[portal.api :as p])
(def p (p/open))
(add-tap #'p/submit)
(tap> {:hello "portal"})
```

### Project initialization

Preferred modern initializer:

```bash
cljt.new app :name myname/myapp
cljt.new lib :name myname/mylib
cljt.new scratch :name play
```

Legacy template ecosystem:

```bash
cljx.clj-new :template app :name myname/myapp
cljx.clj-new :template lib :name myname/mylib
```

### Build and dependency maintenance

```bash
cljt.build
clj.kaocha --help
clj.outdated
```

### Standalone tools

```bash
bb --version
bbin version
neil --version
clj-kondo --version
clojure-lsp --version
```

### Formatter and data tools

```bash
cljt.cljfmt check
cljt.cljfmt fix

printf '[1 2 3]' | cljt.jet exec :to :json
printf '{:a 1}' | cljt.jet exec :from :edn :to :json
```

## Important quoting note for `-T`

Tool mode (`-T`) expects EDN values.

Symbols are fine:

```bash
cljt.new app :name myname/myapp
```

But string values must be passed as EDN strings, for example:

```bash
cljt.new app :name myname/myapp :target-dir "\"/tmp/myapp\""
```

If you do not quote string values as EDN strings, Clojure will report an unreadable arg error.

## Recommended modern Clojure toolchain

For a modern `deps.edn` workflow, the stack I would recommend is:

### Core

- Temurin JDK 21 LTS
- Official Clojure CLI
- `deps.edn`
- `tools.build`

### Daily development

- `clojure-lsp`
- `clj-kondo`
- `babashka`
- `nREPL` or `Rebel Readline`
- `cljfmt`
- `Portal`

### Project initialization

- `deps-new`
  - preferred modern initializer for built-in templates and tool-based workflow
- `clj-new`
  - useful only when you need its older/community template ecosystem

### Maintenance / optional utilities

- `antq`
  - dependency upgrade checking
- `bbin`
  - lightweight installer for Babashka scripts
- `jet`
  - EDN / JSON / Transit transformations
- `neil`
  - project management helper for `deps.edn` projects
- `kaocha`
  - richer test runner when you outgrow the default test setup

## Recommended tools now installed

These recommended optional tools are now present in the environment:

- `cljfmt`
- `portal` via global alias
- `kaocha` via global alias
- `neil`
- `bbin`
- `jet`

## Practical recommendations

- For new projects, prefer `cljt.new` over `cljx.clj-new`.
- Keep `clj-new` only for compatibility with older tutorials and existing template workflows.
- Use `clj-kondo` + `clojure-lsp` as the default lint/LSP pair.
- Add `cljt.cljfmt check` to CI and use `cljt.cljfmt fix` locally.
- Use `clj.portal` when you want a data inspector in a REPL session.
- Use `clj.kaocha` when plain `clojure.test` output stops being enough.
- Use `bb` for scripts and small automation instead of spinning up the JVM for everything.
- Use `bbin` to install Babashka-based utilities, including small one-off scripts.
- Use `jet` for shell-side data conversion instead of ad hoc reader/writer snippets.
- Use `neil` when you want project management commands around `deps.edn`, not just initialization.
- Keep global `~/.clojure/deps.edn` small and tool-focused.
- Put app code, test code, and project-specific aliases in each project's own `deps.edn`.

## Source links

- Clojure CLI / tools reference:
  - https://clojure.org/reference/clojure_cli
  - https://clojure.org/guides/deps_and_cli
- Official install guide:
  - https://clojure.org/guides/install_clojure
- `deps-new`:
  - https://github.com/seancorfield/deps-new
- `clj-new`:
  - https://github.com/seancorfield/clj-new
- `tools.build`:
  - https://clojure.org/guides/tools_build
- `babashka`:
  - https://book.babashka.org/
  - https://github.com/babashka/babashka
- `clj-kondo`:
  - https://github.com/clj-kondo/clj-kondo
- `clojure-lsp`:
  - https://clojure-lsp.io/
  - https://github.com/clojure-lsp/clojure-lsp
- `neil`:
  - https://github.com/babashka/neil
- `bbin`:
  - https://github.com/babashka/bbin
- `jet`:
  - https://github.com/borkdude/jet
- `kaocha`:
  - https://github.com/lambdaisland/kaocha
