# 一键设置（钉住一张到两面）+ 撤回 —— 契约

状态：设计已定稿（用户 2026-10-09 逐条点头，图标选图钉，撤回落点选"同一颗键两态"，系统锁屏副本不做）。
改之前先读第 3、4 节 —— 顺序与"什么不进快照"是有理由的。

设计图：`docs/mockups/6_notif_pin_key.svg`（真实 dp：浅色/深色、未配置态、新键 6 倍、高度账）。

## 1. 谁是权威

| 东西 | 权威 | 说明 |
| :- | :-- | :-- |
| 钉住的是哪一张 | prefs `settings` 里 `pin_lib` + `pin_wallpaper` | 只有 `PinnedWallpaper` 读写。不进 `library.json`，不算槽位；因为在 prefs.xml 里，备份包那条现成链路自动带上 |
| 撤回要退回去的那一整套 | prefs `settings` 里 `pin_undo`（一个 JSON 字符串） | 只有 `PinnedWallpaper` 序列化。**每一次"钉"都重写**：能走到钉这一步就说明此刻不是已钉住态（是的话那颗键点下去走撤回），所以"能退"永远退到这一次钉之前。撤回后即清空，清 pin 也连带清空 |
| 槽位（哪面用哪个库 / 模式 / 间隔 / 暂停） | `LibraryStore` 的键 | 撤回要"只写状态、不排程不记日志"，故新增 `restoreSlot` 一个原语；键名仍归 LibraryStore 自己 |
| 某库某面的切换进度四件套 | `Switcher` 的 `p_<lib>_<h\l>_*` | 撤回要原样回写，故新增 `captureProgress` / `restoreProgress`；JSON 只在这两个方法里成形 |
| 每面的 `last_run` / `next_trigger` / 周期格子 | `TimerScheduler` | 新增 `restoreSchedule`：接得回半截倒计时就接，接不回就重新起算一整轮 |

依赖方向：`PinnedWallpaper` → `LibraryStore` / `Switcher` / `TimerScheduler` / `TakeoverManager` / `WallSwitchService`。
四个既有类都不认识 `PinnedWallpaper` 的快照格式。

## 2. 通知那颗键

- 位置：第二行左侧那一列由「一个角标」变成「竖着叠角标(28dp) + 新键(42×24dp)」，间距 2dp。第二行高 = `max(44, 54) = 54dp`，整卡 128 → 约 **138dp**（146 是 AOSP 收起态裁切线，本机只实测过 128 不被裁 / 164 被裁）。
- 硬约束：那一列的容器必须是 `LinearLayout`（带 `@RemoteView`）。裸 `<View>` 不在 RemoteViews 白名单里，inflate 抛错的后果是整条通知被 NMS 以 REASON_ERROR 删掉。
- 可见性：没配 `pin_wallpaper` → `GONE`，左列回到只有角标，整卡回到 128dp。
- 两态（同一条 action `NOTIF_PIN`，钉还是撤**在点击时现读**，与"通知显示哪一面"同一套同源纪律）：
  - 浅底（现成 `notif_chip_bg`）+ 正文色图钉 = 没钉住，点 = 钉
  - 实心 brand 底（新 `notif_pin_bg_active`）+ 白色图钉 = 已钉住，点 = 撤回
  - 切换中：染次级色 + `setOnClickPendingIntent(id, null)` 锁掉，跟另外三颗同一把锁
- 判据 `canUndo`：有快照 **且** 两面的槽都还指着 `pin_lib` **且** 两面 `_current` 都还是 `pin_wallpaper`。任一条不满足（含"在抽屉换了另一张"）→ 键自动落回"钉"，可幂等重钉。

## 3. 钉住（apply）的契约

前置检查按序，任一条不过**不动任何状态**，直接返回错误码：

| 序 | 检查 | 错误码 |
| :- | :-- | :-- |
| 0 | 没配 pin | 静默返回（这种状态下键本就不画） |
| 1 | 库还在、这张还在那个库里 | `pin_missing` |
| 2 | 接管总开关开着 | `takeover_off`（复用现成文案） |
| 3 | 引擎已被系统选中 | `engine_inactive`（复用现成文案） |

然后：

1. 重写快照（每一次钉都写。"只在为空时写"那种留法会被「钉住 → 删掉那张 → 指针自动推进」这条链绕开，旧快照既不可达也不会被覆盖，往后每次撤回都退到不相干的历史上）
2. 逐面：该面槽位不指向 `pin_lib` 才 `setSlotLib`（**顺序不能颠倒**：`setCurrent` 开头就查 `ownsScope`，槽没换过去它是直接 false）
3. `setCurrent(pin_lib, wp, 桌面)`；false 就带着 `Switcher.lastError()` 返回
4. `setCurrent(pin_lib, wp, 锁屏)`；false **不回滚**，记下 `lock_not_applied` 继续走
5. 两面 `setPaused(true)`
6. 成功不弹 Toast：通知封面/标题当场变这张、「下次 …」变「已暂停」就是反馈

不进快照、因此也不会被撤回弄坏的东西：
- 间隔与模式 —— `setSlotLib` 只在"那一面本来是空槽"时才把库级配置继承进去，非空槽原封不动（快照照样记，兜那一面空槽的情形）
- 旧库的进度四件套 —— 进度键按「库 × 面」分，`setCurrent` 只写 pin 库那套；旧库≠pin 库时旧库根本没被碰过，槽位一换回去屏上那张自己就回来了

