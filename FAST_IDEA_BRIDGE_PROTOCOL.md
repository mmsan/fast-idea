# Fast Idea Bridge 消息协议

本文档描述 Fast Idea Bridge 插件与 Fast 应用的 Node.js WebSocket 服务之间的消息协议。

默认连接地址：`ws://127.0.0.1:19900`

插件发送的消息是 JSON 文本。除特别说明外，所有消息都使用下面的统一结构：

```json
{
  "version": 1,
  "id": "消息 UUID",
  "type": "focus.changed",
  "timestamp": 1710000000000,
  "sequence": 12,
  "payload": {},
  "source": {}
}
```

## 一、统一顶层字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `version` | number | 协议版本，当前为 `1`。 |
| `id` | string | 当前消息的 UUID。 |
| `replyTo` | string，可选 | 响应某条 Node.js 命令时，值为对应命令的 `id`。普通事件没有此字段。 |
| `type` | string | 消息类型，见后文。 |
| `timestamp` | number | Unix 时间戳，单位为毫秒。 |
| `sequence` | number | 当前 IDEA 进程内的递增序号；插件重启后重新计数。 |
| `payload` | object | 消息正文。不同消息类型的结构不同。 |
| `source` | object | 发送该消息的 IDEA 实例和项目身份。 |

未设置的 Java `null` 字段不会被 Gson 序列化，因此部分可选字段可能直接不存在，而不是值为 `null`。

## 二、`source` 身份字段

