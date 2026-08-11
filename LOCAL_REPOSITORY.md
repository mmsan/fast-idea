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

首次安装后，在 IDEA 的 **Settings | Plugins | 齿轮菜单** 中启用 **Update Plugins Automatically**。以后只需执行发布脚本，IDEA 会从本地仓库发现版本更高的更新。
