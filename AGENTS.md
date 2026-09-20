# AGENTS.md

Guidance for coding agents working on this repository.

## Project Overview

This repo builds `boss-plugin-clipboard-history`, a BOSS desktop plugin
that maintains a persistent clipboard ring buffer across restarts. The
plugin is a `mixed` (panel + MCP tools) JAR loaded by BossConsole at
runtime; the host provides the api classes, the storage factory and
the `ClipboardProvider` it consumes.

The plugin does ONE thing - keep the last 200 copied texts, deduplicate,
mask secrets, search/pin/restore - and that is what every file in this
repo exists to do.

## Build

```bash
./gradlew clean buildPluginJar -x test --no-daemon
```

Outputs `build/libs/boss-plugin-clipboard-history-0.1.0.jar`. The jar
is intentionally thin: compiled classes plus the manifest under
`META-INF/boss-plugin/plugin.json`. The api jar is `compileOnly`; the
host provides it at runtime.

## Plugin entry point

`ClipboardDynamicPlugin` (`src/main/kotlin/ai/rever/boss/plugin/dynamic/clipboard/ClipboardDynamicPlugin.kt`).
Implements `DynamicPlugin`. `register(context)` builds the
`ClipboardStore` from the host's `PluginStorageFactory`, registers the
panel factory and the MCP-tool provider. `dispose()` nulls the
references so a panel swap or plugin unload cannot leave a background
loop ticking against the host clipboard.

## Module layout

```
src/main/
  kotlin/ai/rever/boss/plugin/dynamic/clipboard/
    ClipboardDynamicPlugin.kt     - entry point, register/dispose
    ClipboardInfo.kt              - PanelInfo (id, icon, slot)
    ClipboardComponent.kt         - PanelComponentWithUI
    ClipboardContent.kt           - @Composable UI
    ClipboardViewModel.kt         - StateFlows, suspend actions
    ClipboardEntry.kt             - data class
    ClipboardStore.kt             - persistence + JSON (private)
    ClipboardPoller.kt            - 1s polling loop
    SecretMask.kt                 - regex mask for SECRET/TOKEN/KEY/...
    ClipboardMcpTools.kt          - MCP tool provider
  resources/META-INF/boss-plugin/plugin.json
```

## Capture mechanism

`ClipboardPoller` runs on a `SupervisorJob` tied to the plugin's scope.
Once per second it reads `context.clipboardProvider.readText()`,
compares against the last stored value, and appends if changed. The
poller is paused by the same flag the MCP tools read/write, so a
toggle from one place is reflected in the other without re-reading
anything.

## Storage shape

A single JSON blob under the key `clipboard_history_v1`. The list is
stored newest-first. `ClipboardStore` parses and serialises it with a
private hand-rolled JSON reader/writer - the api exposes
`PluginStorageProvider.putJson`/`getJson` as a `String`, so no
kotlinx-serialization dependency was pulled in.

Capacity is bounded by `ClipboardStore.MAX_ENTRIES = 200` and
`MAX_TEXT_BYTES = 64 * 1024`. New appends evict the oldest UNPINNED
entry first; pinned entries only evict after every unpinned one is
gone. Text larger than `MAX_TEXT_BYTES` is REFUSED, not truncated.

## Secret masking

`SecretMask.isSecret(text)` matches
`SECRET|TOKEN|KEY|PASSWORD|CREDENTIAL|API_KEY|BEGIN [A-Z ]*PRIVATE KEY`
case-insensitive, on the full text. A match means the entry is stored
as the literal placeholder `<masked>` and the original text is
discarded. There is no reveal path for the original - by construction.

## MCP tools

`ClipboardMcpToolProvider` registers eleven tools on the `boss` MCP
server while the plugin is active. Read tools default to `readOnly =
true`; mutating verbs (`pin`, `unpin`, `delete`, `clear`, `pause`,
`resume`, `restore`) are explicitly `readOnly = false` so the host's
governed-MCP rule presents them as side-effecting.

`clipboard_history_reveal` is the one path that returns the stored
text of a non-masked entry. The host MCP ledger records the call.

## CI

- `.github/workflows/build.yml` - the release workflow; gated on push
  to `main` with `permissions: contents: write` so the reusable
  release workflow can publish a GitHub Release.
- `.github/workflows/test.yml` - the Tests workflow; runs on every PR
  and downloads the latest `boss-plugin-api` jar from
  `risa-labs-inc/boss-plugin-api/releases` to compile against.

## Conventions

- All Kotlin files end with a newline.
- Spaced hyphens (` - `) in prose; no em-dashes (U+2014) anywhere a
  person reads.
- Commit messages and PR descriptions stay focused on the change;
  no third-party authorship trailers.
- Logging uses the host's `BossLogger` if needed; nothing is logged by
  default to avoid noise during normal capture.

## Notes for future changes

- The api jar lives at `../boss-plugin-api/` (sibling repo). The
  `useLocalDependencies` switch in `build.gradle.kts` (driven by
  `CI != "true"`) selects the local jar when developing and the
  downloaded jar in CI - so bumping the api version means updating
  `build.gradle.kts`, `plugin.json`'s `apiVersion` and
  `minApiVersion`, and nothing else.
- Adding a new MCP tool is one entry in
  `ClipboardMcpToolProvider.tools()`. The host picks the provider up
  on register and removes it on disable/unload automatically - no
  manual unregister is needed.
- Adding a new entry field means updating `ClipboardEntry`,
  `ClipboardStore.parseList` and `ClipboardStore.serializeList`.
  Anything else that emits entry text (MCP formatters, UI) reads the
  field through the data class.
- The poller is intentionally simple. If a future api exposes a
  clipboard-change signal, swap the polling loop for a hook - the
  store append path is the same.