```json
{
  "instanceId": "IDEA 进程 ID",
  "connectionId": "本次 WebSocket 连接 ID",
  "projectId": "当前项目 ID",
  "projectName": "idea-input",
  "projectBasePath": "D:/v/idea-input",
  "projects": []
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `instanceId` | string | 当前 IDEA 进程的 ID。多个 IDEA 进程不会共用此值。 |
| `connectionId` | string | 每次 WebSocket 重连都会变化。 |
| `projectId` | string，可选 | 当前编辑器所属项目的 ID。 |
| `projectName` | string，可选 | 当前编辑器所属项目名称。 |
| `projectBasePath` | string，可选 | 当前编辑器所属项目根目录。 |
| `projects` | array | 当前 IDEA 进程打开的全部项目。 |

`projects` 中的每个项目对象如下：

```json
{
  "projectId": "项目 UUID",
  "name": "idea-input",
  "basePath": "D:/v/idea-input"
}
```

`projectId` 根据规范化后的项目根目录生成，同一项目通常保持稳定。

## 三、插件发出的事件

### 3.1 `bridge.hello`

WebSocket 连接建立后发送一次，用于识别这是 Fast Idea Bridge 插件，并提供客户端能力和窗口信息。

```json
{
  "type": "bridge.hello",
  "payload": {
    "client": "fast-idea-bridge",
    "instanceId": "IDEA 进程 ID",
    "connectionId": "连接 ID",
    "projects": [],
    "processId": 12345,
    "nativeWindows": {},
    "capabilities": [
      "state",
      "getSelectedText",
      "getCurrentLineContent",
      "home",
      "shiftDown",
      "shiftUp",
      "moveUpIfLastLine",
      "navigate",
      "select",
      "find",
      "requestFocus",
      "fullScreen",
      "copyResourcePath",
      "listProjects"
    ]
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `client` | string | 固定为 `fast-idea-bridge`。Node.js 服务可以用它识别此插件。 |
| `instanceId` | string | IDEA 进程 ID。 |
| `connectionId` | string | 当前连接 ID。 |
| `projects` | array | 当前打开的项目列表。项目字段见 `source.projects`。 |
| `processId` | number | IDEA 操作系统进程 ID。 |
| `nativeWindows` | object | 当前进程的顶层原生窗口信息。 |
| `capabilities` | string[] | 插件支持的命令能力。 |

`nativeWindows` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `activeWindowHandle` | string，可选 | 当前活动窗口句柄，使用无符号十进制字符串。 |
| `windows` | array | 当前进程的顶层窗口列表。 |

窗口对象字段：

```json
{
  "handle": "132844"
}
```

句柄使用字符串是为了避免 JavaScript 的整数精度问题。

### 3.2 `focus.changed`

IDEA Swing 焦点变化、键盘输入或鼠标输入后发送。消息正文是完整焦点快照：

```json
{
  "type": "focus.changed",
  "payload": {
    "focus": {},
    "activeEditor": {}
  }
}
```

### 3.3 `editor.state`

连接建立后发送一次，用于提供初始编辑器状态。后续如果编辑器状态发生变化，也可能再次发送。

其 `payload` 与 `focus.changed` 相同，包含 `focus` 和 `activeEditor`。

### 3.4 `editor.statusChanged`

输入能力或当前对象信息发生变化后发送一次。光标和文档变化不会再直接发送高频事件；插件只在对象信息签名或 `inputable` 值变化时发送该状态消息。

```json
{
  "type": "editor.statusChanged",
  "payload": {
    "inputable": true,
    "objectInfo": {
      "context": "none",
      "expressionContext": {
        "expression": "response.data.user",
        "kind": "property",
        "type": "com.example.User",
        "members": []
      }
    },
    "focus": {},
    "activeEditor": {}
  }
}
```

`objectInfo` 是精简对象信息；需要实时读取时可以使用 `command.getObjectInfo`。需要实时读取输入能力时可以使用 `command.getInputable`，读取光标语法上下文时可以使用 `command.getContext`。

## 四、快照 `payload` 字段

### 4.0 `payload.fullScreen`

`payload.fullScreen` 是布尔值，表示当前 IDEA 项目窗口是否处于全屏状态。总服务收到状态事件后，如果该值不是 `true`，可以发送 `command.fullScreen`；插件端会幂等地设置为全屏，不会反复切换。

### 4.0a `payload.editorOpenedAt`

### 4.0b `command.isFullScreen` 返回值

`command.isFullScreen` 会在目标项目窗口所在的 Swing UI 线程重新读取全屏状态，返回 `data.fullScreen`。状态暂不可用时返回错误，而不是把未知状态当作未全屏。

`payload.editorOpenedAt` 是当前编辑器或活动文件首次成为当前编辑器的 Unix 毫秒时间戳。`bridge.hello.payload` 也包含这两个全屏判断字段以及 `activeProject`，总服务只在握手时执行一次自动全屏判断；打开时间超过 30 秒或缺少字段时跳过，避免 Socket 重连时覆盖用户手动调整的窗口大小。后续选中文件、光标和文档事件不会触发自动全屏。

### 4.1 `payload.focus`

描述 IDEA 当前实际拥有 Swing 焦点的组件。

没有焦点组件时：

```json
{
  "type": "none"
}
```

有焦点组件时的字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | string | `textInput`、`editor` 或 `component`。 |
| `className` | string | Java Swing 组件类名。 |
| `name` | string，可选 | 组件名称。 |
| `enabled` | boolean | 组件是否启用。 |
| `visible` | boolean | 组件是否可见。 |
| `showing` | boolean | 组件是否正在显示。 |
| `bounds` | object | 组件屏幕坐标和尺寸。 |
| `hierarchy` | string[] | 从当前组件到父组件的 Java 类名层级，最多 16 层。 |
| `accessibleName` | string，可选 | 无障碍名称。 |
| `accessibleDescription` | string，可选 | 无障碍描述。 |
| `accessibleRole` | string，可选 | 无障碍角色。 |
| `window` | object，可选 | 所属顶层窗口信息。 |
| `input` | object，可选 | 当 `type` 为 `textInput` 时存在。 |

`bounds` 字段：

```json
{
  "screenX": 100,
  "screenY": 200,
  "width": 500,
  "height": 30
}
```

如果组件暂时无法转换为屏幕坐标，则只保证有 `width` 和 `height`。

### 4.2 `payload.focus.input`

当焦点位于普通文本输入框时存在：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `editable` | boolean | 是否可编辑。 |
| `caretOffset` | number | 光标字符偏移量。 |
| `selectionStart` | number | 选择起始偏移量。 |
| `selectionEnd` | number | 选择结束偏移量。 |
| `textLength` | number | 文本长度。 |
| `password` | boolean | 是否为密码输入框。 |
| `text` | string，可选 | 文本内容；需要开启 `sendText` 且不是密码框。 |
| `selectedText` | string，可选 | 选中文本；需要开启 `sendText` 且不是密码框。 |
| `textRedacted` | boolean，可选 | 文本被隐藏时为 `true`。 |
| `caret` | object，可选 | 光标屏幕坐标。 |

`input.caret` 字段：

```json
{
  "screenX": 100,
  "screenY": 200,
  "componentX": 42.0,
  "componentY": 3.0,
  "height": 18.0
}
```

密码输入框始终不会发送文本内容。

### 4.3 `payload.activeEditor`

没有可用编辑器时，`activeEditor` 为 `null` 或被省略。存在编辑器时包含：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `ownsFocus` | boolean | 当前实际焦点是否属于此编辑器。 |
| `editable` | boolean | 编辑器不是只读查看器，并且底层文档可写。 |
| `project` | object，可选 | 编辑器所属项目。 |
| `file` | object，可选 | 当前打开文件。 |
| `caret` | object | 主光标位置。 |
| `selection` | object | 主光标选择区域。 |
| `carets` | array | 所有光标，包括多光标。 |
| `textContext` | object，可选 | 光标附近文本，需要开启 `sendText`。 |

`activeEditor.project`：

```json
{
  "projectId": "项目 UUID",
  "name": "idea-input",
  "basePath": "D:/v/idea-input"
}
```

`activeEditor.file`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | string | 文件名。 |
| `path` | string | 文件绝对路径。 |
| `url` | string | IDEA VirtualFile URL。 |
| `writable` | boolean | 文件是否可写。 |
| `fileType` | string | IDEA 文件类型名称。 |
| `projectRelativePath` | string，可选 | 相对于项目根目录的路径。 |

`activeEditor.caret`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `offset` | number | 文档字符偏移量。 |
| `line` | number | 0-based 逻辑行号。 |
| `column` | number | 0-based 逻辑列号。 |
| `lineNumber` | number | 1-based 行号。 |
| `columnNumber` | number | 1-based 列号。 |
| `visualLine` | number | 0-based 视觉行号。 |
| `visualColumn` | number | 0-based 视觉列号。 |
| `screenX` | number | 光标屏幕 X 坐标。 |
| `screenY` | number | 光标屏幕 Y 坐标。 |

`activeEditor.selection`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `hasSelection` | boolean | 是否存在选择内容。 |
| `startOffset` | number | 选择起始字符偏移量。 |
| `endOffset` | number | 选择结束字符偏移量。 |
| `text` | string，可选 | 选中文本，需要开启 `sendText`。 |

`activeEditor.carets` 中每个元素：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `primary` | boolean | 是否为主光标。 |
| `offset` | number | 光标字符偏移量。 |
| `line` | number | 0-based 逻辑行号。 |
| `column` | number | 0-based 逻辑列号。 |
| `selectionStart` | number | 选择起始偏移量。 |
| `selectionEnd` | number | 选择结束偏移量。 |
| `selectedText` | string，可选 | 选中文本，需要开启 `sendText`。 |

`activeEditor.textContext`：

```json
{
  "currentLine": "当前整行文本",
  "beforeCaret": "光标前的上下文",
  "afterCaret": "光标后的上下文"
}
```

上下文长度由设置项 `contextCharacters` 控制，默认值为 `200`。

### 4.4 `payload.activeEditor` 编辑上下文

插件会在已有 `activeEditor` 快照中尽量附带通用的编辑上下文；底层使用当前编辑器可用的 PSI、符号或语言服务能力，无法可靠判断时省略具体字段，不猜测类型。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `context` | string | 光标最内层的主要语法上下文：`singleQuote`、`doubleQuote`、`backtick`、`braces`、`parentheses` 或 `none`。`braces` 只表示语义上的对象字面量大括号，不表示普通代码块。 |
| `expressionContext` | object，可选 | 光标关联的表达式、符号类型、可推断的完整类型名和最多 15 个成员。 |

`expressionContext` 的结构为：

```json
{
  "expression": "response.data.user",
  "kind": "property",
  "type": "com.example.user.User",
  "members": [
    {"name": "name", "kind": "property", "type": "java.lang.String"},
    {"name": "load", "kind": "method", "returnType": "java.lang.String"}
  ]
}
```

`type` 优先使用语言服务可唯一定位的完整类型名；无法可靠获得 package/namespace 时可以退化为简单类型名或省略。成员数组最多 15 项，方法可以附带 `parameters` 和 `returnType`。

## 五、Node.js 发给插件的命令

插件也会接收 Node.js 发来的命令。命令同样是 JSON 文本，至少包含 `id` 和 `type`：

```json
{
  "id": "command-1",
  "type": "command.getState",
  "payload": {}
}
```

### 5.1 `command.ping`

检查插件连接是否正常：

```json
{
  "id": "1",
  "type": "command.ping"
}
```

### 5.2 `command.getState`

请求当前焦点和编辑器快照：

```json
{
  "id": "2",
  "type": "command.getState",
  "payload": {}
}
```

### 5.2a `command.getInputable`

在插件 UI 线程实时读取当前焦点是否可以输入文本，返回 `data: true` 或 `data: false`，不依赖 Fast 端缓存。

### 5.2b `command.getObjectInfo`

在插件 UI 线程实时读取当前对象信息，返回 `data` 为 `objectInfo`，包括文件、语法上下文、表达式、对象类型、成员、方法参数和方法返回类型；无法解析时返回已有的表达式和 `kind`，没有当前编辑器时返回 `null`。

### 5.2b `command.isEditorFocused`

同步读取当前文本编辑器是否实际拥有键盘焦点，结果为布尔值 `data`。查找框等其他可编辑输入框拥有焦点时返回 `false`。

### 5.2c `command.getContext`

在插件 UI 线程实时读取当前主光标所在的语法上下文。成功响应的 `data` 为 `singleQuote`、`doubleQuote`、`backtick`、`parentheses`、`braces` 或 `none`；没有当前编辑器时为 `null`。

### 5.2d `command.getSelectedText`

主动在插件 UI 线程读取当前编辑器的主光标选中文本，不依赖 Fast 端缓存的编辑器快照。插件会记住上次主动读取的结果；新结果不同则立即返回，相同时每隔 30 毫秒复读一次，发现变化便提前返回，始终相同则在 200 毫秒时返回最新结果。成功响应的 `data` 是选中文本；没有选中内容时为空字符串；没有可用编辑器或关闭 `sendText` 时为 `null`（某些客户端可能省略 `data` 字段）。该只读命令不要求 `target.instanceId`，但 Fast 端快捷方法会自动补充当前实例和项目目标。

```json
{
  "id": "selected-text-1",
  "type": "command.getSelectedText",
  "target": {"instanceId": "bridge.hello 中的 instanceId"},
  "payload": {}
}
```

```json
{
  "type": "command.result",
  "replyTo": "selected-text-1",
  "payload": {
    "success": true,
    "command": "command.getSelectedText",
    "data": "selected source text"
  }
}
```

### 5.2e `command.getCurrentLineContent`

主动在插件 UI 线程读取当前获得编辑器焦点的代码行内容，不包含行尾换行符。成功响应的 `data` 是当前行文本；没有代码编辑器焦点、关闭 `sendText` 或没有可用编辑器时为 `null`。调用方如果要模拟真实 Ctrl+C 的整行结果，需要自行在该值后追加一个换行符。

```json
{
  "id": "current-line-1",
  "type": "command.getCurrentLineContent",
  "target": {"instanceId": "bridge.hello 中的 instanceId"},
  "payload": {}
}
```

### 5.3 `command.listProjects`

### 5.3a `command.isFullScreen`

查询目标项目窗口当前是否全屏。该命令可以携带 `target.instanceId` 以及项目身份字段；成功响应的 `data.fullScreen` 为布尔值。

```json
{
  "id": "fullscreen-state-1",
  "type": "command.isFullScreen",
  "target": {"instanceId": "bridge.hello 中的 instanceId"},
  "payload": {}
}
```

请求当前 IDEA 进程打开的项目列表：

```json
{
  "id": "3",
  "type": "command.listProjects"
}
```

### 5.4 `command.navigate`

打开文件并移动光标。插件会先通过 IntelliJ 的窗口管理器激活目标项目窗口，恢复最小化窗口并将其置前，然后再请求编辑器焦点。此命令必须提供目标 `instanceId`：

```json
{
  "id": "4",
  "type": "command.navigate",
  "target": {
    "instanceId": "bridge.hello 中的 instanceId",
    "projectId": "可选项目 ID"
  },
  "payload": {
    "path": "src/main/java/example/App.java",
    "line": 25,
    "column": 8,
    "coordinateBase": 0
  }
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `target.instanceId` | string | 必填；只允许操作指定 IDEA 进程。 |
| `target.projectId` | string，可选 | 限制到指定项目。 |
| `target.projectBasePath` | string，可选 | 也可以使用项目根目录限制目标。 |
| `payload.path` | string | 必填；支持项目内相对路径、Windows 盘符绝对路径、UNC 路径、正斜杠/反斜杠路径，以及 `file://` 文件 URI。 |
| `payload.line` | number，可选 | 行号，按 `coordinateBase` 解释；省略时保留当前/IDEA 记住的行。 |
| `payload.column` | number，可选 | 列号，按 `coordinateBase` 解释；省略时保留当前/IDEA 记住的列。 |
| `payload.coordinateBase` | number，可选 | `0` 表示 0-based，`1` 表示 1-based；默认 `0`。 |

### 5.5 `command.select`

打开文件并选择指定范围：

```json
{
  "id": "5",
  "type": "command.select",
  "target": {
    "instanceId": "bridge.hello 中的 instanceId",
    "projectId": "可选项目 ID"
  },
  "payload": {
    "path": "src/main/java/example/App.java",
    "start": {"line": 25, "column": 8},
    "end": {"line": 25, "column": 20},
    "coordinateBase": 0
  }
}
```

`start` 和 `end` 都必须包含 `line`、`column`。其他路径和目标字段与 `command.navigate` 相同。

### 5.6 `command.find`

Search a file and position the editor at the first match. If `payload.path` is omitted, the selected editor in the targeted project is searched.

```json
{
  "id": "6",
  "type": "command.find",
  "target": {
    "instanceId": "INSTANCE_FROM_BRIDGE_HELLO",
    "projectId": "PROJECT_FROM_BRIDGE_HELLO"
  },
  "payload": {
    "path": "src/main/java/example/App.java",
    "query": "FocusBridge",
    "caseSensitive": false,
    "regex": false,
    "select": false,
    "startOffset": 0
  }
}
```

| Field | Type | Description |
| --- | --- | --- |
| `target.instanceId` | string | Required target IDEA process. |
| `target.projectId` | string, optional | Restricts the search to a project. |
| `target.projectBasePath` | string, optional | Restricts the search to a project root. |
| `payload.path` | string, optional | Absolute path or project-relative path. Omit to use the selected editor. |
| `payload.query` | string | Required search string or regular expression. |
| `payload.caseSensitive` | boolean, optional | Case-sensitive search; defaults to `false`. |
| `payload.regex` | boolean, optional | Regular-expression search; defaults to `false`. |
| `payload.select` | boolean, optional | Select the match instead of only moving the caret; defaults to `false`. |
| `payload.startOffset` | number, optional | Zero-based search start offset; defaults to `0`. |

On success, the response contains `startOffset`, `endOffset`, zero-based `line` and `column`, one-based `lineNumber` and `columnNumber`, plus the effective search options. A missing match returns `NOT_FOUND`; an invalid regular expression returns `INVALID_QUERY`.

### 5.6a `command.deleteSelection`

在当前编辑器中删除选区，但仅当选区文本与 `payload.expectedText` 完全一致时执行。该命令必须提供 `target.instanceId`，不要求项目身份；选区不匹配时仍成功返回 `data.handled: true`、`data.matched: false`、`data.deleted: false`，调用方不应再强制删除。

```json
{
  "id": "delete-selection-1",
  "type": "command.deleteSelection",
  "target": {"instanceId": "bridge.hello 中的 instanceId"},
  "payload": {"expectedText": "[[end]]"}
}
```

### 5.7 `command.requestFocus`

请求 IDEA 当前编辑器所在窗口置前并获得焦点：

```json
{
  "id": "6",
  "type": "command.requestFocus",
  "target": {
    "instanceId": "bridge.hello 中的 instanceId"
  }
}
```

### 5.8 `command.copyResourcePath`

当 IDEA 的 Project View（项目资源管理器）获得焦点，并且选中一个或多个文件/目录时，在 `data.paths` 返回全部绝对路径。单选时额外返回兼容字段 `data.path`。非 Project View 焦点或没有选中文件/目录时，命令成功返回 `data.handled: false`，调用方应继续执行自己的后续逻辑。

```json
{
  "id": "7",
  "type": "command.copyResourcePath",
  "target": {
    "instanceId": "bridge.hello 中的 instanceId"
  },
  "payload": {}
}
```

成功处理时返回：

```json
{
  "success": true,
  "command": "command.copyResourcePath",
  "data": {
    "handled": true,
    "paths": [
      "D:/v/idea-input/src/main/java/example/App.java",
      "D:/v/idea-input/src/main/java/example/Other.java"
    ]
  }
}
```

### 5.9 `command.fullScreen`

将目标 IDEA 项目窗口设置为全屏。该命令必须提供 `target.instanceId`，并可提供 `target.projectId` 或 `target.projectBasePath` 精确选择项目。成功响应的 `data.fullScreen` 为 `true`；如果当前已经全屏，插件不会再次切换窗口状态。

```json
{
  "id": "5",
  "type": "command.fullScreen",
  "target": {"instanceId": "bridge.hello 中的 instanceId"},
  "payload": {}
}
```

### 5.10 `command.queryPythonPackages`

按需查询目标项目实际 Python Interpreter/Environment 中给定的 import 或 package 名称。该命令不会枚举环境中的全部包；一次请求可以传入多个名称。字符串名称默认同时作为 import 名和 distribution 名，也可以使用对象显式提供 `distributionName`，以支持 `sklearn` → `scikit-learn`、`cv2` → `opencv-python` 等映射。

```json
{
  "id": "python-1",
  "type": "command.queryPythonPackages",
  "target": {
    "instanceId": "bridge.hello 中的 instanceId",
    "projectId": "可选项目 ID"
  },
  "payload": {
    "packages": [
      "numpy",
      {"importName": "sklearn", "distributionName": "scikit-learn"},
      "torch"
    ]
  }
}
```

成功响应的 `data` 包含 `interpreter`、`version`、`environmentType`（如 `venv`、`conda`、`system`）以及按请求名称返回的 `packages` 映射；未安装的包只返回 `installed: false`。

### 5.11 `command.home`、`command.shiftDown`、`command.shiftUp`

三者都要求 `target.instanceId`，并且只操作当前编辑器的原生光标和选择：`home` 清除选择并把每个光标移动到逻辑行首；`shiftDown` 和 `shiftUp` 按逻辑文档行扩选。`payload.count` 可选，默认 `1`。软换行不会改变 `home` 的目标位置，也不会消耗上下移动次数。

### 5.12 `command.moveUpIfLastLine`

仅当目标编辑器主光标位于最后一个有内容的行（其后仅有空白行）时，清除该光标的选择并上移一行，保持列位置。不是该行或文档只有一行时，命令成功返回 `data.handled: false`，调用方应继续使用原有后备逻辑；无编辑器时返回 `NO_EDITOR`。

```json
{
  "id": "move-up-1",
  "type": "command.moveUpIfLastLine",
  "target": {
    "instanceId": "bridge.hello 中的 instanceId",
    "projectId": "可选项目 ID"
  },
  "payload": {}
}
```

## 六、命令响应

插件使用 `type: "command.result"` 返回命令结果，并通过 `replyTo` 对应原命令的 `id`。

成功响应：

```json
{
  "type": "command.result",
  "replyTo": "4",
  "payload": {
    "success": true,
    "command": "command.navigate",
    "data": {
      "path": "D:/v/idea-input/src/main/java/example/App.java",
      "line": 25,
      "column": 8
    }
  }
}
```

失败响应：

```json
{
  "type": "command.result",
  "replyTo": "4",
  "payload": {
    "success": false,
    "command": "command.navigate",
    "error": {
      "code": "NAVIGATION_FAILED",
      "message": "File was not found inside an open project"
    }
  }
}
```

常见错误码：

| 错误码 | 说明 |
| --- | --- |
| `UNKNOWN_COMMAND` | 不支持的命令类型。 |
| `INVALID_MESSAGE` | 收到的 JSON 或命令格式无效。 |
| `NAVIGATION_FAILED` | 打开文件、移动光标或选择文本失败。 |
| `FIND_FAILED` | 打开文件或定位查找结果失败。 |
| `NOT_FOUND` | 未找到匹配文本。 |
| `INVALID_QUERY` | 正则表达式无效。 |
| `NO_EDITOR` | `command.requestFocus` 执行时没有可用编辑器。 |

如果命令带有不属于当前 IDEA 进程的 `target.instanceId`，插件会直接忽略，不返回响应。

## 七、隐私和文本发送设置

插件设置中有两个与文本相关的选项：

| 设置 | 默认值 | 作用 |
| --- | --- | --- |
| `sendText` | `true` | 是否发送输入框文本、选中文本和编辑器上下文。 |
| `contextCharacters` | `200` | 光标前后最多收集的上下文字符数量。 |

以下内容始终不会发送密码值：

- `JPasswordField` 的输入内容；
- 密码输入框的选中文本。

关闭 `sendText` 后，位置、长度、选择范围等结构信息仍会发送，但文本值会被省略；普通文本输入框会额外带有 `textRedacted: true`。

## 八、Node.js 订阅示例

Node.js 服务端可以只订阅 Fast Idea Bridge 插件消息：

```js
const off = f.ideaFocusBridge.subscribe('focus.changed', (msg, ws, source) => {
  const editor = msg.payload.activeEditor
  console.log({
    instanceId: source.instanceId,
    projectId: source.projectId,
    file: editor?.file?.projectRelativePath,
    line: editor?.caret?.line,
    column: editor?.caret?.column,
  })
})

// 不再需要时取消订阅
off()
```

也可以只监听某个 IDEA 进程：

```js
f.ideaFocusBridge.subscribe(
  'focus.changed',
  handler,
  {instanceId: 'bridge.hello 中的 instanceId'}
)
```

## 九、按项目名定位 WebSocket 并发送命令

Node.js 服务端会在收到 `bridge.hello` 握手时，读取 `payload.projects`，把每个项目名直接缓存到对应的 WebSocket：

```js
f.ideaFocusBridge.wsByProjectName // Map<projectName, WebSocket>
```

后续 FastJS 可以直接取出 WebSocket 执行动作，不需要再次扫描客户端。IDEA 连接断开时，对应项目缓存会自动清理。

同名项目默认使用最后一次握手建立的连接；如果需要精确指定 IDEA 进程，使用 `instanceId`。

### 9.1 直接取得项目 WebSocket

```js
const ws = f.ideaFocusBridge.wsByProjectName.get('idea-input')

if (ws && ws.readyState === ws.OPEN) {
  ws.send(JSON.stringify({
    id: 'custom-command-1',
    type: 'command.requestFocus',
    target: {
      instanceId: 'bridge.hello 中的 instanceId',
    },
  }))
}
```

也可以使用辅助方法：

```js
const ws = f.ideaFocusBridge.getWs('idea-input')
```

### 9.2 查询项目对应的客户端

```js
const match = f.ideaFocusBridge.findByProjectName('idea-input')

if (match) {
  console.log(match.client.ws)
  console.log(match.client.source.instanceId)
  console.log(match.project.projectId)
}
```

返回值结构：

```js
{
  client: {
    ws,
    source,
    identity,
  },
  project: {
    projectId,
    name,
    basePath,
  },
}
```

找不到项目时返回 `null`。如果需要指定同名项目所在的 IDEA 实例：

```js
const match = f.ideaFocusBridge.findByProjectName('idea-input', {
  instanceId: 'bridge.hello 中的 instanceId',
})
```

### 9.3 按项目名发送任意命令

```js
const commandId = f.ideaFocusBridge.sendToProject('idea-input', {
  type: 'command.ping',
})
```

服务端会自动补充：

- `id`；
- `target.instanceId`；
- `target.projectId`；
- `target.projectBasePath`。

也可以发送完整的导航命令：

```js
f.ideaFocusBridge.sendToProject('idea-input', {
  type: 'command.navigate',
  payload: {
    path: 'src/main/java/com/mirror/ideafocusbridge/FocusBridgeService.java',
    line: 120,
    column: 8,
    coordinateBase: 0,
  },
})
```

### 9.4 直接打开文件并定位到指定行列

这是导航命令的快捷方法：

```js
f.ideaFocusBridge.openFile(
  'idea-input',
  'src/main/java/com/mirror/ideafocusbridge/FocusBridgeService.java',
  120,
  8,
)
```

参数依次为：

| 参数 | 说明 |
| --- | --- |
| `projectName` | IDEA 项目名称。 |
| `filePath` | 绝对路径，或相对于项目根目录的路径。 |
| `line` | 可选行号，默认保留当前/IDEA 记住的行。 |
| `column` | 可选列号，默认保留当前/IDEA 记住的列。 |
| `options.coordinateBase` | 传 `0` 可改为 0-based 行列号；传入行列时默认按 1-based 处理。 |
| `options.instanceId` | 同名项目存在多个实例时，用于指定 IDEA 进程。 |

`filePath` 支持以下形式：

```js
'src/main/java/example/App.java'
'src\\main\\java\\example\\App.java'
'D:/v/fastjs/src/main/java/example/App.java'
String.raw`D:\\v\\fast\\src\\main\\java\\example\\App.java`
'file:///D:/v/fastjs/src/main/java/example/App.java'
'\\\\server\\share\\project\\App.java'
```

在 JavaScript 字符串中使用 Windows 反斜杠时要写成 `\\`，或使用 `String.raw`；也可以直接使用正斜杠。

例如使用 1-based 行列号：

```js
f.ideaFocusBridge.openFile('idea-input', 'README.md', 10, 1, {
  coordinateBase: 1,
})
```

项目不存在、连接已关闭或项目名对应多个实例且未指定 `instanceId` 时，上述发送方法会抛出异常。

### 9.5 获取当前 IDEA 焦点项目和文件

Node.js 服务端还提供两个快捷查询方法。它们使用 `f.fast.currentWindow.hwnd` 与 IDEA 握手时上报的原生窗口句柄进行匹配：

```js
const projectName = f.ideaFocusBridge.getCurrentProjectName()
const filePath = f.ideaFocusBridge.getCurrentFilePath()
const line = f.ideaFocusBridge.getCurrentLine()
const selectedText = await f.ideaFocusBridge.getCurrentSelectedText()
```

只有当前 Windows 前台窗口属于某个已连接的 IDEA 实例时，方法才会通过 `command.getSelectedText` 主动读取插件当前值；否则返回 `null`。

- `getCurrentProjectName()`：返回当前 IDEA 编辑器所属项目名。
- `getCurrentFilePath()`：返回当前 IDEA 编辑器文件的绝对路径。
- `getCurrentLine()`：返回当前光标所在的 1-based 行号。
- `getCurrentSelectedText()`：返回一个 Promise，主动读取当前编辑器或当前获得焦点的可编辑文本控件中的选中文本；没有选择时返回空字符串。

如果 IDEA 未开启文本发送、当前没有可用编辑器/输入控件，或焦点位于密码输入框，`getCurrentSelectedText()` 返回 `null`。

上述四个快捷查询方法在当前窗口属于 IDEA/CLion、但没有连接 Fast Idea Bridge 时，会通过 `f.log(..., true)` 提示当前 IDE 窗口可能没有安装或启用 `Fast Idea Bridge` 插件；当前窗口不是 IDEA/CLion 时不会提示。提示采用 3 秒防抖。

### 9.6 通用当前编辑器入口

为了让 FastJS 不必根据进程名分别选择 IDEA、Visual Studio 或 VS Code 的 bridge，Fast 还提供统一入口 `f.editorBridge`。原有的 `f.ideaFocusBridge`、`f.visualStudioBridge` 和 `f.vscodeBridge` 保留，仍可用于明确指定某一个插件或项目。

统一入口始终根据 `f.fast.currentWindow` 选择当前激活的编辑器窗口：

```js
const editor = f.editorBridge

editor.getCurrentPlatform()       // 'idea'、'visualStudio'、'vscode' 或 null
editor.getCurrentProjectName()
editor.getCurrentProjectBasePath()
editor.getCurrentFilePath()
await editor.isLive()
editor.isEditorFocused()
editor.getCurrentLine()
await editor.getCurrentLineContent()
await editor.getCurrentSelectedText()
editor.getCurrentEditor()
editor.getCurrentInfo()           // 一次返回当前编辑器的常用信息
```

也可以直接对当前编辑器所属项目执行通用操作：

```js
editor.openFile('README.md', 10, 1)
editor.find('FocusBridge')
await editor.copySelectedResourcePath()
```

`openFile()` 和 `find()` 默认使用当前编辑器项目，也可以通过 `options.projectName` 覆盖项目名。`subscribe()` 会同时订阅三个插件的同名事件，并返回取消订阅函数：

`isLive()` 返回 `Promise<boolean>`，通过现有的 `command.ping` 验证当前原生 bridge 是否仍能响应。首次检测最多等待 4 秒，成功状态缓存 5 秒，重复调用会复用该状态；首次检测失败后，每个原生 bridge 最多保留一个每秒重试任务，任务在检测成功或 60 秒后自动结束。WebSocket 关闭时缓存状态立即失效。

`isEditorFocused()` 返回 `boolean`，读取 bridge 最近一次状态中的焦点信息。只有焦点类型为编辑器且编辑器声明拥有焦点时才返回 `true`；资源管理器和搜索框等其他控件返回 `false`。

```js
const off = editor.subscribe('focus.changed', (msg, ws, source) => {
  console.log(editor.getCurrentInfo())
})
off()
```

需要调用当前插件尚未被统一包装的方法时，可以取得原始 bridge，或使用 `invokeCurrent()`：

```js
const bridge = editor.getCurrentBridge()
bridge?.getInputStates?.()
editor.invokeCurrent('getInputStates')
```
