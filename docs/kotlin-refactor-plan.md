# Kotlin 全项目重构 实施计划

> **状态（2026-10-12）：暂缓。** 决定先补齐验证地基（CI 分支过滤、Gradle 分层检查、夹具进 JUnit、行为基线快照）再评估是否启动；本文档随 `shelved-plans` 分支存档，不随主线推进。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **但先读「必须加」栏的 A1 与 A11 —— 违反这两条会覆盖掉手机上正在用的安装包。**

**Goal:** 把 `app/src/main/java/com/example/wallswitch` 的 31 个主源文件（14,640 行，测于 `8dcdec8` = v3.108）从 Java 换到 Kotlin，**用户可观察行为逐条不变**。

**Architecture:** 分两个可独立验收的阶段 —— 阶段 0 先把本地验证地基换掉（弃手写桩、分层验收、锁死分支不外泄），阶段 1+ 才逐类转换。转换与任何行为改动严格分 commit，混合态（Java + Kotlin 共存）是**允许的长期稳态**，不是必须走完的过渡。

**Tech Stack:** Java 17 → Kotlin（KGP 版本待实测）、AGP 8.5.2、Gradle 8.7（CI 与本地检查都钉这版，不用 wrapper 的 9.3.0）、compileSdk 35、minSdk 26。

---

## 前提事实（本会话实测，不是推断）

| 项 | 实测值 |
| :- | :-- |
| 主源规模 | 31 个 `.java` / 14,640 行；`MainActivity` 3,680 行 = **占 25%** |
| **并发会话** | 本会话内 `gl-engine` 被另一条会话推进过（HEAD `0dc7f15`→`8dcdec8`，`WallpaperStore` 从 992 行涨到 1,006）。**所有行数在开工时必须重测** |
| vendor 代码 | `net/rbgrn/GLWallpaperService` 1,016 行（是否转换见待决 2） |
| 手写桩 | `check/stubs/**` **34 个文件 / 878 行** + `javacheck.ps1`（66 行）里的 R 桩生成器 |
| `check.cmd` 耗时 | **6.1s，当前 PASSED** |
| **Gradle 编译检查耗时（本机 Gradle 8.7 + JDK17 + `--offline`）** | daemon 热 + 全 up-to-date **2.4s**；**新增一个文件 2.7s、改一个文件 2.2s**；daemon 冷启 **28s**；`--no-daemon --rerun-tasks` 全量 **49s** |
| ↑ 这一条推翻了原方案 | 增量单文件 2.2s **比 `check.cmd` 的 6.1s 还快**，且覆盖面严格更大（真依赖 + 真 R + AAPT2 + compileSdk 35）。所以桩路线整体进可弃栏，快筛这件事由 Gradle daemon 做，`check.cmd` 不再保留成日常层 |
| 离线可行性 | appcompat / material / recyclerview / work 四个包全在 `~/.gradle/caches/modules-2`，`--offline` 实测跑通 |
| 本机 SDK | platforms 有 33/**34**/35/36，build-tools 有 34.0.0/35.0.0/36.1.0 —— 即 `compileSdk 35` 本机可用，但 `javacheck.ps1` 硬编 android-34（**错配中**） |
| wrapper | 指 Gradle **9.3.0**；裸跑 `./gradlew` 必失败（默认 `java` 是 1.8，报 "Gradle requires JVM 17"）；带 JDK17 只做 `--dry-run` 通过（**配置阶段过了，执行未验**） |
| CI | `.github/workflows` 是 `on: push`（**无分支过滤**），显式装 Gradle **8.7**，末尾 `gh release upload latest ... --clobber` |
| 远端 | `origin` 的 push URL 有两个（GitHub + Gitee），`git push origin` **一推两得** |
| 无 runner 的夹具 | `BackupRoundtripTest` `MetaSalvageTest` `RestoreSim` `WidgetCellSizeTest` `OrgCross` —— 都有 `main`，10-08 提交信息里写过「夹具 PASSED」，说明是手敲跑的，命令没进仓库 |
| 跨语言风险的量化 | `== null / != null` **537 处**、`catch` **152 处**、`synchronized`/`volatile` **53 处**、三元 **227 处**、`enum` 0 个 |
| 反射 | 主源里 `getMethod` / `getDeclaredField` / `Class.forName` **0 处** → `library.json` 与 SharedPreferences 的 key 全是手写字符串，**属性改名不会静默改磁盘格式** |
| 现状 | 分支 `gl-engine`，HEAD `8dcdec8` = v3.108，`versionCode 115` / `versionName "3.108"` |

