# 两台手机局域网扫码同步 实施方案

- **状态**：代码已全部落地（阶段 A 局域网同步 + 阶段 B App 内扫码）。`check/lan.cmd` 86 条断言全过、`check.cmd` PASSED、本机 `assembleDebug` BUILD SUCCESSFUL。剩真机验收与提交。
- **文档版本**：v2（v1 把 1592 行成品代码抄进了文档，等于同一份东西两处定义 —— 代码已在源文件里，本文只留决定与契约，不再抄代码）
- **日期**：2026-10-07

**Goal:** 旧手机在本 App 内起局域网服务并显示二维码，新手机在本 App 内扫这个码，走 Wi-Fi 收下整库备份包，然后直接进现有的还原流程；全程不离开 App、不接数据线、不依赖任何外部应用。

**Architecture:** 传输层是手写的极小 HTTP/1.1：旧机 `ServerSocket` 只暴露「一个随机口令路径下的 `info` 与 `file`」两条只读路由；新机用**裸 `Socket`** 当客户端。协议、打包、服务、客户端、预览几何这五块一律不 import android 类型，所以 `check/lan.cmd` 能把生产源码当真源码编进纯 JVM 夹具跑断言（照 `check/motion.cmd` 的先例，不复刻算式）。还原不写第二套：收端只回传一个本地文件路径，交给现成的 `onRestoreZipPicked`。

**Tech Stack:** Java 17；`java.net` / `java.io` / `java.util.zip` / `java.security`（纯 JDK）；`android.hardware.camera2` + `TextureView`；`com.google.zxing:core:3.5.3`（本项目第 6 个依赖、第一个非 AndroidX 依赖）。

---

## 0. 施工中学到的事实（别重新怀疑）

| # | 事实 | 出处 / 证据 |
| :- | :-- | :-- |
| 1 | 本地类型检查的 classpath **只有** `android.jar`，androidx/Material/zxing 全靠 `check/stubs/` 手写桩 → **桩只锁签名，锁不住行为** | `check/javacheck.ps1:57-58` |
| 2 | 它会扫描源码里的 `R.*` 引用**自动生成 R 桩**：引用一个不存在的资源，类型检查照样过，CI 才挂 → 资源必须单独交叉检查（§7 第 5 条给了命令） | `javacheck.ps1:22-44`；施工实测抓到两处 |
| 3 | zxing 3.5.3 里 `PlanarYUVLuminanceSource` 在 **`com.google.zxing` 根包**，不是 `.planar` 子包；`MultiFormatReader.decode` 只声明抛 `NotFoundException`（Format/Checksum 不再出现在签名上）；3.4 之后取点阵是 `getBlackMatrix()` | 用 `javap` 打真 jar 核出来的，本文 v1 写错过 |
| 4 | `BackupStore.copyToCache()` 把入参 `Uri` 整份复制成 `cacheDir/restore.zip`；若入参就是那个文件，`FileOutputStream` 先把它截成 0 字节、`FileInputStream` 立刻 EOF → 现象是失败却报那句含糊的「读不到这个包」 | `BackupStore.java:521-539`；`LanSyncTest.trap()` 三条断言锁着 |
| 5 | 包内大头是无损 PNG，DEFLATE 到底只差 2~5%，所以局域网包用 `Deflater.BEST_SPEED`；STORE 不做 —— 要预填 size+CRC 等于每文件多整读一遍，省下的 CPU 被多出来的 IO 吃回去 | 原 `BackupStore:323-325` 注释 + `LanPackager.DEFLATE_LEVEL` |
| 6 | 明文 HTTP **不需要**动网络安全配置：Android 从 targetSdk 28 起默认禁明文，但那条策略只约束 HTTP 客户端与 WebView，不拦 `java.net.Socket` | 所以收端用裸 Socket；服务端仍是标准 HTTP/1.1，那行地址贴进浏览器照样能下（兜底那条路白留着） |
| 7 | 显示 Wi-Fi 的 SSID 要精确定位权限；判「现在是不是 Wi-Fi」只要 `ACCESS_NETWORK_STATE`（normal 级、装完即有、不弹窗） | manifest 只加了后者，没要定位 |
| 8 | 在字段初始化器里（`onImage = source -> …`）读构造函数的 final 字段，javac 判「variable … might not have been initialized」 | 走一层方法（`deliverText`）就正常了，语义也更清楚 |
| 9 | `getString` 里 `%d` 配 `String`、或占位符数与实参数不符，**本地三关全都查不出来**，真机当场抛 | 施工中抓到两处（`sync_pack_done`、`sync_info_card`），已修 |
| 10 | 本机 `./gradlew` 能跑 `assembleDebug`（实测 1m56s），但**必须 `JAVA_HOME` 指到 corretto-17**，默认 java 是 1.8 会报「Gradle requires JVM 17 or later」 | 实测 |
| 11 | 备份包格式与术语**一字未动**：仍是原图/成品图/缩略图三件，`manifest.json` 那十个字段没加、没改、没删 | `collectEntries` + `manifestBytes` |
| 12 | 荣耀 MagicOS 无 GMS → ML Kit 出局；CameraX 要多带 4 个 artifact 和一片桩，而这里只用「预览流 + 取帧」这一件事 | 定稿依据 |

