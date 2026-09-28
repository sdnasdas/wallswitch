# 计划：把动态壁纸引擎换成 OpenGL（照搬 Muzei，废弃 Canvas）

> 交给执行 Agent 的完整施工单。请先读 `AGENTS.md`（仓库强制规范）与 `plan.md`（项目约定），
> 再按本文件执行。**本文件由上一轮对话产出，包含真机事故背景与已验证结论，不要凭直觉改动其中的取舍。**

---

## 0. 背景（为什么做这件事）

`WallSwitchService` 的动态壁纸引擎目前用 **Skia Canvas（`lockCanvas` 软件画布）** 绘制：每次切换壁纸要把满屏位图用 CPU 画一遍（每次切换的过渡动画 ≈ 31 帧全屏软件绘制、约 800MB 内存搬运）。真机实测：

- **Muzei（OpenGL 渲染）怎么切都不热**；
- 我们的引擎只要挂上就温，频繁手动切换时更明显；
- 曾在 v3.28 试过 `lockHardwareCanvas`（Skia 的 GPU 档位）→ **MagicOS 上黑屏 + 系统侧持续空转剧热，且不抛任何异常**，卸载后仍热，最终靠“手动换壁纸”才恢复。**该 API 已永久拉黑，不要再用。**

结论：要拿 Muzei 级的功耗，必须换成 **OpenGL**（它自己管理 EGL 表面、GPU 逐帧混合，是这台设备上已被验证可行的唯一 GPU 路径）。

用户决定（已确认，不要再问）：
1. **只保留 GL**，废弃 Canvas 引擎，不做“渲染后端”回退开关；
2. 模糊过渡**按 Muzei 思路实现**；
3. 一步到位，单次提交发布。

---

## 1. 强制规范（来自 AGENTS.md，必须遵守）

- **未经用户确认绝不 `git push`**（push 触发 CI 打包）。改完先给用户看 diff，用户说推才推。
- **每次提交必须递增版本号**：`app/build.gradle` 里 `versionCode +1`、`versionName` 次版本 +1（本次目标：**versionCode 44 → 45，versionName 3.37 → 3.38**）。
- 改完必须跑本地类型检查：仓库根目录 `.\check\check.cmd`，**必须看到 `== Java type check PASSED ==`** 才算完成。
- **不引入新依赖**（本方案通过 vendor 源码实现，不新增任何 Gradle 依赖）。
- 提交信息写中文，风格参考历史提交（`v3.37 ...`）。
- 本机**没有 gradle wrapper、也没有 Android SDK 完整构建能力**：只能跑 `check.cmd` 做类型检查，**无法本地构建/运行**，因此所有真机验证都由用户完成。

---

## 2. 参考源码（本机已克隆）

- Muzei 仓库：`D:\muzei`（Apache 2.0）
- 要 vendor 的文件：
  - `D:\muzei\gl-wallpaper\src\main\java\net\rbgrn\android\glwallpaperservice\GLWallpaperService.java`（856 行，**自包含**：内含 GLThread/EglHelper/EGL 配置选择器）
  - `D:\muzei\gl-wallpaper\src\main\java\net\rbgrn\android\glwallpaperservice\BaseConfigChooser.java`（152 行）
  - **不要** vendor `GLTextureView.java`（1713 行，是 App 内预览控件，动态壁纸用不到）
- 该模块 `build.gradle` 无任何 dependencies（`D:\muzei\gl-wallpaper\build.gradle` 可自行核对），所以放源码即可，**不需要改 `settings.gradle` / CI**
- Muzei 的关键用法（照抄）：
  - `D:\muzei\main\src\main\java\com\google\android\apps\muzei\MuzeiWallpaperService.kt` 第 193-238 行：`setEGLContextClientVersion(2)`、`setEGLConfigChooser(8, 8, 8, 0, 0, 0)`、`setRenderer(renderer)`、`renderMode = RENDERMODE_WHEN_DIRTY`、`requestRender()`；`onSurfaceChanged` → 重新加载当前图；`onVisibilityChanged` → 记为可见并请求渲染
  - `D:\muzei\main\src\main\java\com\google\android\apps\muzei\render\MuzeiBlurRenderer.kt`：两套画面（current/next）+ `crossfadeAnimator` 逐帧插值 + `onDrawFrame` 画 current、再按进度画 next
  - **alpha 位为 0（`setEGLConfigChooser(8,8,8,0,0,0)`）= 不透明表面**，这是它不发热的关键，务必照抄

