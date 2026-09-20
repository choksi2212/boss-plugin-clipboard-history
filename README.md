# Boss Plugin - Clipboard History

A persistent clipboard ring buffer for BOSS. Every text the user copies
(whether from another app or from inside BOSS itself) is captured into a
searchable, pinnable panel. The last 200 entries are kept across restarts,
deduplicated by exact text, and one click away from being re-copied.

## Why it is unique

BOSS has no clipboard history today. The user copies text, switches
focus, and has to find it again. This plugin solves that gap with a
single panel that lives in the left sidebar's bottom slot.

## What it does

- **Persistent ring buffer** - up to 200 entries, newest first,
  survives restart. Deduplicated by exact text.
- **Pin favorites** - pinned entries float to the top of the panel and
  survive `Clear unpinned`.
- **Substring search** - one search box filters the visible list.
- **One-click restore** - clicking an entry writes its text back to the
  system clipboard through the host's `ClipboardProvider`.
- **Per-entry actions** - right-click semantics are exposed through the
  row's own buttons: pin/unpin, delete, copy.
- **Pause toggle** - stop recording future copies without losing what
  is already captured.
- **Clear history** - drops every UNPINNED entry; pins are preserved.

## Secret masking

Any captured text that matches the broad pattern
`SECRET|TOKEN|KEY|PASSWORD|CREDENTIAL|API_KEY|BEGIN [A-Z ]*PRIVATE KEY`
(regex, case-insensitive) is REPLACED with the literal placeholder
`<masked>` before being stored. The original text is discarded - there
is nothing to reveal afterwards. The panel renders a warning banner
above the list so users can see what the masking policy is.

The plugin is the host's own clipboard watcher; a copy that includes a
`password=` line anywhere in it is masked. This is a heuristic gate,
not a security boundary. A copy that does not match the pattern is
stored as-is, and any captured entry (masked or not) is one
reveal-click away from an agent or another plugin. Do not treat the
ring buffer as a vault.

## MCP tools

Surfaced on the `boss` MCP server while the plugin is active:

| Tool | Mutating? | Purpose |
|---|---|---|
| `clipboard_history_list(query?)` | no | Recent entries; optional substring filter |
| `clipboard_history_get(id)` | no | One entry by id (masked text) |
| `clipboard_history_search(query)` | no | Full-text search, up to 200 hits |
| `clipboard_history_pin(id)` | yes | Pin an entry |
| `clipboard_history_unpin(id)` | yes | Unpin an entry |
| `clipboard_history_delete(id)` | yes | Delete one entry |
| `clipboard_history_clear()` | yes | Clear all UNPINNED entries |
| `clipboard_history_pause()` | yes | Pause capture |
| `clipboard_history_resume()` | yes | Resume capture |
| `clipboard_history_restore(id)` | yes | Write an entry back to the clipboard |
| `clipboard_history_reveal(id)` | no | Return the entry's text (ledgered) |

`clipboard_history_reveal` is logged in the host MCP ledger and is the
only path that surfaces the stored text of a non-masked entry.

## Capture mechanism

There is no host-side hook for `clipboard changed` today, so the plugin
polls the host's `ClipboardProvider.readText()` once per second and
compares against the last stored value. Plugins cannot use AWT's
`Toolkit.getSystemClipboard()` directly - the api gates clipboard
access through the provider precisely because the plugin classloader
would otherwise not see `java.awt.Toolkit`. If the provider returns
null the panel renders `Host clipboard unavailable` and stops polling.

The poll loop reads on a `SupervisorJob` tied to the plugin's scope,
not the panel's, so background capture continues while the panel is
collapsed.

## Storage

Backed by the host's per-plugin `PluginStorageProvider` (a single
JSON blob under the key `clipboard_history_v1`). Settings, secrets
and a per-plugin scope are owned by the host; this plugin contributes
no on-disk files of its own.

Capacity is bounded:

- `MAX_ENTRIES = 200` hard cap; a new append evicts the oldest
  UNPINNED entry first, pinned entries only evict after every
  unpinned one has gone.
- `MAX_TEXT_BYTES = 64 KiB` per entry; larger text is REFUSED, not
  truncated.

## Compatibility

- Built against `boss-plugin-api` 1.0.93.
- Targets `BOSS 9.4.x` and newer.

## Install

1. Download the jar from this repo's GitHub Releases (the `Release`
   workflow produces one on every push to `main`).
2. Open BOSS -> Settings -> Plugins -> Install from file.
3. The Clipboard History panel appears in the left sidebar's bottom
   slot.

## Build

```bash
./gradlew clean buildPluginJar -x test
```

The jar lands at `build/libs/boss-plugin-clipboard-history-<version>.jar`.

## License

This plugin is released under the same license as BOSS itself.
