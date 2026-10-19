## Read-only Projects phase 1 (2026-10-19)

Adapted `aed8ba4a`, the projects.list portion of `445da9fe`, `22e70cf9`, and
only #1562 projects.changed invalidation from `dd1f2b05`. The existing private
History card, local lineage pins, automation sections, pagination and search
remain downstream-owned. No typed-RPC architecture or manageable-list redesign.

- Decode real snake_case project metadata and legacy SQLite booleans; optional
  session cwd/git_repo_root/last_active require no database migration.
- Dispatch only `projects.list {}` using the exact connection-bound awaited
  transport, never a queued request or a local ID as a server `profile`.
- Distinguish initial/offline/unsupported/failed from an empty successful list;
  fence all results by owner and generation; retain last-good only for that owner.
- Resolve remote paths lexically for display, retaining POSIX backslashes and
  separating relative/absolute identities. Deepest cwd match wins, primary breaks
  ties, then recorded repo ownership; archived projects fall back to auto-workspaces.
- Keys use project IDs, normalized auto paths and a Home sentinel, never names.
  Filter only loaded Recents after the lineage-pin partition; pins stay visible.
  Search and automation grouping ignore the project filter.
- Source-bound events coalesce for 250 ms per owner; reconnect and manual refresh
  refetch. No discovery, file/URI access, project mutation or chat launch/move.
- A minimal transport change preserves JSON-RPC error codes on awaited errors so
  -32601 is distinguishable from a failed read. No transport queue/retry changes.

`UPSTREAM.json` remains unchanged: this is selective adoption, not a baseline move.

## Low-risk gateway correctness batch (2026-10-19)

Based on fork `630ff207b88873efc8f4a99c45ecbf46c70ccba7`. Selective adaptations:

- `a79c8e570e69cfd7f8f99dfa9d1e34611c1c160e`: scheduled/active cron jobs pause,
  paused/error resume, completed expose neither control. Unknown/null states expose
  neither control (conservative downstream policy). Resume optimistically uses
  `scheduled`; labels are translated in all five catalogs.
- `1fba107ac4ddcff288d2a81b0fee09d2091bdcbe`: queue management verbs receive a local
  unsupported response rather than being queued as prompt text.
- `dd9e23c5acc367a880ce82f664791fcdb4429b74`: connection/resume notices no longer
  become transcript rows; connection status stays in the banner.
- `54c1fa2435a35344d84dd6987c41acd1b94666ba`: full-width inner composer text box
  anchors the RTL caret. Instrumented regression requires parent-owned execution.
- `a90792d5a599c7e1ab1d6f55985462c2f1f18311`: multi-turn undo confirmation retains
  downstream UndoFence and discards pending confirmation on conversation changes.
- `474642c2d712a25c5b4f06639919d93d84af43a3`: skipped as already covered by
  downstream `a15d0f82`: count-only partial/unconfirmed bulk outcomes tombstone no
  IDs and refresh authoritative survivors. Do not infer deleted IDs by subtracting
  an optional skipped list from an otherwise unconfirmed response.
- `dd1f2b05d32a5b8a151b3aa62805a63439cd19fd`: ONLY #1551 billing outcome
  classification, #1550 webhook configuration/overlap/refusal handling with
  connection-bound dispatch and load/mutation reconciliation, #1559 explicit
  connection-bound Doctor dispatch/polling and optional status gateway PID, #1567
  progressive structured Config loading, and #1556 session/connection-fenced
  command catalog successes and errors (before generic error reduction). Retain
  schema-aware editor reconciliation and generation fencing. REST operation
  preflights do not remove the pre-existing live-auth interceptor check/use race;
  see SECURITY.md. No local connection ID is sent as a server management profile.

No auth/token/retry/OAuth/upload, updater/update receipts, Ko-fi/what's-new,
dependency/CI policy, German, Local Models, Projects, or approvals ports.
`UPSTREAM.json` is intentionally untouched; this is not a contiguous review.

# Selective ports beyond the contiguous review baseline

`UPSTREAM.json` records a contiguous reviewed range. Its reviewed-through value
is deliberately unchanged for this batch: intervening upstream commits have
not all been dispositioned. Consult this supplementary record before repeating
ports from the newer range. Commit titles alone do not establish equivalence.

## Gateway compatibility batch

Based on fork commit `5c87b6ddba691715a1af5434bfb6cc8359cc828c`.
These are downstream adaptations, not wholesale upstream merges:

- `1cdc0dbfc042b70e9a355aeed69bacdd957e3796`: selected gateway liveness and
  streaming-flush behavior; retain downstream single-use tickets, stale socket
  fencing, history ownership, and reasoning continuity.
- `fb0f93c000bafc76be0382434a9101941a2ed37b`: no standalone events-since patch
  required; this fork currently uses resume for replay. Resume open requests
  are admitted only through registered, scoped response IDs.