---

## 3. 施工步骤

### 步骤 1：Vendor 两个文件（保留原版权头，不要改包名）
- `app/src/main/java/net/rbgrn/android/glwallpaperservice/GLWallpaperService.java`
- `app/src/main/java/net/rbgrn/android/glwallpaperservice/BaseConfigChooser.java`
- 原样复制，**保留 Apache 2.0 版权头**（含 Google 与原作者 net.rbgrn 声明），可在文件顶部注释加一行来源说明（注意来源是三层，不要只写 Muzei）：
  `// 来源：AOSP GLSurfaceView（2008，Apache 2.0）→ net.rbgrn 的 GLWallpaperService → Muzei gl-wallpaper 模块（https://github.com/muzei/muzei），均 Apache 2.0`
- 说明：该文件不是 Android 框架 API，而是把 AOSP `GLSurfaceView` 的内部实现（GLThread/EglHelper/配置选择器）**改造成面向壁纸 SurfaceHolder 的版本**——壁纸没有 View 树，无法直接使用 `GLSurfaceView`。Android 官方没有任何“壁纸用 GL”的 API，自己实现等于重写上千年行的 EGL/线程样板，故 vendor 这份已在真机验证过的代码。

### 步骤 2：本地类型检查脚本支持子目录
`check/javacheck.ps1` 目前只收集 `app/src/main/java/com/example/wallswitch/*.java`（平铺、不递归），改不动子包里的新代码。改为**递归收集整个 `app/src/main/java`**：
```powershell
$src = Join-Path $root '..\app\src\main\java'
...
# 生成 R 桩时，R.* 引用只可能来自我们自己的包，扫描时限定 com\example\wallswitch 子目录即可
$files = @(Get-ChildItem $src -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
```
- 注意 `R.java` 桩的生成逻辑仍按原样（扫 `com\example\wallswitch` 下的 java 找 `R.<type>.<name>`）
- 若 `android.jar`（`%LOCALAPPDATA%\Android\Sdk\platforms\android-34\android.jar`）缺少 GL 相关类型而报错，再按既有做法往 `check/stubs/` 添加最小桩（历史上 appcompat/material 就是这么处理的）

### 步骤 3：新写渲染器 `app/src/main/java/com/example/wallswitch/gl/WallpaperRenderer.java`
**职责**：接收位图 → 上传纹理 → 画一帧（含过渡）。全部渲染调用都在 GL 线程（由 `GLEngine.queueEvent` 保证）。

要求（逐条实现）：
1. `implements GLWallpaperService.Renderer`（即 `GLSurfaceView.Renderer`：`onSurfaceCreated/onSurfaceChanged/onDrawFrame`）
2. 用 **GLES20**；一个简单的 textured-quad 着色器程序（顶点着色器传 `aPosition`/`aTexCoord`，片段着色器 `texture2D` × `uAlpha`）；
3. 状态字段：`currentTex`、`nextTex`、`currentBlurTex`（模糊用，可选）、`viewportW/H`、`imageAspect`、过渡进度与时长、`visible`
4. 对外 API（都在 GL 线程调用）：
   - `void setImage(Bitmap bmp, Bitmap blurred, boolean animate)`：
     - 上传 `bmp` 为纹理（`glTexImage2D` + `GLUtils.texImage2D` 皆可），若 `animate && currentTex != 0` → 作为 `next`，启动过渡；否则替换 `current` 并释放旧的
     - `blurred` 非空时同样上传为 `currentBlurTex`（模糊过渡用）
   - `void setVisible(boolean v)`：不可见时不渲染；变为可见时若已有图 → `requestRender()`
   - `void release()`：删除纹理与程序（`onDestroy` 时调用）