---

## 可舍弃（有证据的，不是"看起来多余"）

| # | 弃什么 | 弃的理由 | 弃之前必须先满足的条件 |
| :- | :-- | :-- | :-- |
| S1 | `check/stubs/**` 34 个文件 / 878 行 | 出生理由已反：`c818fb6`(2026-09-22) 提交信息原话「无需 Gradle/完整 SDK」，而本机 Gradle 8.7 离线可编译 | 阶段 0 的分层验收跑顺，且真跑过一次 Gradle 编译检查绿 |
| S2 | `javacheck.ps1` 里那段 R 桩生成器（正则扫 `*.java` 造 `R.id.* = 计数器序号`） | 它**不看 XML**，所以「XML 删了 id、代码还引用」这条必漏；Gradle 的 `processDebugResources` 按真 XML 生成真 R | 同 S1 |
| S3 | `.gitignore` 里 `gradle/ gradlew gradlew.bat` 三条 ignore + 注释「本机无完整 SDK，打包全走 CI」 | 前提已不成立（本机有 SDK 35/36、Gradle 8.7 可跑）。不提交 wrapper 的直接后果：本地 9.3.0、CI 8.7 会一直版本不一致，转 Kotlin 挑插件版本正好撞上 | 决定 wrapper 提不提交（待决 3） |
| S4 | AGENTS.md 规则 4 的「`check.cmd` PASSED 即完成」这个定义 | 它现在抓不到资源/manifest/桩差异，而这些 Gradle 一层就覆盖 | 分层验收落地，完成线改成 Gradle 任务 |
| S5 | `javac -nowarn` | 吞警告。刚才那次 Gradle 跑就把 `使用或覆盖了已过时的 API` 报出来了，check.cmd 完全看不到 | 无 |
| S6 | `realmeta.ps1` 硬编的 `C:\Users\EDY\.cursor\extensions\vmware.vscode-spring-boot-.../lib/json-20260814.jar` | 那是第三方扩展的私有 lib，扩展升级或卸载即断。现仍在，但没理由赌 | 换成仓库内或 Gradle 正规依赖（待决 4） |
| S7 | **"顺手美化"整栏**：`!!`、`lateinit` 清剿、协程改造、拆文件、改名、调结构 | 这是最大的一项舍弃。混进行为改动的翻译**不可审计** —— 出了症状分不清是换语言还是改逻辑，而这个项目没有 adb、回验成本是"装机用一天" | 无（这条是纪律，不是任务） |
| S8 | `check.cmd` / `javacheck.ps1` 作为**日常验收层**（脚本本体可留作 Gradle 起不来时的应急） | 增量实测 2.2s vs 6.1s，速度优势不存在；覆盖面严格输：桩是手抄的（双向差异）、R 桩不看 XML、android.jar 还是 34 | Task 0.4 的 `gradlecheck.cmd` 跑绿一次 |

**明确不在舍弃栏里的**：5 个无 runner 的夹具。它们是**唯一的行为断言**（Gradle 只编译不跑断言），该补 runner 不是该删。

**随实测改判的**：`check.cmd` 原本我按「6s 快筛」把它留在保留栏，理由是 Gradle 要 49s。实测增量单文件只要 **2.2s** —— 那个理由不成立了，所以它进 S8。

---

## 必须加（缺一个就会出事，按危险程度排序）

