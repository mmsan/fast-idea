# Fast Idea Bridge — Agent Guide

## Workspace topology

- `D:\vs\fast` is the primary Windows application.
- After the Windows application starts, it launches and uses `D:\v\fast`, a Node.js service.
- The Node.js service calls the three editor plugin projects: `D:\v\fast-idea`, `D:\v\fast-visual-studio`, and `D:\v\fast-vscode`.
- Changes involving the service or shared editor-bridge behavior must be evaluated across this application, service, and plugin chain.

## Stable identity and scope

Fast Idea Bridge is an IntelliJ Platform plugin that exchanges focus/editor state and targeted commands with the local Fast WebSocket service.

- Display name: `Fast Idea Bridge`
- Plugin ID: `com.mirror.ideafocusbridge` — never change it; IDEA treats a changed ID as a different plugin.
- Java package: `com.mirror.ideafocusbridge`
- Minimum platform: IntelliJ 2024.1 / build `241`; Java bytecode target: 17.
- Read the release version from `pluginVersion` in `gradle.properties`; never hard-code it.
- Use `com.intellij.modules.platform`; do not make the plugin Ultimate-only.

Primary implementation and release surfaces:

- `FocusBridgeService.java`: lifecycle, WebSocket, retry, heartbeat, listeners, and sending.
- `BridgeLog.java`: concise host and rolling plugin logs with three-day retention.
- `FocusSnapshotCollector.java`: Swing focus and editor-state collection.
- `BridgeCommands.java`: handshake identities, commands, path checks, and Windows HWNDs.
- `FocusBridgeSettings.java` / `FocusBridgeConfigurable.java`: persisted settings and UI.
- `FAST_IDEA_BRIDGE_PROTOCOL.md`, `README.md`: externally visible behavior and examples.
- `build-plugin.cmd`, `publish-local-plugin.cmd`, `install-local-plugin.cmd`: package and local-repository workflow.

## Runtime and protocol invariants

- Default endpoint is `ws://127.0.0.1:19900`; host, port, path, bearer token, auto-connect, text sharing, and context length remain configurable.
- Preserve the local connection policy: 1-second connect timeout, 2-second ping interval, 6-second pong timeout, and reconnect delays of 200 ms, 500 ms, 1 s, 2 s, then 2 s repeatedly. Avoid duplicate reconnect schedules. After reconnect, send `bridge.hello` and a complete `editor.state`. Node `ws` handles protocol Ping frames; do not add an application heartbeat.
- Never perform network I/O on the Swing EDT. Read Swing/editor state on the EDT and serialize/send it in the background.
- Every message keeps `installationId`, `instanceId`, `connectionId`, deterministic `projectId`, `projectBasePath`, and all open `projects`.
- Mutating commands (`command.navigate`, `command.select`, `command.requestFocus`, `command.copyResourcePath`) require `target.instanceId`; silently ignore other instances. If supplied, `target.projectId` or `target.projectBasePath` must select the exact project.
- Resolve relative paths only inside open project roots. Reject traversal and absolute paths outside those roots.
- `command.copyResourcePath` handles one or more selected files/directories in a focused Project View. Return all absolute paths in `data.paths`, retain `data.path` for a single selection, and otherwise return `data.handled: false`.
- In explicit local development mode, poll the loopback plugin repository 10 seconds after startup and every 10 seconds thereafter. Download and prepare newer versions, then ask the user whether to restart; never restart IDEA without confirmation. Official-repository installs must not enable this updater.
- Keep actual GUI `focus` separate from `activeEditor`. Prefer the editor/project from the focused component DataContext, then fall back to selected editors.
- Preserve absolute and relative paths, project identity/root, caret positions, screen coordinates, primary/all selections, and bounded optional text context. Text sharing is configurable; redact password fields unconditionally.
- On Windows, include process ID and visible top-level HWNDs only in `bridge.hello`, as unsigned decimal strings plus optional hex diagnostics. Preserve all windows and the initial active handle.
- Keep the plugin dynamically unloadable: register listeners with the plugin `Disposable`, remove AWT listeners, stop executors, and close sockets. Do not add `require-restart="true"` without a real restart requirement.
- Keep diagnostics concise: use IntelliJ `Logger` for warnings/errors and the plugin log for connection lifecycle summaries; never log source text, selections, tokens, or passwords. Remove plugin log files older than three days.
- Only when the explicit `fast.idea.bridge.dev=true` system property is set, poll the configured loopback plugin repository 10 seconds after startup and every 60 seconds thereafter. The local installer writes this property plus `fast.idea.bridge.autoRestart=true` to `idea.properties`; both default to false so official-repository installations never use local polling or automatic restart. While the installed version remains lower, allow the same repository version to retry once per minute so transient publication/download failures recover. Use IntelliJ's `PluginDownloader` for download and compatibility checks, register the restart update, then save/restart IDEA through `ApplicationManagerEx` only when the explicit auto-restart property is true. Never poll or install from a non-loopback repository in this local updater.

## Plugin log locations (on demand)

- Host log: IDEA standard `idea.log`, located via **Help | Show Log in Explorer**.
- Plugin log: the newest `fast-idea-bridge\\fast-idea-bridge-YYYY-MM-DD.log` in the IDEA log directory.

## Cross-project synchronization

This is one of three implementations of the Fast editor bridge. Any shared event, command, identity, targeting rule, path-safety rule, text-sharing rule, reconnect behavior, or disposal change must be checked in the sibling repositories:

