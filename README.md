# Fast Idea Bridge

IntelliJ Platform plugin that connects IDE focus/editor state with the Fast application through an existing Node.js WebSocket server and accepts navigation commands from Fast.

## Defaults

- URL: `ws://127.0.0.1:19900`
- Settings: **Settings | Tools | Fast Idea Bridge**
- Minimum platform: IntelliJ Platform 2024.1 (`241`)
- The plugin uses only the common Platform module, so it can run in IDEA and other JetBrains IDEs.
- Local connection health: sends a WebSocket Ping every 2 seconds, treats 6 seconds without a Pong as disconnected, uses a 1-second handshake timeout, and reconnects with 200/500/1000/2000-ms backoff (then every 2 seconds).

## Instance and project identity

Every outgoing message contains a top-level `source` object:

```json
{
  "source": {
    "installationId": "stable-for-this-plugin-installation",
    "instanceId": "unique-for-this-IDE-process",
    "connectionId": "changes-after-each-reconnect",
    "projectId": "derived-from-the-normalized-project-root",
    "projectName": "idea-input",
    "projectBasePath": "D:\\v\\idea-input",
    "projects": []
  }
}
```

This prevents multiple IDE processes and multiple projects from overwriting each other on the Node.js side. Store sockets by `source.instanceId`, not in one global socket variable.

On Windows, the initial `bridge.hello` additionally contains the process ID and native top-level window handles. Handles are sent only during the handshake, not with every state event:

```json
{
  "processId": 12345,
  "nativeWindows": {
    "supported": true,
    "activeWindowHandle": "132844",
    "windows": [
      {
        "handle": "132844",
        "handleHex": "0x206EC",
        "active": true,
        "title": "idea-input – IntelliJ IDEA"
      }
    ]
  }
}
```

The handle uses an unsigned decimal string to avoid JavaScript integer precision loss. A single IDE process can own multiple top-level windows, so consumers should retain the complete `windows` array and use `activeWindowHandle` as the initial preferred window.

Commands may target one exact IDE and project:

```json
{
  "id":"2",
  "type":"command.navigate",
  "target":{"instanceId":"...", "projectId":"..."},
  "payload":{"path":"src/main/java/example/App.java", "line":25, "column":8}
}
```

Navigation, selection, and focus commands require `target.instanceId`. Commands addressed to another process are silently ignored, which makes broadcasting safe. A supplied `projectId` or `projectBasePath` must match the project containing the file.

## Events

The plugin sends `bridge.hello`, `focus.changed`, `editor.state`, `editor.caretChanged`, and `editor.documentChanged`. Every message has `version`, `id`, `type`, `timestamp`, `sequence`, and `payload`.

`payload.focus` describes the actual Swing focus owner. `payload.activeEditor` independently describes the selected code editor, file, caret, selections, and optional text context.

## Commands

```json
{"id":"1","type":"command.getState","payload":{}}
```

```json
{
  "id":"2",
  "type":"command.navigate",
  "target":{"instanceId":"INSTANCE_FROM_BRIDGE_HELLO", "projectId":"PROJECT_FROM_BRIDGE_HELLO"},
  "payload": {
    "path":"src/main/java/example/App.java",
    "line":25,
    "column":8,
    "coordinateBase":0,
    "requestFocus":true
  }
}
```

```json
{
  "id":"3",
  "type":"command.select",
  "target":{"instanceId":"INSTANCE_FROM_BRIDGE_HELLO", "projectId":"PROJECT_FROM_BRIDGE_HELLO"},
  "payload": {
    "path":"src/main/java/example/App.java",
    "start":{"line":25,"column":8},
    "end":{"line":25,"column":20},
    "coordinateBase":0
  }
}
```

```json
{
  "id":"4",
  "type":"command.find",
  "target":{"instanceId":"INSTANCE_FROM_BRIDGE_HELLO", "projectId":"PROJECT_FROM_BRIDGE_HELLO"},
  "payload": {
    "path":"src/main/java/example/App.java",
    "query":"FocusBridge",
    "caseSensitive":false,
    "regex":false,
    "select":false
  }
}
```

`command.find` searches from the beginning of the file by default, opens the file if needed, and moves the caret to the first match. If `path` is omitted, it searches the selected editor for the targeted project. Set `caseSensitive` to `true` for case-sensitive matching, or `regex` to `true` for regular-expression matching. `select` selects the match instead of only moving the caret. `startOffset` can optionally specify the zero-based search start offset.

For safety, commands can only open files contained by an open project. Password fields are always redacted.

## Build

Preferred Windows build command (auto-detects the JetBrains Runtime and local Gradle):

```powershell
.\build-plugin.cmd -Verify
```

Or use JDK 17 and Gradle 9 directly:

```powershell
gradle buildPlugin
```

Run `verifyPlugin` before distributing a release.

## Local Nginx publishing

See [LOCAL_REPOSITORY.md](LOCAL_REPOSITORY.md). Run `publish-local-plugin.cmd` to bump the patch version, build, publish to the existing local Nginx container, generate `updatePlugins.xml`, and verify the repository URL.
