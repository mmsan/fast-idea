# FastGoTo Bridge

FastGoTo 的 JetBrains IDE 配套插件. 它将当前项目、活动文件、编辑器焦点和光标状态连接到 FastGoTo, 让你通过 FastGoTo 定位代码、查找文本、操作选区和输入内容.

## 使用前准备

- IntelliJ Platform 2024.1 或更高版本的 IDE.
- 在本机运行 FastGoTo 及其本地连接服务. 插件默认连接 `ws://127.0.0.1:9072`.

插件需要 FastGoTo 配合使用. 安装插件本身不会启动 FastGoTo 或本地服务.

本插件免费, FastGoTo 提供免费功能及可选的 VIP 订阅.

## 安装与连接

1. 在 **Settings | Plugins | Marketplace** 中搜索 **FastGoTo Bridge**. 如果尚未找到, 使用发布方提供的插件 ZIP: 打开齿轮菜单, 选择 **Install Plugin from Disk...**, 选中 ZIP, 按 IDE 提示完成安装和重启.
2. 启动本机 FastGoTo 和连接服务, 打开项目与代码文件.
3. 插件默认自动连接. 如需调整, 打开 **Settings | Tools | Fast Idea Bridge** 并保存设置.

| 设置 | 默认值与用途 |
| --- | --- |
| Host | `127.0.0.1`, 本机服务地址 |
| Port | `9072`, 服务端口 |
| WebSocket path | 空, 按服务配置填写 |
| Bearer token | 空, 服务启用认证时填写对应 Token |
| Connect automatically | 开启, 自动连接并在断线后重连 |
| Send input, selection and source context text | 开启, 允许向连接的服务发送文本 |
| Editor context characters | `200`, 源码上下文字符数, 可设置为 `0–5000` |

## 可以做什么

通过 FastGoTo 定位文件和代码行、查找文本、读取选区、移动光标、调整选区、输入或替换文本, 以及执行 IDE 操作和控制全屏. 具体入口由 FastGoTo 提供.

插件按 IDE 实例和项目定位操作. 文件导航限制在已打开项目内. 查找、输入和选区操作可能移动光标或修改当前文档, 使用前确认目标编辑器.

## 文本与隐私

默认连接本机服务. 开启文本共享时, 插件可发送输入控件文本、选中文本和有限源码上下文; 密码输入框内容会被隐藏.

关闭 **Send input, selection and source context text** 后, 插件仍提供项目、文件、光标和选区范围等结构信息, 依赖文本读取的功能会受限. 此开关控制文本共享, 不会禁用 FastGoTo 发来的编辑操作.

## 常见问题

- **没有连接**: 确认 FastGoTo 的本地服务已启动, 主机、端口、路径与 Token 一致, 并已启用 **Connect automatically**.
- **读取不到选区或源码**: 确认已打开代码编辑器, 并允许文本共享.
- **操作没有反应**: 切换到目标项目和编辑器, 再从 FastGoTo 重试. 其他工具窗口的焦点与代码编辑器焦点不同.
- **查看日志**: 使用 **Help | Show Log in Explorer** 打开 IDE 日志目录. 除 `idea.log` 外, 插件日志位于 `fast-idea-bridge` 子目录, 保留最近 3 天, 不记录源码、选区内容或 Token.

Marketplace 安装的插件通过 IDE 管理更新. 本地开发渠道有独立更新设置, 普通使用无需开启.

## 许可证

本插件采用 [MIT 许可证](LICENSE).

源码与问题反馈: [mmsan/fast-idea](https://github.com/mmsan/fast-idea).
