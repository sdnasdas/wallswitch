# 实况壁纸（桌面 GL 引擎播实况段）实施方案

> 状态：**已装机验收通过**（`check.cmd` PASSED，`check/motion.cmd` 35 条断言全过）。范围：只做桌面，锁屏不碰。
> 施工中发现本文两处写错的地方，已在下文就地订正：① 触摸回调是公开的 `onTouchEvent` 不是 `onSurfaceEvent`；② 实况段定位不需要往 `library.json` 加字段。
> 装机后又订正两处（见 §5）：③ `releaseOutputBuffer(idx, timestampNs)` 对 `SurfaceTexture` **不起 pacing 作用**，必须解码线程自己 sleep；④ 循环不在 codec 层 `flush()+seekTo(0)`，改成「一轮一个 player，播完由引擎按间隔重排下一次起播」。
> 术语新增：**实况段** —— 它不是第四份图片文件，而是 `originals/<id>` 尾部的一段字节（MP4）。
> 上屏时它和成品图画的是同一块构图，所以「原图 / 成品图 / 缩略图」三样都不因这个功能改变语义。

## 0. 已实测确认的事实（施工时别重新怀疑）

**样本 A**（小红书存的实况，走微信「图片」通道）：100,720 字节、`FFD9` 就是文件最后两字节、无 EXIF 无 XMP。
→ 结论：图片通道必然重编码，**取证只能走微信「文件」通道**。

**样本 B**（同一张，走微信「文件」通道，2,140,607 字节）字节布局：

| 区间 | 长度 | 内容 |
| :- | :- | :- |
| `[0, 1341119]` | 1,341,120 | JPEG 封面帧 1440×1920，只有 `FFE0` JFIF + `FFE2` ICC |
| `[1341120, 2140566]` | 799,447 | MP4：`ftyp`32 / `moov`4367 / `free`8 / `mdat`795020，`mdat` 之后还有 20 字节 ASCII 垃圾 |
| `[2140567, 2140606]` | 40 | 定长 trailer：`"0:1000"`+14 空格、`"LIVE_799447"`+9 空格 |

定位规则：`N = LIVE_(\d+)`；**实况段 = `[EOF-40-N, EOF-40)`**。实测 `EOF-40-N == JPEG EOI+2`，一字节不差。

**实况段参数**：1080×1440、H.264 High@5.0、162 帧 / 60fps / 2.700 秒、**全片只有 1 个关键帧**、附带一条 AAC 44.1kHz 音轨、`moov` 在 `mdat` 之前（faststart，可流式读）。

两个已踩到的坑：

- `tkhd` 的 width 字段是坏的（`40 00 00 00` → 16384），**真实分辨率只能从 `stsd` 的 `avc1` 条目读**（entry+32/+34）。
- 封面帧 1440×1920、实况段 1080×1440，**两者分辨率不同**（比例都是 3:4 才对得上）。

**链路已验**：Photo Picker（`PickMultipleVisualMedia` + `ImageOnly`）会把实况原样交出来，`importToInbox` 的 `Files.copy` 保住字节，备份 zip 也原样带着 —— `originals/` 那份与源文件 **sha256 相同、`Buffer.compare` 为 0**。
→ 所以：**不改选择器、不加存储目录、不加 `library.json` 字段、不改备份/还原格式**。存量壁纸只要 `originals/<id>` 带尾部，自动就有实况。

## 1. 定稿的六条共识

1. 只做桌面。锁屏仍走 `setBitmap(FLAG_LOCK)`，实况在锁屏不存在（系统不替第三方播动画，MagicOS 自己的实况壁纸无对外 API）。
2. 触发方式两档，**全局一处设置**：默认「按住播放」，可选「循环播放」。
3. 不加格子角标、不加单张开关。有实况段就播、没有就不播。
4. 判定在播放前现场读文件尾 40 字节，结果按壁纸 id 缓存在内存，不写盘。
5. 荣耀相机拍的实况暂不取样本，解析层保留 XMP 兜底，遇到再说。
6. 视频有音轨但**一律静音**（不建 AudioTrack，壁纸出声不可接受）。

## 2. 施工步骤

### 步骤 0（先做，卡解析层）：取一张微博实况样本

用户确认图片来源是小红书 + 微博，都是 jpg。微博的封装格式**未知**：可能同样是尾部追加 + 自定义 trailer，也可能只存静态封面。
要一张样本（同一条已验链路：相册/微博存图 → 微信「**文件**」通道发出来 → 我跑探针）。

