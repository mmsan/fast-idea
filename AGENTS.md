# Fast Idea Bridge — Agent Guide

## Project overview

Fast Idea Bridge is an IntelliJ Platform plugin that connects IntelliJ IDEA focus/editor state with the local Fast application over WebSocket. It publishes focus, active editor, file, caret, selection, text-context, project, process, and native-window information. It also accepts targeted commands for querying state, navigating to a file/line/column, selecting a range, and focusing an editor.

Keep the public plugin identity stable:

- Display name: `Fast Idea Bridge`
- Plugin ID: `com.mirror.ideafocusbridge`
- Java package: `com.mirror.ideafocusbridge`
- Current version: read `pluginVersion` from `gradle.properties`; never hard-code the current release version in documentation or automation.
- Minimum IntelliJ Platform: 2024.1 / build `241`
- Java bytecode target: 17
- Build system: Gradle 9 with IntelliJ Platform Gradle Plugin 2.x
- Platform dependency: `com.intellij.modules.platform`, so the plugin is not limited to IntelliJ IDEA Ultimate.

Do not change the plugin ID when renaming or evolving the plugin. Changing it makes IDEA treat the result as a different plugin and breaks local-repository updates.

## Important files

- `src/main/java/com/mirror/ideafocusbridge/FocusBridgeService.java`: lifecycle, WebSocket connection, heartbeat, retry, event listeners, and message sending.
- `src/main/java/com/mirror/ideafocusbridge/FocusSnapshotCollector.java`: Swing focus and IntelliJ editor-state extraction.
- `src/main/java/com/mirror/ideafocusbridge/BridgeCommands.java`: handshake identity, instance/project identities, native HWND extraction, and inbound commands.
- `src/main/java/com/mirror/ideafocusbridge/FocusBridgeSettings.java`: persisted application settings.
- `src/main/java/com/mirror/ideafocusbridge/FocusBridgeConfigurable.java`: Settings | Tools | Fast Idea Bridge UI.
- `src/main/resources/META-INF/plugin.xml`: plugin descriptor and extension registration.
- `FAST_IDEA_BRIDGE_PROTOCOL.md`: detailed wire protocol. Update it whenever message fields or command behavior change.
- `README.md`: concise usage and command examples.
- `LOCAL_REPOSITORY.md`: local Nginx publishing and installation guide.
- `publish-local-plugin.ps1`: version bump, build, Nginx publish, XML generation, and HTTP verification.
- `install-local-plugin.ps1`: one-time repository registration and offline-safe installation with backup/rollback.

## Cross-project Node.js WebSocket service

This plugin is paired with the Fast application’s Node.js WebSocket server, which is maintained outside this project:

- `D:\v\fast\utils\socket-browser.js`: generic WebSocket server on port `19900`; it forwards messages and close events to the IDEA-specific module.
- `D:\v\fast\utils\idea-socket.js`: Idea Focus Bridge handshake recognition, project-name WebSocket caching, Focus Bridge subscriptions, editor-state caching, current-editor queries, and command sending helpers.
- `D:\v\fast\utils\f.mjs`: FastJS file navigation entry point; `gotoFile()` and `gotoJs_()` detect `D:\v\<projectName>\...` paths and prefer the matching Focus Bridge WebSocket, while retaining the original UI-automation fallback.

When changing the plugin’s handshake, event fields, command names, or command payloads, inspect and update `D:\v\fast\utils\idea-socket.js` as part of the same change. The IDEA module caches `bridge.hello` project names in `f.ideaFocusBridge.wsByProjectName` and exposes `f.ideaFocusBridge.getWs()`, `sendToProject()`, `openFile()`, and `find()` for FastJS callers. Do not assume that changing only the IntelliJ plugin is sufficient; the Node service may be consumed from other Fast projects.

The external files are outside this project’s normal source tree, so verify their absolute paths before editing. Keep the Node-side routing backward compatible and run `node --check D:\v\fast\utils\socket-browser.js` and `node --check D:\v\fast\utils\idea-socket.js` after changes. The current-IDE helpers `getCurrentProjectName()`, `getCurrentFilePath()`, `getCurrentLine()`, and `getCurrentSelectedText()` return empty values unless `f.fast.currentWindow.hwnd` belongs to a connected IDEA window; when the current process is IDEA/CLion but no matching bridge is connected, they emit a throttled `f.log(..., true)` installation hint.

