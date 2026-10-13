# 三个插件统一发布到官方商店

入口位于 `fast-idea` 仓库. 默认从同级目录找到 `fast-visual-studio` 和 `fast-vscode`, 使用 PowerShell 7、JDK 17、Visual Studio SDK、Node.js/npm 和各仓库现有构建工具.

## 第一次配置

准备两个发布凭据:

- JetBrains: 在 [My Tokens](https://plugins.jetbrains.com/author/me/tokens) 创建 Marketplace Token. 对应插件为 `35046` / `com.mirror.ideafocusbridge`. 首次手动上传已完成, 后续使用官方上传 API 更新.
- Microsoft: 使用具有 `mmsan` 发布者权限的账号创建 Azure DevOps PAT, 选择 **Marketplace > Manage** 权限. 同一个 PAT 用于 Visual Studio 和 VS Code. [官方凭据说明](https://code.visualstudio.com/api/working-with-extensions/publishing-extension#get-a-personal-access-token).

在本机终端运行一次, 按提示粘贴 Token, 输入不回显:

```bat
publish-marketplace.cmd -ConfigureCredentials
```

凭据通过 Windows DPAPI 加密保存到 `%LOCALAPPDATA%\FastGoTo\marketplace-credentials.xml`, 只能由同一 Windows 用户在本机解密, 不进入 Git. 更新或更换 Token 时重新执行此命令. 自动化环境也可直接设置 `JETBRAINS_MARKETPLACE_TOKEN` 和 `VS_MARKETPLACE_TOKEN`, 环境变量优先.

不要把 Token 写进脚本、Git 或命令参数. Visual Studio 官方 `VsixPublisher` 在内部调用时仍通过进程参数接收 PAT; 脚本不打印该参数并对输出脱敏, 但同机有权限查看进程的程序仍可能读到它.

Microsoft 官方已公告全球 PAT 将于 **2026-12-01** 退役. 当前本地流程采用官方 PAT 发布方式; 退役前需迁移 Microsoft Entra 身份认证, 不能假定当前凭据永久可用.

## 日常发布

```bat
publish-marketplace.cmd
```

流程如下:

1. 检查发布凭据和工具, 取三个插件当前最高版本, 补丁号加一.
2. 同步 IDEA `gradle.properties`、VS manifest/AssemblyInfo、VS Code package/package-lock 的根版本.
3. 构建 IDEA 并运行结构及完整 Plugin Verifier, 构建 VS 官方渠道 VSIX, 检查 VS Code 语法/依赖并打包.
4. 从实际 ZIP/VSIX 校验稳定插件 ID、发布者及一致版本. 三个包全部成功后才上传.
5. 将安装包和 VS 商店 README 快照保存在 `.marketplace-release/<版本>/`, 记录 SHA-256. 按 JetBrains → Visual Studio → VS Code 顺序提交现有条目的新版本.

不卸载或重建商店条目, 不安装本机插件, 不更改本地更新源, 不自动提交 Git. 构建失败时恢复原始版本文件; 开始上传后保留版本和安装包用于续传. 成功提示表示商店接受上传, 不代表平台校验或人工审核已经完成.

## 预览、分步执行和续传

```bat
rem 只显示版本计划, 不修改或上传
publish-marketplace.cmd -Plan

rem 指定一个高于三个当前版本的新版本
publish-marketplace.cmd -Version 0.2.0

rem 同步版本并完成全部构建校验, 暂不上传, 不要求 Token
publish-marketplace.cmd -PrepareOnly

rem 使用已经准备好的安装包续传, 不重新构建或提升版本
publish-marketplace.cmd -Resume
```

上传进度在 `.marketplace-release/latest.json`, 已确认提交成功的平台会跳过. 续传前重新校验全部文件哈希, 文件变化即停止. 不要将该目录加入 Git, 也不要在发布完成前删除它. 有未完成发布时, 新发布会被阻止.

如果上传报错或进程中断, 该平台保持 `Uploading`, 表示远端结果不确定. **先到商店确认相同版本是否已收到**, 防止重复上传:

```bat
rem 仅在已确认 JetBrains 收到该版本后执行
publish-marketplace.cmd -Resume -ConfirmSubmitted JetBrains
```

平台名称可为 `JetBrains`、`VisualStudio`、`VSCode`. 如果确认远端没有收到, 在 `latest.json` 将该平台的 `status` 从 `Uploading` 改回 `Pending`, 然后执行 `-Resume`. 三个商店不支持跨平台原子发布, 某一平台失败不能撤回其他平台已经接受的更新.

仓库不在同级目录时可传 `-VisualStudioRoot` 和 `-VSCodeRoot`. IDEA 构建沿用 `build-plugin.ps1` 的 JDK/Gradle 配置, 并将 `HTTPS_PROXY` 的代理主机和端口传给 Gradle.

## 官方接口

- [JetBrains plugin upload API](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html)
- [Visual Studio VsixPublisher](https://learn.microsoft.com/en-us/visualstudio/extensibility/walkthrough-publishing-a-visual-studio-extension-via-command-line?view=vs-2022)
- [VS Code vsce](https://code.visualstudio.com/api/working-with-extensions/publishing-extension)
