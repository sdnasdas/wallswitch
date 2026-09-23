# wallswitch 项目工作规范（Agent 必读）

> 本文件约定与本仓库协作时的强制规则。开始任何工作前先读完本文件；
> 更详细的交接上下文见 `C:\Users\EDY\wallswitch-handoff.md`，补充约定见 `plan.md`。

## 项目基本信息

- 路径/仓库：`D:\wall`，GitHub `sdnasdas/wallswitch`，分支 `main`
- 技术栈：Java 17、Gradle 8.7、AGP 8.5.2、minSdk 26、targetSdk 35、`applicationId com.example.wallswitch`
- 依赖仅 WorkManager 2.9.1 + appcompat + recyclerview（AndroidX）
- 目标设备：荣耀 MagicOS 手机，APK 手动安装、靠版本号区分
- 本机没有 gradle wrapper，完整资源打包与 lint 只能在 CI 跑
- CI 用固定 keystore 签名（GitHub Secrets `WALLSWITCH_KEYSTORE_*`，本地备份在
  `C:\Users\EDY\wallswitch-signing\`），保证覆盖安装签名一致；keystore 绝不提交进仓库

## 强制工作流规则

1. **未经用户确认绝不 `git push`**（push 会触发 CI 打包）。
   所有改动先以 diff 形式展示给用户，用户确认后再提交。
2. **每次提交必须递增版本号**：`app/build.gradle` 中
   `versionCode +1`、`versionName` 次版本 +1（如 1.1 → 1.2）。
3. **用户提问时先回答问题**，不要急着改代码；涉及改动时先给方案、等确认再实现。
4. **改完代码后运行本地类型检查**：在仓库根目录执行 `.\check\check.cmd`，
   期望输出 `== Java type check PASSED ==` 后才算完成。
5. 遵守现有代码风格与约定；不引入未在项目中使用的新依赖（除非用户同意）。

## 提交信息约定

- 提交信息写中文，概括改动要点（参考历史提交风格，如 `410789a v1.1`）。
- 版本发布提交可用 `v<versionName>` 作为标题。

## 常用命令

- 本地类型检查：`.\check\check.cmd`
- CI 状态查询：`https://api.github.com/repos/sdnasdas/wallswitch/actions/runs`