## WebSocket behavior

Default endpoint:

```text
ws://127.0.0.1:19900
```

Host, port, path, bearer token, auto-connect, text sharing, and context length are user-configurable. Do not replace these settings with fixed constants except for defaults.

Connection policy is intentionally aggressive because the Fast service is local and may restart frequently:

- WebSocket/HTTP connect timeout: 1 second
- Ping interval: 2 seconds
- Pong timeout: 6 seconds
- Reconnect backoff: 200 ms, 500 ms, 1 second, 2 seconds, then every 2 seconds
- Avoid duplicate concurrent reconnect schedules.
- After reconnection, send a new `bridge.hello` followed by a complete `editor.state`.
- Node.js `ws` automatically replies to protocol Ping frames; no application-level heartbeat is required.

Never perform network I/O on the Swing EDT. Read Swing/editor state on the EDT and send serialized snapshots in the background.

## Multiple IDEs and projects

Every outgoing message contains a `source` identity. Preserve these identity levels:

- `installationId`: persisted for the plugin installation
- `instanceId`: unique for one IDE process lifetime
- `connectionId`: changes on each successful WebSocket connection
- `projectId`: deterministic from the normalized project root path
- `projectBasePath`: absolute project root directory
- `projects`: all projects open in the current IDE process

Mutating commands (`command.navigate`, `command.select`, and `command.requestFocus`) require `target.instanceId`. Commands targeting another instance must be silently ignored so a server broadcast cannot affect every open IDE. If supplied, `target.projectId` or `target.projectBasePath` must select the exact project.

Resolve relative file paths inside the selected project root. Reject traversal and absolute paths outside all open project roots. Do not weaken this restriction without an explicit security requirement.

When choosing an active editor, prefer the editor/project resolved from the actual focus component DataContext. Only fall back to selected editors when the focus context has no editor.

## Focus and editor data

Keep actual GUI focus separate from the active code editor. For example, after Ctrl+F:

- `focus` describes the Find text field and its input caret.
- `activeEditor` still describes the source file, code caret, and source selection.

Editor payloads should continue to expose:

- Absolute path and project-relative path
- Project name, `projectId`, and project root (`basePath`)
- Offset, zero-based line/column, one-based line/column, visual position, and screen coordinates
- Primary selection and all carets/selections
- Optional bounded text context rather than the whole source file

Password fields must always be redacted even when text sharing is enabled. Input/source text sharing must remain configurable.

Use stable IntelliJ Platform and standard Swing/AWT APIs. Avoid relying on version-specific Find UI implementation classes; identify generic text inputs first and treat semantic classification as best effort.

## Windows native window handles

On Windows, `bridge.hello` includes the IDE process ID and visible top-level native HWNDs. Handles are sent only in the handshake, not every state message. Send them as unsigned decimal strings plus optional hexadecimal diagnostics to avoid JavaScript integer precision loss. One IDE process may own multiple project windows, so preserve the complete window list and the initial active handle.

Do not add `require-restart="true"` unless a future change truly requires it. The current plugin should remain dynamically unloadable: listeners are registered with a plugin `Disposable`, AWT listeners are removed, executors are stopped, and sockets are closed during disposal.

## Build and verification

### Required package-build procedure

This section is mandatory for any agent asked to generate the installable plugin package. Do not stop after `compileJava`, `jar`, or an IDE project build: the requested installable artifact is the ZIP produced by the Gradle `buildPlugin` task.

The preferred and environment-independent command on this Windows workspace is:

```powershell
.\build-plugin.cmd
```

The script automatically:

1. Reads the current `pluginVersion`.
2. Uses `JAVA_HOME` when valid; otherwise locates the newest `C:\Program Files\JetBrains\*\jbr`.
3. Detects the actual Java major version and passes `-PjavaToolchainVersion=<major>`.
4. Prefers the already extracted `.gradle-dist\gradle-9.0.0\bin\gradle.bat` to avoid Gradle Wrapper download timeouts.
5. Falls back to `gradlew.bat` only when the local Gradle distribution is absent.
6. Runs `buildPlugin` and fails if the expected ZIP is missing.
7. Prints the absolute artifact path and size.