---

## 1. 定稿的共识

| # | 事项 | 定稿 | 代价（明写） |
| :- | :-- | :-- | :-- |
| 1 | 传输 | 局域网，全程 App 内 | 两台机要同一 Wi-Fi；被 AP 隔离时改「旧机开热点、新机连」 |
| 2 | 配对 | 旧机屏上二维码 + 新机 App 内相机扫 | 新增 `zxing:core` + 16 个手写桩；**解码行为只能装机验** |
| 3 | 兜底 | 码下面那行地址可长按复制、也可手输 | 多一个解析入口，省掉「扫不动就整条路死」 |
| 4 | 压缩档 | `BEST_SPEED` | 体积 +2~5%，换 CPU 大幅减少 |
| 5 | 包从哪来 | ≤24h 且文件还在的 cache 包直接分享；否则现场重打 | 重打几十秒 + 一份 cache；好处是不碰用户导出目录、不要求先设导出目录 |
| 6 | 相机 | Camera2 + TextureView 手写，Activity 锁竖屏，只喂 640×480 级灰度帧 | `CameraScan` 约 360 行；预览框高度夹到屏高 62%（切边不变形，夹具验过偏离建议比例那两种情形） |
| 7 | 断点续传 | **不做**，失败删干净 | 传到 90% 断了要整包重来；换来服务端不实现 Range、客户端少约 80 行 |
| 8 | 增量同步 | **不做**，下一版 | 本版每次都是整包 |
| 9 | 安全 | 8 位随机口令、只读、不列目录、页面活着才有效、一键停止；明文不加密 | 同网段可嗅探；口令猜不到且只在共享期间有效 —— 个人 App 接受，不上自签 HTTPS |
| 10 | 提交 | 攒批；用户说提交才 commit，版本号最后一次 3.95→3.96、versionCode 102→103 | 施工全程未 commit |

---

## 2. 线上格式与命名

**二维码内容**（只放地址，其余全从 `info` 拿）：`http://192.168.1.23:49317/7f2k9zq1`

- 端口 `new ServerSocket(0)` 由系统分配（躲开写死端口撞车），`getLocalPort()` 回填进 URL。
- 口令 8 位，字母表 `abcdefghjkmnpqrstuvwxyz23456789`（剔掉 `0O1li`，手输不容易看错），`SecureRandom`。
- 刻意不把清单塞进码：QR 越短点越粗、越远越好扫，3KB 清单会糊成扫不动的芝麻。

**`GET /<口令>/info` 响应体**（`k=v` 行文本，不用 JSON —— `org.json` 属于 android，引进来 `LanPair` 就脱离 JVM 夹具，而且撞「org.json 两种尾逗号口味」的旧坑）：

```
v=1
name=lan-share.zip
time=2026-10-07 14:22:31
items=38
images=38
originals=36
thumbs=38
bytes=631244800
sha256=9f2c…64 位十六进制…
app=3.96
```

值里不许有换行与等号（编码侧直接截断），未知字段忽略（前向兼容），`v` 不匹配、`bytes` 缺失、`sha256` 短于 32 位都当「拒绝连接」而不是硬收。

**路由**：`GET|HEAD /<口令>/info` → 200 `text/plain`；`GET|HEAD /<口令>/file` → 200 `application/zip` + `Content-Length`；其余（口令错、路径含 `.` 或 `%`、非 GET/HEAD）→ 404/405 **不解释原因**。一律 `Connection: close` + `Cache-Control: no-store`，一连接一请求，不实现 keep-alive。

**落盘名字**：