5. **过渡实现（简化版 Muzei，但观感一致）**：
   - 淡入（默认，500ms）：画 `current`(alpha=1) → 画 `next`(alpha=t)
   - 模糊（700ms）：前 40% 画 `current` + `currentBlur`(alpha 递增)；后 60% 画 `currentBlur` + `next`(alpha 递增)
   - 过渡中每帧末尾 `requestRender()`（`RENDERMODE_WHEN_DIRTY`）；`t>=1` 时把 `next` 提升为 `current`、删除旧纹理、停掉重复请求
   - 进度用 `SystemClock.elapsedRealtime()` 计算（**不要**再用 `Thread.sleep` 循环——那是旧 Canvas 版的做法）
6. **centerCrop**：按 `viewportW/H` 与纹理宽高比计算纹理坐标（放大到铺满、超出部分裁掉），视口变化时重算
7. **关键坑（必须处理）**：`onSurfaceCreated` 会让**所有纹理失效**。引擎要在 surface 重建后**按“当前壁纸 key”重放一次上传**（对应 Muzei 的 `queuedImageLoader` 重放）。实现方式：渲染器保存一个 `pendingBitmap`/`pendingKey`，或由引擎在 `onSurfaceCreated` 后回调请求重放。
8. 上传完成后 CPU 侧 Bitmap 即可 `recycle()`（`texImage2D` 已拷贝到 GPU）

### 步骤 4：改造 `app/src/main/java/com/example/wallswitch/WallSwitchService.java`
- 基类：`extends WallpaperService` → `extends GLWallpaperService`
- `onCreateEngine()` 返回的 `WallEngine`：`extends Engine` → `extends GLEngine`
- 引擎 `onCreate(SurfaceHolder)`：`setEGLContextClientVersion(2)` / `setEGLConfigChooser(8,8,8,0,0,0)` / `setRenderer(new WallpaperRenderer(...))` / `setRenderMode(RENDERMODE_WHEN_DIRTY)` / `requestRender()`
- **删除**：`lockCanvasSafe`、`drawColor(SurfaceHolder)`、`drawBitmap(SurfaceHolder/Canvas,…)`、`alphaPaint`、`runTransition(...)`（sleep 循环版），以及任何 `lockCanvas`/`lockHardwareCanvas` 调用
- **保留**（这些与渲染无关，逻辑已稳定，勿动）：
  - `ENGINES` 列表 / `notifyWallpaperChanged()` / `openActivator()`
  - `isActive()` —— **按包名比对**（ROM 上报组件可能带别名）
  - 库与指针解析、推进兜底（`autoAdvanceFails < 3` + 取库里第一张显示）、过渡效果设置读取
  - 性能自记 `engine_stats.txt`（字段语义调整见下）与崩溃取证 `engine_crash.txt`、全局 `UncaughtExceptionHandler`
  - `makeBlurred()` / `boxBlur()`（CPU 一次性预模糊，结果作为模糊纹理上传）
- **不要**去照搬 Muzei 的“一次加载解三遍”（64px 探暗度 + 主图 + 模糊降采样）：那是为它的**压暗/灰化/多档模糊**特效服务的，我们没有这些效果；
  我们的 `WallpaperStore.decodeBounded()` 已经等价于它的“按目标尺寸解码”（`inSampleSize` 采样 + `scaleToFit` 缩到精确尺寸 + 立即回收中间图），
  且图源是本 App 自己裁剪保存的无损 PNG（尺寸/方向已规范，无需 EXIF 旋转与 OOM 重试循环）。**解码这块保持现状，别动。**