- 若微博也带尾部：按它的 trailer 再加一条规则（三层兜底的第二层）。
- 若微博不带：**这不是 bug，是内容本身没有动图**，代码不用改，方案到此为止。

### 步骤 1：新增 `app/src/main/java/com/example/wallswitch/gl/MotionSource.java`

职责：给一个 `originals/<id>` 文件，判定有没有实况段，返回 `(offset, length)`，没有返回 null。

要求（逐条）：

1. **纯解析函数与 Android 解耦**：`static long[] parseTail(byte[] last40, long fileSize)` 这类静态方法不碰任何 android 类型，好让 `check/` 夹具能直接复刻（见步骤 6）。
2. 三层兜底，按成本从低到高，命中即返回：
   - ① 读文件**最后 40 字节**，正则 `LIVE_(\d+)` → 小红书格式。
   - ② 读文件**开头 64KB** 找 XMP：`Item:Length`（Google 动态照片 1.0，Pixel/小米/三星）→ 实况段 = `[fileSize-Length, fileSize)`；旧字段 `MicroVideoOffset` 也认。
   - ③ 从 JPEG `FFD9` 之后扫 `ftyp`（OPPO/一加那种只写 Length 不写 Offset 的）→ 实况段 = `[ftyp-4, fileSize)`。
   - 全不中 → null。
3. **必须校验**：算出的起点要 > JPEG EOI 位置，且该处 4 字节之后确实是 `ftyp`；长度 ≤ 文件大小。任一不满足按 null 处理，**绝不返回一个解不开的区间**。
4. 缓存：`Map<String wallpaperId, long[] or null>`，进程内，`ConcurrentHashMap`，null 也要缓存（免得没实况的壁纸每次上屏都读一遍文件）。
5. 暴露给播放侧的入口：`open(File f)` 里用 `FileInputStream` 拿 `FileDescriptor` 交给 `MediaExtractor.setDataSource(fd, offset, length)`，**不把字节抠出来写盘**。

### 步骤 2：新增 `app/src/main/java/com/example/wallswitch/gl/MotionPlayer.java`

职责：实况段 → 帧 → 一块外部纹理。生命周期严格受控。

1. 构造在 **GL 线程**（`SurfaceTexture` 必须绑到已生成的 OES 纹理名上）。
2. `MediaExtractor.setDataSource(fd, offset, length)`，只挑 `video/*` 的那一条轨（**音轨直接不选**）。
3. `MediaCodec.createDecoderByType(mime)` → `configure(format, new Surface(surfaceTexture), null, 0)` → `start()`。
4. 喂帧：`queueInputBuffer` / `releaseOutputBuffer(idx, info.presentationTimeUs)`。
   **订正 ③**：本文原先断言"按时间戳渲染、不 sleep"，装机后被证伪 —— 实测速度快到看不清。
   `releaseOutputBuffer(index, timestampNs)` 的节拍只对 **SurfaceView 那块硬件绑定表面**生效，
   `SurfaceTexture` 没有 display clock，时间戳直接被忽略。改成解码线程按
   `startNs + presentationTimeUs*1000` 自己 `Thread.sleep` 到点再放帧（锚点取第一帧，避免起播 burst）。
5. `setOnFrameAvailableListener`（默认主线程 Handler 即可）里**只做两件事**：置一个 volatile 标志 + 调 `requestRenderHook.run()`。`updateTexImage()` 只在 `onDrawFrame` 里调。
6. 循环模式：**订正 ④** 原设计的 codec 层 `flush()+seekTo(0)+start()` 在 MagicOS 上没跑起来（只播一轮）。
   改成「一轮一个 player」：解码到 EOS 就正常收尾，渲染器发现 `endedAtEos()` 且仍要循环时回调引擎，
   由引擎 `postDelayed(间隔)` 重新起播。附带两个好处：间隔时长可以按用户设置插进去（v3.89），
   以及连续 3 轮一帧都没出就自动停掉重播，不会打转。
7. `stop()`：停 codec、`release()`  extractor/codec、`SurfaceTexture.release()`，但**OES 纹理由渲染器持有和删除**，本类不碰 `glDeleteTextures`。
8. 任何异常一律吞掉前先回调一个 `onFailure(String reason)`，由引擎写进 `engine_stats.txt`（他不用 adb，只能靠落盘取证）。

