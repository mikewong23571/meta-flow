# Meta-Flow UI Design System

This document is the authoritative source for UI design decisions. Any new page or component
must conform to these rules. Reference this before implementing any UI work.

## Page Anatomy

Every page is divided into exactly these blocks, in order:

```
┌─────────────────────────────────────────────────────────┐
│  TOPBAR                                                 │
│  Page identity (eyebrow + h1 + subtitle)                │
│  ← left                    nav tabs + icon actions →   │
├─────────────────────────────────────────────────────────┤
│  FILTER BAR  (optional — only on list pages)            │
│  Filter controls only. No status, no counts.            │
├─────────────────────────────────────────────────────────┤
│  CONTENT PANELS                                         │
│  Panel header: section title (left) + table meta (right)│
│  Panel body:  table / cards / data                      │
└─────────────────────────────────────────────────────────┘
                                    ┌──────────────────┐
                                    │  DETAIL DRAWER   │
                                    │  fixed, overlays │
                                    │  content on open │
                                    └──────────────────┘
```

### Topbar
- Left: `eyebrow` tag + `h1` (display font) + `subtitle` (secondary text)
- Right: `nav-tabs` + primary icon action button
- Separated from content by a `border-bottom`

### Filter bar
- Contains **only** filter/search controls (selects, inputs)
- No status indicators, no counts, no metadata
- Grid layout: controls are equal-width columns

### Content panel header
- Left: section title (`component-title`) + optional meta note (`scheduler-meta-note`)
- Right: table-level status as **plain text** — count + live indicator dot + poll interval
- This is the only place to show "how many rows" and "how fresh is the data"

### Detail drawer
- `position: fixed`, right edge, full height
- Slides in over content — never compresses the main layout
- Closed via ✕ icon button

---

## Information Hierarchy

Assign each piece of information to exactly one weight class:

| Weight | Use for | Visual treatment |
|--------|---------|-----------------|
| **Primary** | Page title, key metrics | `font-display`, large size |
| **Secondary** | Section titles, field values | `font-sans` medium weight |
| **Meta** | Counts, timestamps, state labels, poll intervals | `font-mono` small, muted color |
| **Status** | State transitions (running/failed/complete) | Colored badge (monospace, no background on contextual text) |

Rule: **never elevate meta to primary**. Visible count is meta. Polling interval is meta.
Do not use panels, badges, or large type for these.

---

## Buttons & Actions

**Use icons, not text labels.** Users of an ops dashboard are technical; they read icons.
Text labels are noise.

- **Icon-only actions**: Use `button-icon` class (square, `2.5rem`). Always add `title` attribute for tooltip/a11y.
- **Icon + label**: Reserve for primary CTAs in hero sections where first-time clarity matters.
- **Text-only**: Do not use for actions. Acceptable only for nav tab labels.

### Standard icon mapping

| Action | Icon | Notes |
|--------|------|-------|
| Refresh / reload | ↻ (`icons/refresh`) | Primary action in topbar |
| Close / dismiss | ✕ (`icons/close`) | Detail drawer, dialogs |
| Navigate forward | → (`icons/arrow-right`) | Hero CTAs |
| Home / back | ⌂ (`icons/home`) | Return to entry |

Add new icons to `frontend/src/meta_flow_ui/icons.cljs` as inline SVG components.
Do not use icon fonts or external icon CDNs.

---

## Color Semantics

| Token | When to use |
|-------|-------------|
| `--color-text-accent` (cyan) | Interactive links, active nav, live indicators |
| `--color-status-success-fg` (emerald) | Completed, healthy, fresh |
| `--color-status-warning-fg` (amber) | Degraded, stale, retryable |
| `--color-status-danger-fg` (red) | Failed, timeout, error |
| `--color-status-info-fg` (violet) | In-progress, loading, awaiting |
| `--color-text-muted` | Meta text, labels, secondary annotations |

---

## Information Redundancy

Every piece of information must appear **exactly once**. If the same fact is conveyed
by two elements, remove one.

Common violations to avoid:
- Page title repeated in eyebrow (eyebrow adds context h1 doesn't give; if it can't, remove it)
- Section subtitle that describes what the user already sees
- Card description that restates the card title
- Action labels prefixed with "Go to" / "Open" when an icon carries that meaning
- Status labels (like "Selection") before a panel whose purpose is already clear from context

When in doubt: remove the element. If the page still communicates, it was redundant.

---

## Writing in the UI

**No explanatory prose in the interface.** The UI speaks through structure, labels, and icons.
If something needs a sentence to explain what it is, redesign it — don't annotate it.

Examples of text that must not appear in the UI:
- "Counts are authoritative; ids are sampled."
- "Type support comes from the summary column, not per-type table layouts."
- "This page is a dedicated example surface for UI exploration."

These are implementation notes. They belong in code comments or docs, not on screen.

---

**Do not use status colors decoratively.** Only apply them when the data state justifies it.
Overusing accent colors dilutes their signal value.

---

## Component Vocabulary

| Situation | Use |
|-----------|-----|
| Section container | `.panel` |
| Elevated / selected state | `.panel.panel-strong` |
| Task/run state label | `.badge` with appropriate tone |
| Table-level metadata (count, freshness) | Plain `span` with `.tasks-visible-count` / `.tasks-poll-label` pattern |
| KPI metrics (scheduler overview only) | `.scheduler-kpi-card` inside `.scheduler-summary-strip` |
| Inline status in table rows | Left `border-left` color on `<tr>` — no extra elements |

### List vs Detail responsibility split

A list page and a detail view have distinct jobs — never mix them:

| List | Detail |
|------|--------|
| Identity (id, name) | All fields |
| Primary content (summary, work key) | References and relationships |
| Status (state badge) | Timestamps |
| Recency (updated at) | Run history, attempt count |

If a column requires context to interpret, it belongs in detail.
If a user needs to open detail to act on a row, the list gave them enough.

**When to use `.badge`**: Only for task/run state values that change over time and carry
operational significance (running, failed, complete, etc.). Not for UI meta (counts, intervals).

---

## Typography

```
h1 page title      → font-display, 800, clamp(2rem, 3.5vw, 3.25rem), letter-spacing -0.04em
h2 section title   → font-display, 700, text-lg/text-xl, letter-spacing -0.01em
eyebrow tag        → font-mono, 500, text-xs, uppercase, letter-spacing 0.1em
body copy          → font-sans, 400, text-md, line-height 1.65
meta / labels      → font-mono, 400, text-xs, uppercase, letter-spacing 0.08em
table headers      → font-mono, 400, text-xs, uppercase, letter-spacing 0.08em, muted
code / ids         → font-mono, 400/500, text-xs or text-sm
```

---

## Spacing

Use the spacing scale. Do not use arbitrary values.

```
--space-2xs  0.25rem   tight inline gaps
--space-xs   0.5rem    badge padding, small gaps
--space-sm   0.75rem   element gaps within a group
--space-md   1rem      between groups
--space-lg   1.5rem    panel padding, section gaps
--space-xl   2rem      topbar padding, major sections
--space-2xl  3rem      page top padding
```