| # | 加什么 | 不加的后果（机制已核实） |
| :- | :-- | :-- |
| A1 | **重构分支不许 `git push origin`**，或给 workflow 加 `push: branches: [main]` 过滤 | CI 是 `on: push` 无分支过滤 + `gh release upload latest --clobber`。半重构分支一 push → 滚动 latest 直链（手机就装这个）被覆盖成分支包，且 origin 双 push URL 连 Gitee 一起推 |
| A2 | 本地 Gradle 检查脚本**钉死四件事**：`JAVA_HOME=corretto-17.0.20.1`、Gradle **8.7 发行版**（不是 wrapper 的 9.3.0）、`--offline`、**daemon 常驻（不加 `--no-daemon`）** | 裸 `./gradlew` 必报 JVM 8；本地 9.3.0 / CI 8.7 不一致 → 本地绿 CI 红；不带 `--offline` 会走网络、失败信号变成"下载超时"而不是编译错误；带 `--no-daemon` 会把 2.2s 的增量检查变成 28s，等于把这一层判死刑 |
| A3 | **行为基线快照**（转换开工前先记）：当前 versionName、每条主链路的"操作 → 屏幕该看到什么"清单 | 换语言零用户可见变化，唯一验收信号是真机回验。没有转换前的基线，回验就没有对照物，出问题时无法判断是转换引入还是既有 |
| A4 | **真机回验清单**（逐条可勾选，含实况段、小组件、通知角标、锁屏、备份还原） | `WallpaperRenderer` 700 行 + `MotionPlayer` 389 行 + shader 字符串 + 实况段字节区间，和 `WidgetConsoleProvider` 597 行的 RemoteViews，是两处"改坏了本地发现不了"的地方。v3.101 那次小组件挂掉是已付过学费的实例：机制全对、装上就是不能用、最后整体回退 |
| A5 | **跨语言边界的空值契约**：来自 Java 侧的值进 Kotlin 参数一律声明成可空并显式判空；禁止 `!!`；禁止用平台类型接 | javac 不拦 Java→Kotlin 的 null 传参（Kotlin 的 `@NotNull` 对 javac 只是 IDE 提示），运行时才由 intrinsic check 抛。537 处 null 判断 + 老装机缺 `originals/`、`library.json` 截断救回、备份缺字段，全是往边界递 null 的路径 |
| A6 | **异常契约**：Kotlin 调用仍为 Java 的 `throws IOException` 方法时，必须显式 try 或把声明上移 | Kotlin 无 checked exception，编译器不逼调用者处理。转换中途很容易变成异常直冒到线程 uncaught handler，而这个 App 的失败模式多半是**静默没切图**，不崩给你看 |
| A7 | **一类一 commit，且可单文件回退**（禁止把多个类捆进一个 commit） | 混合态下每张类都有 Java 引用者（`MetaFiles` 被 3 个、`PinnedWallpaper` 被 4 个、`SwitchLog` 被 5 个、`TimerScheduler` 被 14 个、`LibraryStore` 被 15 个引用）。捆批 = 出问题时只能整批 revert，丢掉已验证的部分 |
| A8 | **KGP 版本必须在 AGP 8.5.2 + Gradle 8.7 上实测过**再写进构建脚本 | 本机唯一真跑通的是 8.7（9.3.0 只做了 dry-run，执行未验）。插件版本超出这档支持范围 = 构建在配置阶段就挂，且报错长得像 SDK 问题 |
| A9 | **`compileSdk` 与本地 android.jar 对齐**（35 vs 现在桩路线用的 34） | 若保留 javac 快筛就把它改 35；若弃桩则随 S1 自然消失。这条不能留在错的状态上 |
| A10 | 重构分支**独立版本号段**（待决 5） | 号段撞车 = 装机分不清 + `UpdateChecker` 从 latest 标题读 versionName 比对失效（`branch-version-numbering` 记过：lan-sync=103/3.96、gl-engine 当时跳到 104） |
| A11 | **合流策略先定，且基线记 commit SHA 不只记 versionName**（待决 7） | 这是本计划里最容易致命的缺口：`gl-engine` 是**活跃分支**（本会话内就被推进过一次，v3.106/107/108 几天内连发）。重构分支若长期不分叉合回，最后收口时要在 3,680 行的 `MainActivity` 上和正在进行的产品化改动做三方合并 —— 那种冲突没有可靠解法，只能重做。所以「什么时候、以什么粒度把 Kotlin 合回主功能线」必须在**第一个类转换之前**定死，而不是等转完再谈 |