- `e4be8b146e693879985f977a6679a12316cb7db1`: same-ID gateway requests for
  approval, clarification, sudo, secrets, and minimal vault prompts. Replies
  bind to source profile, runtime session, socket, and connection generation;
  rejected writes retain prompts. No full vault settings/catalog port.
- `f94d1784d43d0229790755b43ec71d2b5ee46413` and
  `b2aa08de239c00eae430cebe76644de371851ade`: stale prompt dispatch protection,
  adapted with conversation-generation and atomic connection-bound sends,
  including redirect fallback and attachment preparation.
- `0a2c78213997dc1ba1bab83024b881b1cf2b04b2`: selected model-context correction.
  Accepted model changes invalidate the old model-specific maximum while
  retaining live occupancy; no restoration of upstream REST-polled metering.

Additional hardening includes exact-binding prompt replay/replacement,
malformed replay rejection, request admission cleanup on disconnect,
read-only locked clarification answers, masked transient credential inputs,
and independently visible, validated save-login origins.

The Coil migration remains owner-deferred. Kanban expansion, plugin catalog
installation, composer redesign, and broad upstream refactors are not included.
Live subagent/session visibility is a separate authorized follow-up batch.

## Low-risk maintenance batch (2026-09-26)

Reviewed against upstream `e4004decb4acd18273a19fca2b2f8d12b829ce8e`
(v1.30.0). `reviewed_through` is unchanged: most of the 156 intervening
commits build on upstream's tool-display and ChatViewModel refactors, which
this fork has not taken.

- `8c5a3a061f5207ead6b36b2d026a159a7f69a53b`: syntax highlighting for code
  cards moves off the main thread; applied to the downstream `CodeBlockCard`.
- `399892618f0359296eac6e7a9650ce5207666be4`: fixed-width embedded Git SHA for
  reproducible APKs, without the F-Droid rationale.
- `a2c9a025` and `f3023c74`: KSP 2.3.12 and LaTeX 1.5.5. New verification
  hashes were checked against Maven Central `.sha256` sidecars.
- The macOS `aapt2` artifact is now pinned (checked against Google Maven) so
  the verified build also runs on Apple silicon hosts.

The wholesale patches from `304a9018` and `1ab30f1e` target upstream's
`ui/chat/tool/` engine, which does not exist here. The process-output and
skill-result subset previously called not applicable is adapted below.
The `sqlcipher` 4.19.0 and Coil 3.x bumps, the scoped-route
expansion in `303019b9`, and all feature work remain for owner review.

## Reliability and scope correction (2026-09-27)

- `83b2a8fa9b5900905038b1dbc48c96dccfaabba1`: adapt only attachment-result
  normalization in `ChatViewModel`. Raw JSON and Kotlin map results both retain
  file references and image acknowledgements. The current downstream parser
  normally emits maps; this is defensive wire-result compatibility, not proof
  of a currently reproducible file-loss incident. History hydration changes
  from that upstream commit are not included.
- `303019b9a5d5daeabb9362aa32638e0a17f9357b`: do not port the route expansion.
  Downstream's old interceptor used the local connection ID as a backend
  profile name. Remove that incorrect inference and preserve explicit caller
  scopes. Full management-profile selection remains unimplemented; it needs a
  separate validated identity bound to the connection and backend contract.
- `98c417ae8420f5d4ff6243896b426e3a8c8f6529`: the bulk-completion guard is not
  applicable to this fork's current single-task Kanban workflow. No bulk API,
  selection, or evidence dialog was introduced by this batch.

The two attachment regressions fail with the normalization removed and pass
with it restored. Real Retrofit/MockWebServer tests also reproduce the wrong
scope on management reads and reverse-proxied writes, then pass after removal;
explicit profile queries remain unchanged. These focused results are not a
substitute for the full release validation matrix.

## Process-card output adaptation (2026-09-27)

- `7290b300ab9de05102f85c89b56e6104e25cfc15`: `output_preview` fallback
  for process polls; retain nonempty `output` precedence in the downstream
  `ChatBubble` parser, including the `process_manage` tool alias.
- `e539d650cf35d88d4da694bf9afa0a2986292975`: show a separate warning
  for positive, exact integral `output_cut` values representable as `Long`.
  Missing, zero, negative, fractional, string, and overflow values show no
  omission notice. No upstream ToolView engine was imported.
- `a567c91132dfcfd3f7a9b00ff00b1bef921713ce`: narrow skill-result
  correction: only a literal JSON boolean true produces a success indicator;
  absent or invalid success fails closed. Downstream branch ordering already
  handled explicit false, so no wholesale renderer port.

These commits are beyond the contiguous `UPSTREAM.json` reviewed range;
`reviewed_through` and its entries are intentionally unchanged.

## Rendering, language, and plan ports (2026-09-30)

Based on fork `2b8e0647154e393ff4584d3b8f701b8e480134d9`; inspected against
upstream main `ed915d08169469f9ea6c63357fa584ccbd40a919` and dev
`ee1d7a0b7527f6e2ac88bee6df96c24e5e4883bd`. Sara requested upstream
integration with as much autonomous implementation as possible. Existing
owner holds remain in force. These are selective adaptations, not an ancestry
merge or a claim that all intervening commits were reviewed.