- 解码仍在 `DRAW_EXECUTOR` 后台线程；解码完成后 `queueEvent(() -> renderer.setImage(bmp, blurred, animate))`
- 缓存 key 逻辑保留：`壁纸id#宽x高` 不变则**不重复解码、不重复上传**；只是“命中”后的动作从“重绘”变成“必要时 requestRender”
- `onVisibilityChanged(visible)`：转发给渲染器（`setVisible`），不可见时**不再做任何绘制工作**
- `onSurfaceChanged`：更新视口，并按当前 key 重放上传（见步骤 3 第 7 条）
- **性能自记字段调整**：`累计绘制` 语义改为 **GL 帧数**（只有 dirty 时才画，数字小是正常的）；新增 `纹理上传次数`；`真实解码次数`/`缓存命中` 保留；故障诊断行保留（读不到壁纸、GL 初始化失败等）

### 步骤 5：失败可见（**没有回退了，不能白屏**）
- `onSurfaceCreated` 未成功 / EGL 初始化异常 / 绘制抛异常 → 写入 `engine_crash.txt`（限频）+ 在 `engine_stats.txt` 的“故障诊断”行写明原因
- 主界面已有读取 engine_stats 的通道，若需要，可在顶部横幅补一句“渲染初始化失败，请查看日志”——**不要新增复杂 UI**

### 步骤 6：类型检查 + 交付
- 跑 `.\check\check.cmd`，必须 `PASSED`
- 给用户看 diff 摘要（改了哪些文件、删了什么、加了什么）
- **等用户明确说“推”**，再 bump 版本号并提交推送（`git push origin ui-redesign`，分支不要动）
- 提交信息示例（中文、写清要点）：
  `v3.38 动态壁纸引擎换成 OpenGL（vendor Muzei 的 gl-wallpaper，照抄其 EGL 配置与 RENDERMODE_WHEN_DIRTY）：删除全部 Canvas/lockCanvas 绘制路径，过渡动画与模糊改由 GPU 逐帧混合；onSurfaceCreated 后重放当前图上传；失败路径写 engine_crash/engine_stats 取证`

---

## 4. 验收标准（用户真机执行，执行 Agent 只需在交付说明里列出）

1. 壁纸正常显示（不是黑屏/纯色）
2. 手动连续切换 10 次：动画流畅、**手机不再明显发热**（本次改造的核心目标）
3. 灭屏→亮屏、回桌面、下拉通知栏后画面正常
4. 一次自动切换（把间隔设 15 分钟等一轮）后画面正确
5. 旋转/分屏等尺寸变化后正常
6. `Download/WallSwitch/engine_stats.txt`：纹理上传次数 ≈ 切换次数，无“故障诊断”告警
7. 若黑屏：把 `engine_crash.txt` 与 `engine_stats.txt` 发给用户方（GL 失败原因会写在其中）

---

## 5. 风险与回滚

| 风险 | 说明 / 对策 |
|---|---|
| MagicOS 上 GL 壁纸表面异常 | 后台 Muzei 长期在本机运行 = 最强可行性证据；EGL 配置照抄 |
| 纹理在 surface 重建后丢失 | 必须在 `onSurfaceCreated` 后重放上传（步骤 3-7） |
| 无回退开关（用户明确要求） | 失败时靠日志定位；代码层面可 `git revert` 该提交后重新出包 |
| 执行 Agent 无法本地构建/运行 | 只保证：类型检查通过 + 逻辑自洽 + 无歧义；其余靠用户真机一轮反馈 |
| 不要做的事 | ❌ 不要再尝试 `lockHardwareCanvas`；❌ 不要引入新依赖；❌ 不要动接管状态模型（v3.37 已对齐 Muzei）；❌ 不要改库/指针/通知/小组件逻辑 |

---

## 6. 交付物清单

- [ ] 2 个 vendor 文件（含版权头与来源注释）
- [ ] `WallpaperRenderer.java`（新）
- [ ] `WallSwitchService.java`（改造：删 Canvas 路径、接入 GL）
- [ ] `check/javacheck.ps1`（递归收集源码）
- [ ] `check.cmd` 输出 `PASSED` 的证据
- [ ] 给用户的 diff 摘要 + 验收清单
- [ ] （用户确认推送后）bump 到 v3.38 / versionCode 45，提交并推送
