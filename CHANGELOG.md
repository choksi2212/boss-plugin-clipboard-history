# Changelog

## 0.1.0 - Initial release

- Persistent clipboard ring buffer (last 200 entries, newest first,
  deduplicated by exact text, persists across restarts).
- One-second polling loop reading the host `ClipboardProvider`.
- Pin/unpin (pins float to the top of the panel and survive
  clear-history).
- Substring search with case-insensitive matching.
- One-click restore to the system clipboard via
  `ClipboardProvider.setText`.
- Pause toggle that stops capture without losing existing entries.
- Clear-history drops unpinned entries; pinned entries preserved.
- Secret-pattern matching (`SECRET|TOKEN|KEY|PASSWORD|CREDENTIAL|
  API_KEY|BEGIN [A-Z ]*PRIVATE KEY`, case-insensitive) replaces
  matched text with the literal placeholder `<masked>` before
  storage; the original is discarded.
- Eleven MCP tools on the `boss` server: `clipboard_history_list`,
  `clipboard_history_get`, `clipboard_history_search`,
  `clipboard_history_pin`, `clipboard_history_unpin`,
  `clipboard_history_delete`, `clipboard_history_clear`,
  `clipboard_history_pause`, `clipboard_history_resume`,
  `clipboard_history_restore`, `clipboard_history_reveal`.
- Privacy banner in the panel explains the masking policy.