---

## 待决清单（按编号回答；没答的走我标的推荐值）

1. **转换范围含不含 `MainActivity` 那 3,680 行？**
   推荐：**含，但排最后**，并且明确允许停在「MainActivity 仍是 Java」的稳态收工。
   代价：它不转，它调用的那些 Kotlin 类的非空声明全靠运行时兜 —— 类型系统收益在 Java 调用方那一侧归零，只拿到语法收益。

2. **`net/rbgrn/GLWallpaperService` 1,016 行转不转？**
   推荐：**不转**，留 Java。
   代价：留 1k 行 Java 在树里，混合态永久存在。转的理由只有"整齐"。

3. **`gradlew` / `gradle/` 提不提交进仓库？**
   推荐：**提交，并把 wrapper 版本改成 8.7 与 CI 对齐**。
   代价：多两个二进制入口文件进仓库；好处是本地与 CI 版本从此同一，`AGENTS.md` 那句过期理由也一并订正。

4. **5 个夹具的去向：补 `.cmd` runner，还是挪进 `app/src/test` 走 Gradle unit test？**
   推荐：**挪进 `app/src/test`**（`testImplementation 'org.json:json:<ver>'` 一并解决 S6 那个 `.cursor` 扩展 jar）。
   代价：要确认这 5 个夹具没碰 android API（它们大多自带复刻逻辑，看起来是纯 JVM，但需逐个 `grep import android` 才算确认）；`android.jar` 里的 `org.json` 在单测里会抛 "not mocked"，所以正规依赖是必须的而不是可选。

5. **重构分支的号段。**
   推荐：`versionCode` 从 **200** 起、`versionName` 用 **3.200.x** 形态（大字段的真实含义是"这批包永远不该被当成正式版装"，而不是冒充 4.0 里程碑）。
   代价：UpdateChecker 是按 `.` 拆开比整数的，跨段比较要现算一遍确认 3.200 > 3.108 成立（这条必须现跑现验，不许推定）。

6. **转换与拆文件是否允许同 commit？**（`MainActivity` 3,680 / `WallpaperStore` 1,006 / `WallSwitchService` 899 都到了该拆的体量）
   推荐：**禁止同 commit**，拆文件另立一档、排在语言转换全部收口之后，且可以完全不排期。

7. **合流策略（对应 A11，本计划里最需要你拍的一条）**
   - (a) 每完成一档就合回 `gl-engine`，混合态即交付态，功能开发不停
   - (b) 冻结 `gl-engine` 上的功能开发直到重构收口
   - (c) 重构分支独立走到底，最后一次性合
   推荐 **(a)**。理由：它和 A7「一类一 commit」、待决 1「允许停在 MainActivity 仍是 Java」是同一套设计 —— 混合态是合法稳态，所以每一档合回去都是可发布状态；冲突面被切成"一档一行"级别的小合并。
   代价：(a) 要求每档合回前都过一次真机回验（A4），装机次数比 (c) 多；且混合态会真实存在几周，期间 `check.cmd` 对 `.kt` 隐形这件事必须靠阶段 0.4 的 Gradle 分层兜住，不能靠旧脚本。
   (b) 代价：直接推迟产品化那条线（`docs/cloud-service-plan.md`），我不建议由我这边默认选它。

---

## 阶段 0：地基（先做完才准转第一个文件）

产出物是一个可独立验收的状态：**桩路线退役、Gradle 分层验收上线、分支不外泄**。此阶段零 Kotlin 源码。

### Task 0.1：立 worktree 与分支

