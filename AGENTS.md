# Fast Idea Bridge — Agent Guide

## Workspace topology

- This is the primary plugin project for shared rules. The Windows host is `D:\vs\fast`; the Node service is `D:\v\fastjs`.
- Shared plugin behavior is defined below and is referenced by `D:\v\fast-visual-studio\AGENTS.md` and `D:\v\fast-vscode\AGENTS.md`.

## Stable identity and scope

Fast Idea Bridge is an IntelliJ Platform plugin that exchanges focus/editor state and targeted commands with the local Fast WebSocket service.

- Display name: `Fast Idea Bridge`
- Plugin ID: `com.mirror.ideafocusbridge` — never change it; IDEA treats a changed ID as a different plugin.
- Java package: `com.mirror.ideafocusbridge`
- Minimum platform: IntelliJ 2024.1 / build `241`; Java bytecode target: 17.
- Read the release version from `pluginVersion` in `gradle.properties`; never hard-code it.
- Use `com.intellij.modules.platform`; do not make the plugin Ultimate-only.

Key surfaces: `FocusBridgeService.java` (lifecycle/transport), `FocusSnapshotCollector.java` (focus/editor state), `BridgeCommands.java` (commands/path safety), `BridgeLog.java` (logs), settings/configuration files, protocol docs, and the build/publish/install scripts.

## Shared editor-bridge rules

- This section is authoritative for all three editor plugins. Keep sibling Agent files as references, not copies of these rules.
- Do not duplicate method signatures or return descriptions when the source is cheap to read; inspect the implementation instead.
- Use `ws://127.0.0.1:19900` by default; keep endpoint, bearer token, auto-connect, text sharing, and context length configurable. Preserve 1-second connect, 2-second ping, 6-second pong, and 200/500/1000/2000-ms reconnect timing; do not add application heartbeats.
- Preserve message identity (`installationId`, `instanceId`, `connectionId`, project IDs/roots, and open projects). Mutating commands require the exact instance and matching project when supplied; reject unsafe paths.
- Current-editor commands (caret, selection, or text actions without an explicit path) target only the exact `instanceId`; the plugin must operate on its own active editor and must not require a workspace/project. This includes temporarily opened files outside any workspace. Only path-based commands may require matching project identity or project-root validation.
- Keep GUI `focus` separate from `activeEditor`. Efficiency is mandatory: avoid full-document reads, CodeModel/EnvDTE/COM, UI Automation, repeated RPCs, and simulated-key fallback on hot paths.
- Prefer cached editor state, native snapshots, one focused request, and minimal UI-thread work; rich state collection is the usual cause of long current-line, selection, caret, or find calls.
- For `find`, `NOT_FOUND` means the plugin handled the request; only errors, timeouts, unsupported commands, or unavailable targets fall back to simulated Ctrl+F.
- `isEditorFocused` is true only when the code editor itself owns keyboard focus. Search boxes, terminals, explorers, sidebars, external windows, recent editors, and unknown states are false.
- VS Code focus is checked by the Fast-side native host through `f.emit('isVsCodeEditorFocused', {})`; do not reintroduce a slower extension-side approximation.
- Text sharing remains configurable; preserve structural ranges when disabled, redact passwords, and expose only visible top-level HWNDs in Windows handshakes.
- Preserve backward compatibility and three-plugin alignment; update all adapters and protocol/docs for shared wire changes.
- Before shared changes, read `D:\Obsidian\knowledge\projects\fast-idea\editor-bridge.md`, inspect all affected repositories, update protocol/docs, and run focused checks.

## IntelliJ-specific runtime rules

- Never perform network I/O on the Swing EDT. Read Swing/editor state on the EDT and serialize/send it in the background.
- Keep the plugin dynamically unloadable: register listeners with the plugin `Disposable`, remove AWT listeners, stop executors, and close sockets.
- Use IntelliJ `Logger` for warnings/errors and concise three-day rolling plugin logs; never log source text, selections, tokens, or passwords.
- The local updater is development-only: require `fast.idea.bridge.dev=true`, loopback URLs, and explicit `fast.idea.bridge.autoRestart=true` before restarting; never poll or install from a non-loopback repository.

- Keep `command.copyResourcePath` scoped to the focused Project View; inspect its implementation for response fields.

## Plugin log locations (on demand)

- Host log: IDEA standard `idea.log`, located via **Help | Show Log in Explorer**.
- Plugin log: the newest `fast-idea-bridge\\fast-idea-bridge-YYYY-MM-DD.log` in the IDEA log directory.

## Cross-project changes

Use the shared rules above. For externally visible IntelliJ behavior, update protocol documents and README examples, then run the relevant adapter checks:

```powershell
node --check D:\v\fastjs\utils\socket-browser.js
node --check D:\v\fastjs\utils\socket-idea.js
```

For a shared change, verify all three installable packages using each repository's package procedure. A Java compile, JAR build, or IDE project build alone is not package verification.

## Build and release package

- Ordinary code changes use source-level checks only. Build the installable ZIP only when packaging, verification, publishing, installation, or release is requested.
- The package entry point is `build-plugin.cmd`; the release verification entry point is `build-plugin.cmd -Verify`.
- The expected artifact is `build\distributions\fast-idea-bridge-<pluginVersion>.zip`; keep the version from `gradle.properties`.

```powershell
.\build-plugin.cmd
```

## Local repository publishing and installation

- Publishing/installing is explicit user-authorized work; ordinary changes do not update the local repository or bump versions.
- Use `publish-local-plugin.cmd` for a local release. It must build, publish atomically, verify metadata/package availability, and roll back on failure; use `-NoVersionBump` only for a retry.
- For a coordinated release, follow the one-command runbook in `D:\v\fastjs\AGENTS.md`; do not independently bump IDEA.
- For first installation, close IDEA and run:

```powershell
.\install-local-plugin.cmd
```

## Change discipline

- Preserve settings/defaults for older state. Do not overwrite a running IDEA plugin; use the supported update flow.
