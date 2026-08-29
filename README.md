# Fast Idea Bridge

IntelliJ Platform plugin that connects IDE focus/editor state with the Fast application through an existing Node.js WebSocket server and accepts navigation commands from Fast.

## Defaults

- URL: `ws://127.0.0.1:19900`
- Settings: **Settings | Tools | Fast Idea Bridge**
- Minimum platform: IntelliJ Platform 2024.1 (`241`)
- The plugin uses only the common Platform module, so it can run in IDEA and other JetBrains IDEs.
- Local connection health: sends a WebSocket Ping every 2 seconds, treats 6 seconds without a Pong as disconnected, uses a 1-second handshake timeout, and reconnects with 200/500/1000/2000-ms backoff (then every 2 seconds).

`command.getSelectedText` returns a changed selection immediately. If it matches the previous active read, the plugin polls every 30 ms, returns as soon as the value changes, and otherwise returns the latest value after 200 ms.

## 日志

插件使用 IntelliJ 的 `Logger` 写入宿主 `idea.log`；发生连接失败、心跳超时或发送失败时会记录简短告警。也会在 IDEA 的标准日志目录下写入 `fast-idea-bridge/fast-idea-bridge-YYYY-MM-DD.log`，仅保留最近 3 天的日志。可通过 **Help | Show Log in Explorer** 打开日志目录。

## Instance and project identity

Every outgoing message contains a top-level `source` object:

```json
{
  "source": {
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
    "activeWindowHandle": "132844",
    "windows": [
      {
        "handle": "132844"
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

Navigation, selection, focus, and full-screen commands require `target.instanceId`. Commands addressed to another process are silently ignored, which makes broadcasting safe. A supplied `projectId` or `projectBasePath` must match the target project.

## Events

The plugin sends `bridge.hello`, `focus.changed`, `editor.state`, and `editor.statusChanged`. `editor.statusChanged` is emitted only when inputability or the current object information changes; caret and document changes are no longer sent as high-frequency events. Every message has `version`, `id`, `type`, `timestamp`, `sequence`, and `payload`.

`payload.focus` describes the actual Swing focus owner. `payload.activeEditor` independently describes the selected code editor, file, caret, selections, and optional text context. It also includes the generic `context` and optional `expressionContext` (expression, kind, type, method return types, parameters, and at most 15 members). `editor.statusChanged.payload.inputable` is the current inputability and `editor.statusChanged.payload.objectInfo` contains the reduced object context. `payload.fullScreen` is the current IDE full-screen state. `payload.editorOpenedAt` is the Unix millisecond timestamp when the current editor became active. The handshake payload also includes these full-screen decision fields; the Fast service only evaluates automatic full-screen on `bridge.hello`.

## Commands

三个插件都支持通过 `command.getInputable`、`command.isEditorFocused`、`command.getContext`、`command.getObjectInfo` 和 `command.getSelectedText` 主动读取当前状态；Fast 端快捷调用为 `await f.editorBridge.getCurrentInputable()`、`await f.editorBridge.isEditorFocused()`、`await f.editorBridge.getCurrentContext()`、`await f.editorBridge.getCurrentObjectInfo()` 和 `await f.editorBridge.getCurrentSelectedText()`。`isEditorFocused` 仅在文本编辑器实际拥有键盘焦点时返回 `true`；`getContext` 返回 `singleQuote`、`doubleQuote`、`backtick`、`parentheses`、`braces` 或 `none`；没有可用编辑器时返回 `null`。

全屏状态可以通过 `command.isFullScreen` 查询，返回 `data.fullScreen`。自动全屏会在握手后查询，确认未全屏才发送 `command.fullScreen`，切换后再次查询确认，并在窗口尚未就绪时重试。

```json
{"id":"1","type":"command.getState","payload":{}}
```

To make the target IDE window full screen, send an idempotent command:

```json
{"id":"fullscreen-1","type":"command.fullScreen","target":{"instanceId":"INSTANCE_FROM_BRIDGE_HELLO"},"payload":{}}
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

`command.queryPythonPackages` is an on-demand batch query. Its `payload.packages` list accepts import names or `{ "importName", "distributionName" }` objects and returns only the requested packages from the targeted project's configured interpreter, including `interpreter`, `version`, and `environmentType` when available.

当 IDEA 的 Project View 获得焦点且选中一个或多个文件/目录时，`command.copyResourcePath` 在 `data.paths` 返回全部绝对路径并设置 `data.handled: true`；单选时还保留兼容字段 `data.path`。其他焦点状态或没有选中资源时返回 `data.handled: false`。

本地开发模式通过 `idea.properties` 中的 `fast.idea.bridge.dev=true` 显式开启；`install-local-plugin.cmd` 会自动写入。仅在开发模式下，插件会在启动 10 秒后检查本机自定义插件仓库，之后每 10 秒检查一次。只要本机版本仍低于仓库版本，就会每 10 秒允许重新触发下载，以便从临时失败中恢复。发现新版后使用 IntelliJ 接口下载、校验并登记安装；当 `fast.idea.bridge.autoRestart=true` 时，弹窗询问是否立即重启。选择“稍后”会持久记录该版本，不再提示相同版本；发现更高版本时仍会提示。只有用户确认后才重启 IDEA、应用新版并恢复窗口。两个开关默认均为 `false`，从官方仓库安装时不会启用本地轮询或重启提示。

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
