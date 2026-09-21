# Fast Idea Bridge — Agent Guide

## Scope

- Primary IntelliJ plugin and source of shared editor-bridge rules for all three plugins.
- Related projects: `D:\vs\fast` (Windows host) and `D:\v\fastjs` (Node service).

## IntelliJ plugin

- Plugin ID: `com.mirror.ideafocusbridge` — never change it.
- Java package: `com.mirror.ideafocusbridge`.
- Minimum platform: IntelliJ 2024.1 / build `241`; Java bytecode target: 17.
- Read the release version from `pluginVersion` in `gradle.properties`; never hard-code it.
- Use `com.intellij.modules.platform`; do not make the plugin Ultimate-only.

## Shared editor bridge

This section is authoritative; sibling `AGENTS.md` files should reference it, not copy it.

- Default endpoint: `ws://127.0.0.1:9072`. Keep endpoint, bearer token, auto-connect, text sharing, and context length configurable. Preserve 1-second connect, 2-second ping, 6-second pong, and 200/500/1000/2000-ms reconnect timing; do not add application heartbeats.
- Preserve identity (`installationId`, `instanceId`, `connectionId`, project IDs/roots, and open projects). Mutating commands require the exact instance and matching project when supplied; reject unsafe paths.
- Current-editor commands without an explicit path target only the exact `instanceId` and the plugin's active editor, including temporarily opened files. Only path-based commands may require project identity or root validation.
- Keep hot paths lightweight: prefer cached/native state and focused requests; avoid full-document reads, repeated RPCs, CodeModel/EnvDTE/COM, UI Automation, simulated-key fallback, and unnecessary UI-thread work.
- Keep `focus`, `activeEditor`, and `isEditorFocused` distinct. `isEditorFocused` is true only when the code editor owns keyboard focus; unknown or other UI states are false. VS Code focus remains Fast-side via `f.emit('isVsCodeEditorFocused', {})`.
- For `find`, `NOT_FOUND` means handled. Fall back to simulated Ctrl+F only for errors, timeouts, unsupported commands, or unavailable targets.
- Keep text sharing configurable, preserve structural ranges when disabled, redact passwords, and expose only visible top-level HWNDs in Windows handshakes.
- After reconnect, send a fresh handshake and complete state.
- Preserve backward compatibility and three-plugin alignment. Shared wire changes require corresponding adapter and protocol/documentation updates.
- Before shared changes, read `D:\Obsidian\knowledge\projects\fast-idea\editor-bridge.md` and inspect all affected repositories.

## IntelliJ runtime

- Never perform network I/O on the Swing EDT. Read Swing/editor state on the EDT and serialize/send it in the background.
- Keep the plugin dynamically unloadable: bind listeners to the plugin `Disposable`, remove AWT listeners, stop executors, and close sockets.
- Use IntelliJ `Logger` for warnings/errors and concise three-day rolling logs; never log source text, selections, tokens, or passwords.
- The local updater is development-only: require `fast.idea.bridge.dev=true`, a loopback URL, and explicit `fast.idea.bridge.autoRestart=true`; never poll or install from a non-loopback repository.

## Verification

For externally visible or shared changes, update affected adapters and protocol docs, then run:

```powershell
node --check D:\v\fastjs\utils\socket-browser.js
node --check D:\v\fastjs\utils\socket-idea.js
```

For shared changes, verify all three installable packages using their repository procedures; compilation or a JAR build alone is insufficient.

## Build and release

- Normal code changes use source-level checks. Build installable packages only for packaging, verification, publishing, installation, or release.
- Build with `.\build-plugin.cmd`; use `.\build-plugin.cmd -Verify` for release verification. The artifact is `build\distributions\fast-idea-bridge-<pluginVersion>.zip`.
- Publishing/installing requires explicit authorization and must not bump versions during ordinary changes. Use `publish-local-plugin.cmd`; use `-NoVersionBump` only for retries.
- Coordinated releases follow `D:\v\fastjs\AGENTS.md`; do not independently bump IDEA.
- For first installation, close IDEA and run `.\install-local-plugin.cmd`.
- Preserve settings/defaults and never overwrite a running plugin; use the supported update flow.