- `1ba1a62f319df0ecbaf1aa14623f61548e638912`: nested inline emphasis, code
  delimiters, and styled link labels in the downstream Markdown parser.
- `e80b00a6a73eb112d420ed280c99e1830342df3c`: inline Markdown and formula
  rendering in table cells; preserve ambient table structure direction.
- `83ac8a3418ffc660f41ab4cf760ec8ee05cc53a7`: completed reasoning renders
  Markdown; streaming reasoning remains plain text.
- `4f14ecac4b19448e1176cedc71f5b26ce1df1927`: additive search highlights on
  styled text and syntax-highlighted code. No full upstream search-controller,
  occurrence navigation, or reasoning/tool match-discovery port.
- `18953e368b3fa73ae6f82070bf727b2d614d4ca1`: single prefix-gated URL match
  and memoized inline formulas with complete search/style inputs.
- `4fc4d097ccb196570fe57206f9a1a6b060912b7f` and
  `6a00696e68d09f1c1114c5e0ca6c813dc680e611`: memoized typing display and
  transcript content types, retaining stable keys and downstream bubble layout.
- `7c00f4407dfe722567ea5b6b6417a999df0ed415`: Arabic language selection and
  translations matching downstream resources. Untranslated downstream keys use
  English fallback; sparse locale overrides retain window geometry/font scale.
- `9690ce8dc63dd5fa278c45c882802edf3ace2e1d`: deduplicate History search
  results and fence obsolete query/connection responses. Confirmed renames are
  displayed as titles; confirmed deletions cannot be resurrected by an older
  search response. No new search pagination or backend contract.
- `ceb48132181348a9f752db5ce9d26b54fb305bb7`: parent-aware todo parsing and
  bounded, cycle-safe hierarchy rendering. No revisioned `todo.updated` live
  snapshot protocol. Duplicate IDs, orphan references, and rootless cycles
  retain every source occurrence.

Existing non-Google speech support (`dbd7008ee39818f96b16ab4a9c79d94508c98c9d`)
was verified against downstream `1d358d96daa9fba85956a2c99287ee8c8a43a81e` and
its earlier implementation commits. No second source port is needed. The
ledger's post-base resolution requirement prevents recording historical
source equivalence as a new resolution; that policy is not waived here.

Nested block/content-sized table work, full reasoning/tool search, dependency
migrations, the management-profile experiment, auth/header changes, updater
work, signing changes, and broad refactors are not included.

## Profile creation and hub preview contract fixes (2026-10-18)

Based on fork `6cedf9099d04b603459118ccde0f86125043f8bf`.
Selectively adapted from upstream audit commit
`dd1f2b05d32a5b8a151b3aa62805a63439cd19fd`:

- #1549 only: `CreateProfileRequest.keep_skills` is an optional string list;
  opt-out uses the separate optional `no_skills` boolean. The profile builder
  sends `no_skills=true` when default skills are disabled and omits both fields
  for the default policy.
- #1548 only: the hub preview endpoint decodes its `skill_md` payload through
  `SkillHubPreviewResponse`, independently of installed-skill `content`.
  Preview replacement, blank selection, dialog dismissal, hub-tab exit and
  disposal invalidate the preview generation and cancel pending work. Late
  results cannot overwrite a replacement, including reopening the same skill.
  Callback wiring targets the active private browse view in `SkillsScreen`;
  the unused similarly named component is unchanged.

This is not acceptance of the whole audit commit or its management-profile,
authentication, transport, retry, receipt-display, or dependency changes.
Contiguous review boundaries, entries, dispositions and resolutions remain
unchanged. Only `fork_base` is normalized to current `origin/main` after normal
ledger validation and historical-base ancestry proof. Regression and local
validation receipts are in the maintenance implementation handoff. Both-flavor
connected execution subsequently passed all 109 tests per flavor, including
the real-parent preview and profile-builder regressions.

A pre-existing localization parity failure was independently reproduced on the
exact base with hash-identical catalogs/checker. This candidate also supplies
only the missing downstream labels (Chinese 16, Japanese 22, Korean 16),
preserving format arguments and the checker policy. These localized labels are
a downstream repair, not a claim that upstream supplied Japanese translations.

## Previous reliability-batch validation and release boundary

The following evidence covers the preceding 2026-09-27 reliability batch,
not the 2026-09-30 candidate.

Independent specification and code-quality reviews were completed, including
corrections and targeted regression tests. Both-flavor unit tests, Android lint,
debug assemblies, release Kotlin compilation, formatting, and color checks
passed locally. Full-suite testing exposed an authentication test collector
leak; teardown now resets that singleton before the next test changes Main.

Connected-device tests and exact-head hosted continuous integration remain
release gates. This document is not evidence of publication. A release must
also verify the downloaded signed artifact, clean install, previous-release
upgrade, and public latest-release readback.
