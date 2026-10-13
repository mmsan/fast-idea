# 本地插件仓库

现有 Docker Nginx 将宿主机 `D:\docker\www` 映射为容器 `/www`，并通过宿主机端口 `8800` 提供静态文件。

## 发布新版本

```powershell
.\publish-local-plugin.cmd
```

该命令会自动：

1. 将 `gradle.properties` 中的补丁版本号加一。
2. 构建插件 ZIP。
3. 将 ZIP 发布到 `D:\docker\www\idea-plugins`。
4. 原子更新 `updatePlugins.xml`。
5. 通过 Nginx URL 检查仓库是否可访问。

如需不升版本重新发布（仅用于检查构建，IDEA 不会将其识别为更新）：

```powershell
.\publish-local-plugin.cmd -NoVersionBump
```

## 首次安装

关闭所有 IDEA 窗口，然后运行：

```powershell
.\install-local-plugin.cmd
```

安装脚本还会将仓库 URL 合并写入最新 IDEA 配置目录的 `idea.plugin.hosts`，不会覆盖已有的其他自定义仓库。

仓库地址：

```text
http://127.0.0.1:8800/idea-plugins/updatePlugins.xml
```

## 发布校验

发布到 JetBrains、Visual Studio 和 VS Code 官方商店请使用 [统一商店发布入口](MARKETPLACE_RELEASE.md). 本文的本地更新源流程独立保留.

发布前运行 `.\build-plugin.cmd -Verify` 检查插件包结构, 并使用 JDK 17 和 Gradle 9 运行 `gradle verifyPlugin` 完成 IDE 兼容性校验. `-Verify` 不包含 `verifyPlugin`.

发布脚本会先检查新版本 ZIP 地址是否返回 HTTP 200 且内容长度非零，再切换 `updatePlugins.xml`。切换后还会重新读取 XML，确认版本号和 ZIP 地址一致；如果后续检查失败，会恢复原来的 XML，并清理本次新发布的 ZIP。这样可以避免 IDEA 在更新检查时读到已经发布但暂时下载不到的 ZIP 地址。

首次安装后，在 IDEA 的 **Settings | Plugins | 齿轮菜单** 中启用 **Update Plugins Automatically**。以后只需执行发布脚本，IDEA 会从本地仓库发现版本更高的更新。

本地安装脚本会在 `idea.properties` 写入 `fast.idea.bridge.dev=true` 和 `fast.idea.bridge.autoRestart=true`。仅在开发模式开关显式开启时，插件才会在启动 10 秒后主动读取本机仓库元数据，之后每 10 秒检查一次；只要本机版本仍然较低，同一仓库版本也会每 10 秒允许重新触发下载，以便从临时失败中恢复。发现新版后通过 IntelliJ 平台接口下载、校验并登记安装；重启提示开关开启时，IDEA 弹窗询问是否立即重启，只有用户确认后才应用新版并恢复窗口。轮询只接受 `127.0.0.1`、`localhost` 或 `::1` 的 HTTP(S) 仓库地址。两个开关默认关闭，因此未来从官方仓库安装的版本不会使用本地轮询或重启提示。