- [ ] 确认主 worktree 干净：`git status --short` → 只应看到 `?? docs/cloud-service-plan.md` 与 `?? docs/kotlin-refactor-plan.md`（本文档），**别的都算异常，先问再动**
- [ ] 记起点：`git rev-parse HEAD` → 把完整 SHA 写进本文档「基线」小节（A11）
- [ ] 建 worktree：`git worktree add ../wall-kotlin -b kotlin-refactor gl-engine`
- [ ] 重测规模：在 `../wall-kotlin` 里重跑行数统计，替换本文档「前提事实」表里的数字（本会话内该数已三次变动）
- [ ] 在 `../wall-kotlin` 里加"禁止误推"护栏：`git config push.default nothing` **不够**（显式 `git push origin <br>` 不受它管）→ 契约是：**阶段 0 结束前不动 origin**，且 CI 加过滤（Task 0.2）先落地，再谈任何 push
- [ ] 把本文档带进新 worktree：**未跟踪文件不会跟着 worktree 走**，要么先在 `gl-engine` 上 commit 本文档再建 worktree（推荐，文档从此有历史），要么手工 `cp` 过去。契约是：**执行者读到的必须是 worktree 内的那份**，否则两个会话读两份

**验收**：`git worktree list` 出现两条；主 worktree HEAD 未变。

### Task 0.2：CI 加分支过滤（A1）

- [ ] 改 `.github/workflows/*.yml` 的 `on: push:` 为 `on: push: branches: [main]`，保留 `workflow_dispatch`
- [ ] 手工验一次：在 `kotlin-refactor` 上不 push，改用 `workflow_dispatch` 才触发 —— 契约是"分支包只能显式要"

**验收**：`.github/workflows` 里存在 `branches` 行；期望失败文案 —— 若漏改这条，后续每次分支 commit 都会覆盖手机上的 latest 直链。

### Task 0.3：KGP 版本实测（A8）

- [ ] 根 `build.gradle` 的 `plugins {}` 加 `id 'org.jetbrains.kotlin.android' version '<候选>' apply false`，**候选按这个顺序试，第一次成功即停并把版本号记进本文档**：`1.9.24` → `2.0.21`。（不预先宣判：这两个版本对 AGP 8.5.2 / Gradle 8.7 的支持范围我**没有查过官方兼容表**，只认这条命令的返回码）
- [ ] `app/build.gradle` 的 `plugins` 加 `id 'org.jetbrains.kotlin.android'`
- [ ] 跑 `JAVA_HOME=<corretto-17> <gradle-8.7>/bin/gradle compileDebugKotlin --no-daemon`（**这一次不带 `--offline`**：实测本机 `modules-2/files-2.1` 下只有 `kotlin-stdlib` 那一族，**没有 `kotlin-gradle-plugin`**，插件标记解析需要联网一次。下完之后往后的日常检查才恢复 `--offline`）
- [ ] 期望：`BUILD SUCCESSFUL`，且**当前还没有任何 `.kt` 文件**（空 source set 也要过，这证明插件与 AGP/Gradle 三者能共存）
- [ ] 失败文案：配置阶段报 AGP/KGP 不兼容 → 换下一个候选版本重试，**不要**顺手升 AGP 或改 wrapper（那会把范围搞脏）
- [ ] 无网且必须验时：`--offline` 会报插件版本解析失败，那条错误**长得像 SDK 问题但不是** —— 遇到就先确认是不是断网

**验收**：`gradle-8.7` 上 `compileDebugKotlin` SUCCESSFUL；记录最终版本号进本文件，作为后续唯一依据。

### Task 0.4：分层验收脚本（A2、S5、A9）

- [ ] 新建 `check/gradlecheck.cmd`，内容契约（四件事缺一不可）：设 `JAVA_HOME=C:\Users\EDY\.jdks\corretto-17.0.20.1` → 调 **8.7 发行版**（实测路径 `C:\Users\EDY\.gradle\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle`）→ 任务集 `compileDebugKotlin compileDebugJavaWithJavac` → 带 `--offline`
- [ ] **绝对不要加 `--no-daemon`**：实测 daemon 常驻时增量单文件 2.2s，加 `--no-daemon` 冷启变 28s、全量 49s。这一条决定 `check.cmd` 能不能被替掉
- [ ] **不许用 `./gradlew`**（9.3.0 + 无 JAVA_HOME 必挂，见前提表）
- [ ] `check.cmd` 保留但降为应急（S8），文件头注释写明「Gradle 起不来时才用，且它查不到资源/manifest/真依赖差异」；若暂时仍留 `android-34` 硬编就一并改 35（A9）、去掉 `-nowarn`（S5）