| 位置 | 谁写 | 谁清 |
| :-- | :-- | :-- |
| `cacheDir/lan-share.zip` | 旧机打包 | 下次重打覆盖（24h 内复用） |
| `cacheDir/lan-receive.zip.part` | 新机下载中 | 成功改名、失败删除 |
| `cacheDir/lan-receive.zip` | 上一步改名而来 | **还原成功后由 MainActivity 删**；绝不叫 `restore.zip`（事实 #4） |
| prefs(`settings`) `lan_share_at/bytes/sha256/items/images/originals/thumbs` | 旧机打包时 | 一组读，缺任一就当没有现成包 |

---

## 3. 磁盘与发热这笔账

**能量**：新增只有三块 —— 打包一次 CPU、相机预览几十秒、一次局域网收发。全部只在 Activity 前台存在：扫到码立即关相机，`onDestroy` 立即 `server.close()`。不新增 WorkManager 任务、闹钟、广播接收器、Service，不申请 WakeLock（常亮用 `FLAG_KEEP_SCREEN_ON`，随窗口自动释放）。空闲时的定时/切换/GL 链路一行未改。对表现状「手动导出 → 拷贝工具搬过去 → 选文件还原」，省掉整包在闪存上**多写两遍**，属于偏减的那一头。

相机侧两个具体省钱点：预览只取 640×480 级 YUV，解码节流到每 250ms 一帧（其余帧 `acquireLatestImage()` 拿到就 `close()` 丢掉），单帧解码量级 5~15ms；不申请高分辨率流、不做拍照。

**磁盘峰值**（600MB 为例）：

| | 现状（SAF 还原） | 本方案收端 |
| :-- | :-- | :-- |
| 峰值 | zip + cache 副本 + 解出的图 ≈ **1.8GB** | 下载的 zip + 解出的图 ≈ **1.2GB** |
| 为什么 | `copyToCache` 又复制一份 | `localZip()` 让本地文件直接进 `ZipFile` |

发端多占一份 `lan-share.zip`，靠 24h 复用摊掉重复打包；打包前先按 `getUsableSpace()` 预检，不够就明说（不等 IO 抛一个含糊的异常）。

---

## 4. 代码落在哪（本文不再抄代码）

| 文件 | 责任 | android-free |
| :-- | :-- | :-- |
| `LanPackager` | 条目列表 → zip 流；顺手算 SHA-256 与字节数；`sha256Of(File)` 给复用现成包用 | ✅ |
| `LanPair` | URL 造/解析、口令、`info` 编解码、本机 IPv4 挑选与优先级、点阵铺像素 | ✅ |
| `LanClient` | 裸 Socket 客户端：取 info、收到 `.part`、校验、改名、任何异常不留残 | ✅ |
| `LanHttp` | 手写 HTTP/1.1 服务端：accept 循环、口令路由、流式发文件、进度回调 | ✅ |
| `ScanTransform` | 预览矩阵数学，返回 `float[9]`；三条不变式 | ✅ |
| `BackupStore` | `collectEntries` 成为打包清单的唯一写手；`backupToCache`；`localZip` 修自覆盖 | ❌ |
| `SyncHostActivity` | 旧机页：打包 → 起服务 → 码 + 地址 + 实时状态 + 停止/重打 | ❌ |
| `SyncJoinActivity` | 新机页三态：扫 / 问（包信息卡）/ 收（进度）；只回传路径 | ❌ |
| `CameraScan` | Camera2 开关、帧节流、Y 平面喂 zxing；扫到立即关相机 | ❌ |
| `QrBitmap` | `BitMatrix` → `Bitmap`（算式在 `LanPair.qrPixels`，有夹具） | ❌ |
| `MainActivity` | 抽屉两行入口；收端回传的包走现成的回显+确认+还原 | ❌ |
| `check/LanSyncTest` + `check/lan.cmd` | 86 条断言，编的是生产源码本体 | — |
| `check/stubs/com/google/zxing/**`（16 个） | 让 `check.cmd` 过得去；签名以 `javap` 真 jar 为准 | — |

三个容易改错的接口约束：`LanClient.download(Target, Info, File, …)` 的期望摘要**当参数传**（不用进程级全局，两处下载不会互相串）；`LanPackager.pack` 的调用方负责关流，它只 `finish + flush`；`LanHttp.Server.close()` 必须同时关监听 socket 与当前连接 —— 写方向没有 `SO_TIMEOUT`，靠关连接才能打断一条卡住的发送。

---

## 5. 施工顺序（为什么客户端排在服务端前面）

阶段 A 先做「不装相机也能同步」的那半（协议、打包、还原三块先落地并真机验过），阶段 B 只往上面接一个扫码页 —— 这样真机出问题时能立刻分辨是协议层还是相机的锅。阶段 A 结束时功能已经可用（旧机给地址、新机长按复制粘贴）。