### 步骤 3：改 `gl/WallpaperRenderer.java`

1. **第二套 program**：fragment shader 顶部加 `#extension GL_OES_EGL_image_external : require`，采样器换 `samplerExternalOES`。顶点着色器与现有那套共用同一份源码字符串。
2. **顶点着色器加一个 uniform**：现在是
   `vTexCoord = vec2(0.5 + aCorner.x*0.5*uTexSpan.x, 0.5 - aCorner.y*0.5*uTexSpan.y)`
   改成 `vec2(uTexCenter.x + aCorner.x*0.5*uTexSpan.x, uTexCenter.y - aCorner.y*0.5*uTexSpan.y)`。
   静态纹理路径传 `uTexCenter = (0.5, 0.5)`，**与今天完全等价**，零观感变化。
3. **取景框归一化**（这是硬约束，别用像素）：从 `Item` 的 `src_w/src_h/crop_l/crop_t/crop_r/crop_b` 算
   `Cx=(l+r)/2/src_w`、`Sx=(r-l)/src_w`、`Cy=(t+b)/2/src_h`、`Sy=(b-t)/src_h`。
   再按「子矩形像素宽高 = Sx*frameW × Sy*frameH」与视口宽高比做 centerCrop，把 `Sx/Sy` 各自乘上裁剪系数。
   这样实况帧（1080×1440）和成品图（从 1440×1920 原图裁出）画的是同一块构图。
4. 新增 `setMotionFrame(boolean available)` / 或一个 `motionTex` 字段 + `drawingMotion` 标志：
   `drawFrame()` 里若正在播实况，画外部纹理并 `updateTexImage()`；否则走现有 current/next/blur 那套。
   **静态 `currentTex` 在播放期间不得删除**，停播要能立刻回到静帧。
5. **与过渡动画互斥**：`setImage(animate=true)` 被调用时先通知引擎停掉实况播放，再进 FADE/BLUR。
   否则「播放中正好定时切到下一张」会出现两套画面抢同一帧缓冲。
6. `release()` / `onSurfaceCreated` 里把外部纹理与第二 program 一起纳入现有的失效重放口径。

### 步骤 4：改 `WallSwitchService.java`

1. `WallEngine.onCreate`（:207-219）里加 `setTouchEventsEnabled(true)`，并覆写 **`onTouchEvent(MotionEvent)`**（**订正**：本文原先写的 `onSurfaceEvent` 是框架内部方法，不可覆写 —— 本地 android-34 的 `javap 'android.service.wallpaper.WallpaperService$Engine'` 查过，Engine 对外只暴露 `onTouchEvent` 与 `onOffsetsChanged`；全项目此前零处调用过 `setTouchEventsEnabled`，是全新的）。
   v3.89 定的手势判定（装机反馈"左右滑也会被判定成长按"后改的）：
   - `ACTION_DOWN` 只记下按下点 + `postDelayed(holdFired, ViewConfiguration.getLongPressTimeout())`（实测 500ms），**不起播**；
   - `ACTION_MOVE`/`POINTER_*` 与按下点比距离，超过 `getScaledTouchSlop()` 即撤掉那条延时任务，并记一次「滑动取消长按」计数；
   - 到点才 `startMotionForCurrent(false)`，`ACTION_UP`/`ACTION_CANCEL` 停播；
   - 没排队也没在播时**一个 `queueEvent` 都不发**（一次滑动几十个 MOVE，全丢给 GL 线程等于白跑几十趟）。
2. 设置读取照 `transitionEffect(ctx)`（:139-142）的写法加一个 `motionMode(ctx)`，常量与 key 加在 :77-83 那一区。
3. 生命周期收口，三处必须停：
   - `onVisibilityChanged(false)`（:253）→ 停播 + release；
   - `onSurfaceDestroyed`（:247）→ 同上；
   - `onDestroy`（:222）→ 同上，且走现有的「引擎线程释放」口径，别在主线程 recycle。
4. `drawCurrent`（:288）解出当前壁纸 id 后，把 id 一并交给实况判定（读尾部那一下放到 `DRAW_EXECUTOR` 线程，**不能上 GL 线程**）。
5. 「循环播放」档：`drawCurrentSafely` 每次重绘后确认一次（进桌面 / 切完图 / Surface 重建都算），当前壁纸有实况段就起播；「按住播放」档：只响应 `onTouchEvent`。
6. **取证**：`engine_stats.txt`（:573 附近那段文案）加三行 —— `onTouchEvent` 收到次数、实况起播次数与绘制帧数、最近一次实况失败原因。
   这是判断「荣耀桌面到底转不转发触摸」的唯一手段。