**验收**：`check/gradlecheck.cmd` 输出 `BUILD SUCCESSFUL`；**第二次连跑应落在 1~3s**（这是 daemon 常驻的证据，不是巧合）；故意把一个方法名改错 → 期望它**报编译错误**（证明这一层真在检查，不是空跑）。`check.cmd` 仍 `== Java type check PASSED ==`。

### Task 0.5：夹具挪进 Gradle 测试（S6、待决 4）

- [ ] 先做判别：逐个 `grep -l "^import android" check/*.java` —— **有 android import 的那几个不能直接挪进 JVM unit test**，留在 `.cmd` 路线
- [ ] 新建 `app/src/test/java/...`，把可挪的夹具转成 JUnit；`app/build.gradle` 加 `testImplementation` JUnit + `org.json:json`
- [ ] 给留下的 5-无-runner 夹具补 `check/fixtures.cmd` 一次跑完（含 `WidgetCellSizeTest`，它 10-08 还在用）
- [ ] `realmeta.ps1` 的 jar 路径改成仓库内或 Gradle 解析出的那份（S6）

**验收**：`gradle testDebugUnitTest --offline` 出现 PASSED，且失败时能明确报是哪个夹具（不许只报 "tests failed"）。这条是"编译通过 ≠ 行为对"那一层唯一的自动化信号。

### Task 0.6：行为基线快照（A3）

- [ ] 在 `gl-engine`（转换前那条）装机跑一轮，逐条记「操作 → 屏幕看到什么」：格子点击重编、长按浮排（铅笔=改标题）、切换日志 tab、通知角标翻面、锁屏静态那张、小组件两档占位、实况段播放、备份→还原计数（原图/成品图/缩略图分开报）
- [ ] 记进 `docs/kotlin-refactor-plan.md` 末尾的「基线」小节，**标注采集时的 versionName**

**验收**：清单条目 ≥ 上面 8 项，每项有可判定的"通过长什么样"。

---

## 阶段 1+：逐类转换

按 fan-in 从低到高排（**被引用越少越先转**，因为混合态下每个 Java 引用者都是一处 `cannot find symbol`，扇入决定返工量）：

| 档 | 类（扇入 → 行数） | 该档专属契约 |
| :- | :-- | :-- |
| T1 | `MetaFiles`(3) 277、`PinnedWallpaper`(4) 322、`SwitchLog`(5) 367、`WidgetProvider`、`NotifActionReceiver`、`SwitchNotifier`、`BootReceiver` | `MetaFiles` 是原子写 + 截断救回的落点：**它的空值语义一个都不许"顺手改"**（A5）；转换后必跑 0.5 的夹具 |
| T2 | `LibraryStore`(15)、`TimerScheduler`(14)、`UpdateChecker`、`LibPickerActivity`、`EditActivity` 511 | 磁盘格式与调度：**`library.json` 序列化只能一处定义**（备份/还原档案记过的硬约束），翻译时若把写手复制出第二处即失败 |
| T3 | `WallpaperStore`(1,006)、`BackupStore`(654)、`Switcher`(505)、`TakeoverManager`(518)、`WallpaperExporter`、`CropView`(372)、`StatusNotifier`(400)、`WidgetConsoleProvider`(597) | **本档含两处"本地发现不了"的重灾区**：RemoteViews 与裁剪算式。`WidgetConsoleProvider` 转换后必须走 A4 真机回验，不接受"编译过就算"；坐标契约不许动 —— 裁剪矩形仍是**原图像素坐标** |
| T4 | `gl/MotionSource`(355)、`gl/MotionPlayer`(389)、`gl/WallpaperRenderer`(700) | `MotionSource` 是**唯一被 runner 直接编进生产源码的类**（`motion.cmd` 把 `MotionSource.java` 和测试一起 javac），转 Kotlin 那一刻 `motion.cmd` 必挂 —— 必须同步改成 kotlinc 或挪进 0.5 的单测。实况段仍是「原图尾部字节区间」，**不新增文件、不加 `library.json` 字段**；裁剪矩形喂给实况帧前要归一化再乘实况帧自身宽高 |
| T5 | `WallSwitchService`(899)、`MainActivity`(3,680) | 最大两张，且 `MainActivity` 里有 `wheelColumn()` 这类被弹窗复用的工具（弹窗契约：一律 `MaterialAlertDialogBuilder`、数值用滚轮、单选项弹窗不许 `setMessage`）。允许收工停在 `MainActivity` 仍是 Java |