- `D:\v\fast-visual-studio`
- `D:\v\fast-vscode`
- Fast-side adapters: `D:\v\fast\utils\socket-idea.js`, `socket-visual-studio.js`, and `socket-vscode.js`
- Generic current-editor API: `D:\v\fast\utils\editor-bridge.js`; keep `f.ideaFocusBridge`, `f.visualStudioBridge`, and `f.vscodeBridge` available for explicit plugin targeting.

When changing the handshake, fields, commands, or payloads, update the relevant Fast adapter, protocol documents, and README examples. Keep routing backward compatible and run the changed adapter checks, including:

```powershell
node --check D:\v\fast\utils\socket-browser.js
node --check D:\v\fast\utils\socket-idea.js
```

For shared changes, verify all three installable packages using the package procedure in each repository. A Java compile, JAR build, or IDE project build alone is not package verification.

## Build and release package

The installable artifact is the ZIP from Gradle `buildPlugin`, not `compileJava`, `jar`, or an IDE build. Use the repository wrapper:

```powershell
.\build-plugin.cmd
```

For release-quality verification, use:

```powershell
.\build-plugin.cmd -Verify
```

The wrapper always uses the standalone JDK 17 installed at `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot`, never an IntelliJ IDEA JBR or an external `JAVA_HOME` value. The IntelliJ Platform SDK is resolved by Gradle from the configured platform dependency; it is not the installed IDEA application. The wrapper prefers `.gradle-dist\gradle-9.0.0` before the Gradle wrapper, runs `buildPlugin`, verifies the ZIP, and prints its absolute path and size. It also runs `verifyPluginProjectConfiguration` and `verifyPluginStructure` with `-Verify`. The expected artifact is:

```text
D:\v\fast-idea\build\distributions\fast-idea-bridge-<pluginVersion>.zip
```

Keep release verification across IntelliJ 2024.1, 2024.3, 2025.x, and a current/recommended IDE. Do not add an `until-build` without a concrete incompatibility.

`build-plugin.cmd` is the normal package wrapper and writes to the standard `build` directory, while using the isolated `.intellijPlatform-build\gradle-cache` project cache by default. It therefore does not use the running IDEA project's `.gradle\9.0.0\fileHashes\fileHashes.lock`; `-ProjectCacheDirectory` is available for an explicit override. The project-local `.gradle` directory is disposable and may be absent; do not recreate or troubleshoot it unless an explicit override points there. In contrast, `.gradle-dist\gradle-9.0.0` is the local Gradle runtime used by the wrapper and must be kept for offline/reliable builds. For publishing while an IntelliJ IDEA process may have the project open, use `publish-local-plugin.cmd`; it automatically uses `.intellijPlatform-release\gradle-cache`, `.intellijPlatform-release\platform-cache`, `.intellijPlatform-release\sandbox`, and `.intellijPlatform-release\build`, so it does not share the running IDEA project's Gradle cache, IntelliJ Platform cache, sandbox, or build output. Its optional `-ProjectCacheDirectory`, `-IntelliJPlatformCacheDirectory`, `-SandboxContainer`, and `-BuildDirectory` parameters are for explicit CI or troubleshooting overrides.

## Local repository publishing and installation

Local publication is an explicit user-authorized action, not a default verification step. After ordinary code changes, only build and verify the installable ZIP. Do not update the local Nginx repository, bump a release solely for publication, or run `publish-local-plugin.cmd` unless the user explicitly asks to publish locally, release a version, or uses equivalent wording. A request to modify, fix, build, test, or verify does not authorize publication.

The existing local Nginx mapping is `D:\docker\www -> /www`, host port `8800` to container port `80`. Repository URLs are:

```text
http://127.0.0.1:8800/idea-plugins/updatePlugins.xml
http://127.0.0.1:8800/idea-plugins/fast-idea-bridge-<version>.zip
```

HTTP on loopback is acceptable for local use; bind the Docker service to `127.0.0.1` when it must not be reachable from the LAN.

Publish with:

```powershell
.\publish-local-plugin.cmd
```

The workflow must increment the patch version, restore it on failure, build the plugin, copy the ZIP to `D:\docker\www\idea-plugins`, verify the new ZIP URL returns HTTP 200 with non-empty `Content-Length`, atomically replace `updatePlugins.xml`, and verify both the metadata URL and its version/ZIP entry. If post-publish verification fails, restore the previous metadata and remove the newly published ZIP. Use `-NoVersionBump` only to retry a version already incremented by an interrupted/manual workflow; IDEA requires a higher repository version to detect an update.

For a coordinated three-plugin release, use the executable one-command runbook in `D:\v\fast\AGENTS.md` under **Fast path: one-command coordinated local release**. Do not independently bump IDEA when the other two plugins are part of the same release.

For first installation, close all IDEA processes and run:

```powershell
.\install-local-plugin.cmd
```

The installer registers `idea.plugin.hosts`, downloads from Nginx, backs up the old plugin outside the active `plugins` directory, installs the archive, verifies the JAR version, and rolls back on failure. Write `idea.plugin.hosts` as an unquoted URL/property value; quotes become literal URI characters and cause `URISyntaxException`. Never leave a backup with the same plugin ID inside the active plugins directory. After the first installation, enable **Settings | Plugins | Update Plugins Automatically** once.

## Change discipline

- Preserve settings when adding fields and provide defaults for older persisted state.
- Keep protocol additions backward compatible where practical; increment the protocol version only for breaking changes.
- Do not send full documents by default or log source text, selections, tokens, passwords, or bearer credentials.
- Limit and validate inbound JSON messages; keep UI reads on the EDT and callbacks non-blocking.
- Update protocol/docs after externally visible changes.
- Do not overwrite a running IDEA plugin manually; close IDEA or use supported dynamic update mechanisms.