### 步骤 5：设置抽屉加一行「实况播放」

照「切换动画」那一行的五个触点抄：

1. `res/layout/activity_main.xml:368-391`（`row_transition` + `tv_transition_effect`）旁边加 `row_motion` + `tv_motion_mode`。
2. `res/values/strings.xml:314-317` 附近加 1 个标题 + 2 个选项文案（`实况播放` / `按住播放` / `循环播放`）。
3. `WallSwitchService.java:77-83` 加 key 与两个取值常量（字符串枚举，不用 boolean）。
4. `WallSwitchService.java:139-142` 旁边加 reader，默认值 = `按住播放`。
5. `MainActivity.java:1001-1039` 的 `setupTransitionEffect()` 旁边加 `setupMotionMode()`（`AlertDialog.setSingleChoiceItems`），并在 :217 那一带注册调用。
6. **v3.89 追加**：选「循环播放」后紧接着弹一次间隔选择（0 / 0.5 / 1 / 2 / 3 / 5 / 10 秒，默认 1 秒），
   取消或返回 = 整件事不生效（档也不切，回到原来那一档），免得留下「切到循环但从来没设过间隔」的半套状态。
   间隔存在 `motion_loop_gap_ms`，行文案把间隔一起显示出来（`循环播放 · 间隔 1 秒`），只写「循环播放」的话改完看不出有没有生效。

### 步骤 6：`check/` 夹具

`check/javacheck.ps1` 递归收 `app/src/main/java` 全部 `.java`，新文件自动进类型检查，不用改脚本。本机 android.jar 用的是 **android-34**，`MediaExtractor.setDataSource(fd,offset,length)`(15)、`MediaCodec.releaseOutputBuffer(long)`(16)、`SurfaceTexture.setOnFrameAvailableListener`(19) 全在范围内。

另照 `croprect.cmd` 的体例加一个 `check/motion.cmd` + `check/MotionParseTest.java`（纯 JVM，喂真字节）。
**与 CropRectTest 的区别**：`MotionSource` 刻意不 import 任何 android 类型，所以夹具直接编译**生产源码**，
不再复刻一份算式（复刻就有两处定义、就会漂）。

1. 用样本 B 的真实尾部 40 字节 + 文件大小，断言解析出的 `(offset, length) == (1341120, 799447)`。
2. 断言「尾部 40 字节是普通 JPEG（样本 A 那种）」→ 返回 null。
3. 断言「N 与文件大小矛盾（N > size-40）」→ 返回 null，不返回半截区间。
4. 断言归一化取景框算式：给定 `src_w=1440, src_h=1920` 和一个矩形，在 1080×1440 帧上算出的比例与在 1440×1920 上算的**一致**（证明跨分辨率不漂）。
5. XMP 层：`Item:Length` 两种属性顺序、`MicroVideoOffset` 元素与属性两种写法、顺序不规范时取最后一个 `Item:Length`、以及「有 Length 但全文没提 MotionPhoto 就不猜」。

实际跑到 **35 passed / 0 failed**。这一层当场抓到一个真 bug：XMP 正则拿 `<Item` 当元素名匹配，
而真 XMP 里条目元素是 `<rdf:li>`、`Item:` 只是属性前缀 —— 三条断言直接红，改成属性优先才对。

### 步骤 7：AGENTS.md 与提交

1. 术语表加一行「实况段」（存在哪、什么性质、进不进备份包 —— 答案：随原图字节进，无需新增条目）。**已完成（v3.87）**
2. `app/build.gradle` 版本号 +1、`versionName` 次版本 +1。**v3.87=94 / v3.88=95 已推；v3.89=96 本轮待确认后统一升**
3. 改完跑 `.\check\check.cmd`，期望 `== Java type check PASSED ==`。**已过**
4. **以 diff 展示给用户确认后才 commit**，push 前再确认一次。**待做**

## 3. 发热账（按「只减不增」的验收口径）