`LanClient` 排在 `LanHttp.Server` 前面，是为了让服务端的往返测试两端都跑真码，而不是在夹具里复刻第二个客户端（复刻就是两处定义）。`check/lan.cmd` 因此按 `LanPackager → LanPair → LanClient → LanHttp → ScanTransform` 的顺序编译。

---

## 6. 验证状态（哪些是真验过的）

| 层 | 命令 | 结果 | 说明 |
| :-- | :-- | :-- | :-- |
| 协议 / 打包 / 几何 | `cmd /c check\lan.cmd` | **86 passed, 0 failed** | 编的是生产源码本体，不是复刻算式 |
| 全项目类型 | `.\check\check.cmd` | **PASSED** | 含 4 个新 android 类与 16 个 zxing 桩 |
| 真依赖 + 资源链接 | `JAVA_HOME=…corretto-17 ./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** | 桩与真 zxing 签名一致、`@string/@color/@id` 全链得上 |
| 资源引用交叉 | §7 第 5 条那条命令 | 只剩两个 `android.R.*` 假阳性 | 事实 #2：`check.cmd` 查不出缺资源 |
| **相机解码 / 预览观感 / 真机吞吐 / 文案排版** | — | **未验** | 本地只有桩（事实 #1），必须装机 |

夹具里最值得记的三条断言：以 `CRLFCRLF` 开头的响应体（证明头体切分没吃错字节）、口令错与路径含点号一律 404、`close()` 之后端口不再监听。

---

## 7. 后续改动要守的规矩

1. **打包只有一处定义**：加/删备份条目只改 `BackupStore.collectEntries`；`LanPackager` 只管搬字节，别在里面加业务字段。
2. **收端包名不许改成 `restore.zip`**：那是 `copyToCache` 的目标名，改了就撞事实 #4，`LanSyncTest.trap()` 在锁这条。
3. **改 `info` 字段就 +1 `LanPair.PROTOCOL_V`** 并在 `parseInfo` 里拒绝旧版本；不许靠「字段缺失就默认」蒙。
4. **新加 android-free 的类要顺手加进 `check/lan.cmd` 的文件列表**；引新库要补桩 + `javap` 真 jar 核签名（这一步不能跳，事实 #3 就是证据）。
5. **资源交叉检查要跟着跑**（`check.cmd` 查不出缺资源）：

```bash
cd app/src/main && for t in string id color drawable layout xml; do
  for r in $(grep -rho "R\.$t\.[a-zA-Z0-9_]*" java | sed "s/R\.$t\.//;" | sort -u); do
    case $t in
      string)   grep -q "name=\"$r\"" res/values/strings.xml || echo "MISSING R.string.$r";;
      color)    grep -q "name=\"$r\"" res/values/colors.xml  || echo "MISSING R.color.$r";;
      layout)   ls res/layout/$r.xml >/dev/null 2>&1         || echo "MISSING R.layout.$r";;
      id)       grep -rq "@+id/$r\"" res/layout             || echo "MISSING R.id.$r";;
      drawable) ls res/drawable/$r.* >/dev/null 2>&1         || echo "MISSING R.drawable.$r";;
      xml)      ls res/xml/$r.xml >/dev/null 2>&1           || echo "MISSING R.xml.$r";;
    esac
  done
