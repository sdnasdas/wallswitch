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
6. **push 成功即任务收尾**：不需要轮询/监听 CI 结果，也不要为等 CI 而阻塞——
   用户会自己去 GitHub Actions 下载构建好的 APK。

## 术语约定（三份图，绝不混用）

用户口中的「真实壁纸图」= 下表里的**成品图**。写文案、注释、提交信息时用下表术语，不要自造第三种叫法。

| 术语 | 指什么 | 存在哪 | 关键性质 |
| :- | :--- | :--- | :--- |
| **原图** | 用户从相册选中的那张图片文件本身，未经 App 任何处理 | `files/originals/<uuid>`，保留**原始字节**（不转码不缩放）。导入时由 `confirmImport` 从 `inbox/` **搬进**来；存量老壁纸可能没有，靠编辑页的「关联原图」补 | 进备份包（`originals/`）。它是重复调整的源；缺了它就只能裁上加裁 |
| **成品图** | 裁剪 + 缩放 + 挪位后导出的那张，也就是真正上屏的图 | `files/wallpapers/<id>.png`（无损 PNG，最长边 ≤ `maxWallpaperDim`） | 桌面 GL 引擎纹理与锁屏 `setBitmap` 都读它；导出目录里的图、备份包 `wallpapers/` 都是它的原始字节拷贝 |
| **缩略图** | 壁纸库网格 / 小组件 / 通知封面用的显示图 | `files/thumbs/<id>.jpg`（从成品图裁中心正方形，边长按两列方格显示宽度 `thumbSide`，JPEG q90） | 派生数据，**绝不上屏**；进备份包是为了免得还原后首次进列表在 UI 线程现解成品图 |
| **实况段** | 实况照片里那段短视频（约 2.7 秒 / 60fps / 带一条不播的音轨） | **不是文件**，是 `files/originals/<id>` 尾部的一段字节区间。播放前现场读尾部 40 字节的 `LIVE_<N>`（小红书）或头部 XMP 的 `Item:Length`（Google 系）算出偏移，再用 `MediaExtractor.setDataSource(fd, offset, length)` 直接喂 | 随原图字节进备份包，**不新增条目、不加 `library.json` 字段**；只有桌面 GL 引擎播它，锁屏够不着。缺原图 = 没实况 |

配套纪律：

- 「上屏那张」一律写**成品图**；只有指用户相册里那张才写**原图**。
- 备份/还原文案里的计数按这三样分开报，不要混称。
- 备份 zip 里每张壁纸有 3 个图片文件（原图 + 成品图 + 缩略图）是设计如此，**不是重复保存**；v3.8x 之前的老包只有 2 个（缺原图），还原时缺 `originals/` 跳过即可，不算失败。
- **实况段不算第 4 个文件**：上面那句「3 个图片文件」依然成立，备份包格式与还原计数都不因为它变化，文案里也别把它单列一项。
- 裁剪矩形一律是**原图像素坐标**，唯一例外是播实况段：必须先归一化成比例、再乘实况帧自己的宽高（真样本里封面帧 1440x1920、实况帧 1080x1440，像素矩形直接搬会错位）。
- **点壁纸格**就是「重编」，源是**原图**（`originals/<id>`），并用 `library.json` 里那条裁剪矩形（`src_w/src_h/crop_l/crop_t/crop_r/crop_b`，全在**原图坐标**）复原上次取景框；只有缺原图的存量壁纸才退回以成品图为源。参数只在「本次源是原图」时写。
- 长按格子浮出的那一排里，铅笔是**改标题小窗**（只有输入框 + 一行「成品图尺寸 / 原图有没有留存」，**不显示图片**），不是进裁剪 —— v3.86 把格子入口与铅笔互换了：以前单击看大图、铅笔进裁剪，现在单击直接进裁剪，大图预览随之下线。

## 提交信息约定

- 提交信息写中文，概括改动要点（参考历史提交风格，如 `410789a v1.1`）。
- 版本发布提交可用 `v<versionName>` 作为标题。

## 常用命令

- 本地类型检查：`.\check\check.cmd`
- CI 状态查询：`https://api.github.com/repos/sdnasdas/wallswitch/actions/runs`