| 场景 | 今天 | 加实况后 |
| :- | :- | :- |
| 静止停在桌面 | 零帧（`RENDERMODE_WHEN_DIRTY`） | **不变**，仍是零帧（外部纹理只在播放期间被标脏） |
| 按住播放 | —— | 按住那 ≤2.7 秒里 60 帧/秒 + 一路硬解；松手立刻归零 |
| 循环播放（显式开启） | —— | 可见期间 60 帧/秒常驻 + 硬解常驻。**这是净增，且是用户主动选的** |
| 灭屏 / 离开桌面 | 零帧 | 零帧，且解码器已 release（不是暂停而是拆掉） |
| 一次切图过渡 | 500/700ms 抽帧 | 不变；播放中遇切换则先停播再过渡 |

要点：`RENDERMODE_WHEN_DIRTY` 和 `setEGLConfigChooser(8,8,8,0,0,0)` 这两条发热基座**一行都不改**；抽帧全部由 `onFrameAvailable` 驱动，**不改 CONTINUOUSLY**。
（订正：v3.88 起解码线程要按时间戳 sleep 才不掉速，v3.89 起循环档有一个 `postDelayed` 排下一轮 —— 两者都只在**正在播**那几秒里存在。按住档静止停在桌面仍是零帧、零解码线程；循环档在两轮之间的间隔里解码器已经 release，同样不画帧。）

## 4. 未验证项（装机后才能定，按风险排序）

1. **荣耀桌面转不转发触摸给壁纸引擎** —— 决定「按住播放」是否成立。靠 `engine_stats.txt` 里
   「壁纸触摸事件」那一行是不是 0 判（装机后按住桌面空白处再导出看一眼即可）。
   附带风险：`setTouchEventsEnabled(true)` 有可能干扰桌面滑动/翻页。若干扰，退回「只有循环这一档」。
2. **MagicOS 是否限制壁纸常驻进程里的 MediaCodec**（省电策略杀解码器 / 拿不到硬解）。
3. **观感**：实况段只有 1080×1440，屏幕 1264×2800，播放那 2.7 秒会比静帧**糊一档**（约 1.3 倍上采样）。这是内容本身的分辨率，代码补不了，先有预期。
4. **微博来源到底带不带尾部**（步骤 0）。

## 5. 装机后的三轮反馈（v3.88 / v3.89）

| 反馈 | 真因 | 处理 |
| :- | :--- | :--- |
| 「能播但速度特别快」 | `releaseOutputBuffer(idx, timestampNs)` 对 `SurfaceTexture` 不节流（没有 display clock，时间戳被忽略） | 解码线程自己按 `presentationTimeUs` sleep 到点再放帧（订正 ③） |
| 「从任何 App 回桌面闪一下锁屏壁纸，约 1 秒自己好」 | 起了播但第一帧没到之前 `drawFrame` 直接 return，表面空着 → 合成器露出系统静态壁纸，而系统里那份只有 `setBitmap(FLAG_LOCK)` 写的锁屏图 | 加 `motionExclusive`：第一帧之前照常画静态图。差分证据是用户那句「普通壁纸好像不闪」。**没碰**共用的绘制收口 |
| 「循环档不循环，只有回桌面播一下」 | ①档位只在重绘/触摸时读，选完没反馈；②codec 层 `flush()+seekTo(0)` 在这台机器上没跑起来 | 改成一轮一个 player + 引擎重排（订正 ④）；设置页改完调 `notifyMotionModeChanged()` 立刻接上/断开 |
| 「左右滑还是被判定成长按」 | `ACTION_DOWN` 立即起播 | 长按到点才起播 + 超 touchSlop 撤（见步骤 4） |
| 循环档想歇一口气 | —— | v3.89 新增每轮间隔（0/0.5/1/2/3/5/10 秒，默认 1 秒），选「循环播放」时紧接着弹窗问；这一步取消 = 整件事不生效（档也不切） |

顺带在本轮改动里做掉的两件（与本功能无关，同一批提交）：
导出目录取消导入自动导出（只留两个手动入口，同名覆盖而非叠 `(1)` 副本），以及日志镜像的 URI 按文件名分键
—— 之前桌面日志与锁屏日志共用一个已存 URI，导出目录里那份会被后写的顶掉（私有目录里两份都全，只有镜像串）。

## 6. 明确不做

- 不做锁屏实况（无 API，`setBitmap` 递出去的就是静态位图）。
- 不做视差（`onOffsetsChanged` 仍不实现）。
- 不做实况的播放进度条/暂停按钮/单张开关。
- 不抽独立 `motions/<id>.mp4`，不加 `library.json` 字段。
- 不改 Photo Picker 选择器、不改备份/还原格式。
- 不播声音。