done
```

`android.R.*` 的命中是假阳性，忽略。
6. **数字必须带标签**：字节数一律走 `SyncHostActivity.human()`（B / KB / MB / GB），文案里别裸报数字；`%d` 只配 int/long，别传 `String.valueOf(...)`（事实 #9）。
7. **服务与相机都不许越过页面生命周期**：`onDestroy` 里 `server.close()` / `camera.stop()` 是唯一收口，别加后台 Service 去「让它更稳」。

---

## 8. 失败场景与文案对照（不许出现「出错了」）

| 场景 | 机制 | 界面给的话 |
| :-- | :-- | :-- |
| 路由器开了 AP 隔离 | TCP 连不上 | 「多半不在同一个 Wi-Fi，或被路由器隔离（改成旧机开热点、这台连它）」 |
| 旧机没连 Wi-Fi 也没开热点 | 没有可用私网 IPv4 | 「这台机没找到可用的局域网地址（先连 Wi-Fi 或开热点）」 |
| 旧机点了停止共享 / 页面关了 | 端口不再监听 | 「对方端口没开：那边可能已经点了「停止共享」，或者这个码过期了」 |
| 口令不对 / 路径被改 | 404 且不解释 | 「对方没有这个共享：口令不对，或者那边已经点了「停止共享」」 |
| 两台机 APK 版本不同 | `v` 字段不匹配 | 「对方用的是第 N 版协议，这台机只认第 1 版：两台手机装同一个安装包」 |
| 传输被中断 | 收到的字节 ≠ `Content-Length` | 「传输中断：收到 X 字节，应有 Y 字节，再点一次重收整包」+ 删 `.part` |
| 包被改坏 | 摘要不符 | 「包在路上下了个坏版本（校验摘要不符），已丢掉，重收一次」+ 不留残 |
| 新机 cache 不够 | 写不下或改名失败 | 「收好了但改不了名（磁盘可能满了）」 |
| 旧机空间不够打包 | 打包前预检 | 「地方不够：还需要约 N MB」—— 不烧那几十秒 CPU |
| 相机被别的 App 占着 | `onDisconnected` / `onError` | 「相机没打开：…」，同时把提示换成「可以用手动输入地址」 |
| 用户拒给相机权限 | `RequestPermission` 返回 false | 「没给相机权限，扫不了码；可以用下面「手动输入地址」那条路」 |
| 新机在用移动数据 | 不在 Wi-Fi 上 | 扫态顶部红字常驻「现在用的是移动数据，连不上对方：请先连 Wi-Fi」 |
| 二维码画不出来 | `QrBitmap.encode` 返回 null | 界面仍显示可复制的地址，功能不死 |

---

## 9. 真机验收清单（建议顺序：先手输、后扫码）

先走「地址长按复制 + 对方手输」那条（不碰相机），确认协议与还原通了，再验扫码 —— 这样相机出问题时可以确定不是协议层的锅。

- [ ] **老链路回归**（我改了共用的打包代码，这条必须回验）：旧版那套「备份整库到导出目录 → 从导出目录选包还原」各跑一遍，
      确认包里的条目、张数、还原后的标题/库归属/槽位/定时与以前一致。新包体积可能比以前大 2~5%（`BEST_SPEED` 的已知代价，不是坏了）
- [ ] 旧机进「共享给另一台手机」：那行「刚打好的包：元数据 N 条、成品图 N 张、原图 N 张、缩略图 N 张，整包 X MB」读数记进 §10
- [ ] 新机「从另一台手机接收」→ 手动输入地址 → 能不能看到对方的包信息卡（大小与张数应与旧机一致）
- [ ] 收到 100% 后是否自动弹出**现成的**还原确认框；还原完首页库是否原样、`cache/lan-receive.zip` 是否已被删
- [ ] 差分提问 ①：第一次连不上时，旧机屏上有没有跳出「已连上 …」？（有 = 网络通了、服务侧问题；没有 = 网络不通，走热点）
- [ ] 差分提问 ②：同一 Wi-Fi 不通时，改成旧机开热点、新机连上再试 —— 通不通？（通 = AP 隔离，文案已给对方向）
- [ ] 相机：20~40 厘米正对能不能一次扫上；侧 30° / 暗处呢
- [ ] 中途把新机压后台再回来，是干净报错还是卡住
- [ ] 旧机点「停止共享」时新机正在收，报的是哪句
- [ ] 症状先归因再动手：任何一条不对，先确认装机版本号是这一版，再跑 `cmd /c check\lan.cmd` 看协议层是否已被本地证伪

---

## 10. 待补的实测数字（填完就是 §1 第 4 条的定稿依据）

| 项 | 数字 | 在哪儿看 |
| :-- | :-- | :-- |
| 整包大小 / 张数 | 待填 | 旧机那行「刚打好的包：… 整包 X MB」 |
| 打包耗时 | 待填 | 进页到那行出现之间 |
| 局域网吞吐（MB/s） | 待填 | 两边进度条读数 |
| 扫码距离与角度容限 | 待填 | 真机 |
| 压后台 / 息屏的表现 | 待填 | 期望是干净报错，不是卡住 |

## 11. 本版刻意不做

- **增量同步**：每次整包。要做就得给 `manifest.json` 加逐条条目清单（现在只有计数），并按「序列化只能一处定义」动 `collectEntries` —— 单开一版。
- **断点续传（HTTP Range）**：失败就删干净重来。
- **加密 / HTTPS**：明文 + 8 位随机口令 + 只在共享期间存活。
- **页面关闭后继续传**：不养前台 Service，那是往发热账上加东西。
- **第二套还原代码**：一律回 `MainActivity.onRestoreZipPicked`。