The successful package must be located at:

```text
D:\v\idea-input\build\distributions\fast-idea-bridge-<pluginVersion>.zip
```

For a release-quality local build, run:

```powershell
.\build-plugin.cmd -Verify
```

This additionally runs `verifyPluginProjectConfiguration` and `verifyPluginStructure`.

Do not assume `java` or `gradle` is available on `PATH`. On this machine it may not be. PowerShell execution policy can also reject direct `.ps1` invocation, so use `build-plugin.cmd`; it applies `-ExecutionPolicy Bypass` only to this script process and does not modify system policy. Do not blindly invoke `gradlew.bat` when `.gradle-dist` exists: the wrapper can attempt to download Gradle and fail with a 10-second network timeout. The standalone script handles both conditions.

Manual fallback with the currently installed JetBrains Runtime is:

```powershell
$env:JAVA_HOME = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.0.1\jbr'
& '.\.gradle-dist\gradle-9.0.0\bin\gradle.bat' buildPlugin -PjavaToolchainVersion=25
```

Although JBR 25 drives Gradle in that example, `build.gradle.kts` still emits Java 17 bytecode through `options.release = 17`.

On a normal machine with JDK 17 and Gradle Wrapper connectivity, this shorter command also works:

```powershell
.\gradlew.bat buildPlugin
```

If only a newer local JetBrains Runtime is installed, override the build toolchain while retaining `options.release = 17`:

```powershell
.\gradlew.bat buildPlugin -PjavaToolchainVersion=25
```

Before handing off a release, run at least (prefer `build-plugin.cmd -Verify`):

```powershell
.\gradlew.bat buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
```

The distribution ZIP is written to `build/distributions`. Keep the build verification matrix covering 2024.1, 2024.3, 2025.x, and a current/recommended IDE. Do not add an `until-build` without a concrete incompatibility reason.

## Local Nginx repository

An existing Docker Nginx container serves host directory:

```text
D:\docker\www -> /www
host port 8800 -> container port 80
```

Published repository URLs:

```text
http://127.0.0.1:8800/idea-plugins/updatePlugins.xml
http://127.0.0.1:8800/idea-plugins/fast-idea-bridge-<version>.zip
```

For local-only use, HTTP on `127.0.0.1` is acceptable. The current Docker mapping may listen on `0.0.0.0`; prefer binding it to `127.0.0.1` if the repository must not be reachable from the LAN.

Publish a new update with:

```powershell
.\publish-local-plugin.cmd
```

The script must:

1. Increment the patch version.
2. Restore the original version if publishing fails.
3. Build the plugin.
4. Copy the ZIP to `D:\docker\www\idea-plugins`.
5. Atomically replace `updatePlugins.xml`.
6. Verify the repository URL returns HTTP 200.

Use `-NoVersionBump` only to retry a version already incremented after an interrupted/manual workflow. IDEA will not detect an update if the repository version does not increase.

For a first installation, close all IDEA processes and run:

```powershell
.\install-local-plugin.cmd
```

The install script registers `idea.plugin.hosts`, downloads from Nginx, backs up the prior plugin outside the active `plugins` directory, installs the new archive, verifies the JAR version, and rolls back on failure. Write `idea.plugin.hosts` as an unquoted URL/property value; surrounding quotes are treated as literal URI characters by IDEA and cause `URISyntaxException`. Never leave a backup containing the same plugin ID inside IDEA's active `plugins` directory.

After first installation, the user must enable **Settings | Plugins | Update Plugins Automatically** once. Subsequent releases are published through the script and discovered through the local repository.

## Change discipline

- Preserve existing user settings when adding fields; provide defaults for state loaded from older plugin versions.
- Keep protocol additions backward compatible when practical and increment the protocol version only for a breaking change.
- Do not send full documents by default or log source text, tokens, passwords, or bearer credentials.
- Limit inbound WebSocket messages and validate JSON before dispatch.
- Keep UI reads on EDT and avoid blocking callbacks.
- Rebuild and update protocol/docs after externally visible behavior changes.
- Do not manually overwrite a running IDEA plugin. Close IDEA or use supported dynamic update mechanisms.