已知副作用（写在这就是为了别当成 bug）：
- 原来占两面的那两个库被顶掉，通知第一行库名会变；抽屉/小组件随时能改回来
- 切换日志多四行：两面各一行「换库」+ 各一行「暂停」
- `setSlotLib` 各排一次定时、紧接着 `setPaused` 各撤一次 → **净新增周期唤醒 0**（发热账：只有点下去那一刻干一次活）
- 清 pin（抽屉长按）连带清快照 → 撤回入口消失，但**不还原壁纸**。想恢复轮播是按暂停键，不是清 pin

## 4. 撤回（undo）的契约

顺序：回写两面槽位 → 回写旧库进度四件套 → 屏上退回（桌面只标脏让引擎现读，锁屏对旧库那张重新 `setBitmap`，几秒，走现成 400ms「切换中」）→ 排程退回 → 清快照 → 刷通知与小组件。

排程退回三分支：
- 快照里那面本来暂停 → `cancelScope`，没有「下次」可言
- 快照 `trigger - now > 0` → 回写 `last_run` 与 `next_trigger`，用 REPLACE 把格子首次延迟设成剩余 → **接回半截倒计时**，显示值精确；真醒点仍由 WorkManager 定（与现成口径一致：宁可晚，绝不早）
- 快照 `trigger - now <= 0`（钉着期间那个点已过） → 落回现成的 `restartScope`：从此刻重起一整轮。不补切，因为"撤回"不该立刻把刚退回去的图换掉

回退时如果旧库已被删（他钉住期间把那个库删了）：当作"本来没库"处理（空槽 + 撤任务），不把指向已删库的槽写回去 —— 否则常驻通知会因"两面都没库"整个消失。

唯一补不回来的洞：某一面**本来没有占位库**（不接管）时，一键设置仍会把它钉上；撤回能把槽退回"空"、桌面退回纯色，但**锁屏原本那张系统壁纸我们手上没有副本**，屏上只能停在钉住那张，并如实弹一句。用户已确认不做系统壁纸副本（他自己两面都有库）。

"整段退回"的口径：撤回退到"最后一次钉之前"，中间他手动切过的图、改过的槽位配置一起退。不做作废判定 —— 哪些手动态该算被保留没有边界，加钩子会污染 `setSlotLib` 那条已验收链路。

## 5. 抽屉入口

- 位置：卡片四「保存当前壁纸」下面一行。标题「一键设置壁纸」，副标题 = 「库名 · 标题」或「未设置」
- 点按 = 两级 `MaterialAlertDialogBuilder`（不配 `setMessage`），行布局共用现成 `item_slot_lib`：先选库（当前那个打勾）→ 再在这库里选那张
- 缩略图**不预解全部**（一个库可能上千张，开弹窗时一次解完等于自己卡住自己）：沿用首页那套 LruCache 现解（`bindThumb` → `libAdapter.thumbFor`），不新立第三种取档
- 长按 = 直接清除、副标题当场变「未设置」，不弹确认（重选一张才几秒，可逆）
- 选好那一句提示分两档：常驻通知被关掉（我们的开关关或系统总闸关）时那颗图钉根本不画出来，选好等于白选，所以如实说「打开它才有那颗图钉可点」，而不是只报"已记下"

## 6. 失败文案

| 错误码 | 屏幕上 |
| :- | :-- |
| `pin_missing` | 一键设置的那张已经不在了，去抽屉重选一张 |
| `takeover_off` / `engine_inactive` / `not_applied` | 复用现成文案 |
| `lock_not_applied` | 桌面已设为这张，锁屏没设上（两面已停住） |
| `lock_left`（撤回时） | 这一面本来没接管，屏上这张退不回去，要手动换回 |

## 7. 动的文件

新增 `PinnedWallpaper.java`、`ic_notif_pin.xml`、`notif_pin_bg_active.xml`；
改 `LibraryStore`（+1 原语）、`Switcher`（+2 原语）、`TimerScheduler`（+2：`restoreSchedule`、带首次延迟的排程内部入口）、`StatusNotifier`、`NotifActionReceiver`、`notification_status.xml`、`activity_main.xml`、`MainActivity`、`strings.xml`。
版本号按攒批纪律留到提交时统一 +1（当前 HEAD 109/3.102，下一档 110/3.103）。

## 8. 验证到哪一步算完

- `check\check.cmd` == `== Java type check PASSED ==`（只保类型；它编的是本机真 android.jar，所以 `RemoteViews.setBackgroundResource` 这类 API 存在性它能验）
- 新 drawable / 新 id / RemoteViews 布局都在 check.cmd 盲区 → 再跑一次 Gradle `assembleDebug` 确认 aapt2 资源链接过
- 资源交叉检查：Java 里用到的 `R.id`/`R.drawable`/`R.string` 与 XML 逐个对上
- 装机待验三条：138dp 会不会被裁（被裁就先收封面 40→36，再收键 24→20）、42×24 按不按得到、点完两面是否真落在那张并停住、撤回后屏上与「下次」是否回到钉之前那一档