**标准转换流程**（每个类一遍，不跳步）：

- [ ] Step 1：`git checkout -b` 不进新分支，在当前分支上单文件操作；确认工作区只有这一个文件待改
- [ ] Step 2：IDE 转换 → **立即通读，逐处把 `!!` 换成显式判空或可空声明**（A5）。清 `!!` 的数量记进 commit 信息，作为「这轮真的核过空值」的证据
- [ ] Step 3：跑 `check/gradlecheck.cmd` → 期望 `BUILD SUCCESSFUL`；`check.cmd` 此阶段会因 Java 侧 `cannot find symbol` 报红，**这是预期的**，不许为了让它绿而回填桩
- [ ] Step 4：跑阶段 0.5 建立的夹具/单测 → 期望 PASSED
- [ ] Step 5：`git commit` —— **一个类一个 commit**（A7），信息里写：类名、清掉的 `!!` 处数、动了哪几处空值判断、有没有触发 A6 的异常契约
- [ ] Step 6：每完成一个档，装机一次，按 A4 清单回验该档涉及的链路，然后**递增版本号**（待决 5 的号段）

**失败文案（写死，不许临场发挥）**：
- 某类的 Java 引用者太多导致返工超出预算 → **回退该类**，在本文档记「此类保持 Java」，不许为了转它而顺手改调用方
- 出现「行为变了但说不清是不是翻译引起的」 → **停手**，回退到该档最后一个已装机验证过的 commit；这一档重新逐类转，一次一类

---

## 不做的事（范围护栏）

- 不引入协程/Flow 改造定时与导入链路（那是另一件事，且它的收益只有在他真要重做异步时才成立）
- 不拆 `MainActivity`（待决 6，可完全不排期）
- 不动 `net/rbgrn`（待决 2，推荐不转）
- 不新增任何依赖（`kotlin-stdlib` 本来就在 APK 里，实测 classes.dex 含 `kotlin/collections`，**加插件不增体积**）
- 不为了"Kotlin 风格"改任何磁盘格式、SharedPreferences key、RemoteViews layout id、通知 channel、manifest 类名
- 不顺手改命名（`src_w/crop_l/...` 这类 key 一个都不许动）

---

## 收工条件

阶段 0 全部绿 + 至少 T1 档装机回验通过，才允许讨论"要不要继续"。任何时候都可以停在混合态上正常发版 —— 混合态是合法稳态，不是未完成状态。

---

## 待实测项（本文档不许有"应该是"）

| 项 | 现状 | 谁来定 |
| :- | :-- | :-- |
| KGP 具体版本 | **未定**，Task 0.3 按 `1.9.24 → 2.0.21` 顺序实测；我**没查过官方兼容表**，只认命令返回码 | Task 0.3 |
| Gradle 9.3.0 + AGP 8.5.2 能否真执行 | 只做过 `--dry-run`（配置阶段过） | 决定待决 3 时一并测 |
| 5 个夹具是否碰 android API | 未逐个 grep（决定它们能不能进 JVM 单测） | Task 0.5 Step 1 |
| `3.200 > 3.108` 在 UpdateChecker 的整数拆比下是否成立 | 未验 | 待决 5 落地时现跑现验 |
| `kotlin-gradle-plugin` 能否在本机离线复用 | 已确认**不在缓存**，第一次必须联网 | Task 0.3 落地后复测 `--offline` |
