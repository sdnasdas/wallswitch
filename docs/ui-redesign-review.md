# UI 改造 Review（v3.14 未提交版本）

> **审查对象**：工作区里**尚未提交**的 v3.14 改动（基线 `v3.13` / `26e6919`）。
> **审查方式**：跑门禁 + 逐文件读码 + **用脚本解析布局 XML 树** + 核对 `docs/ui-redesign.md`
> 的验收清单。行号基于**当前工作区版本**，改动后可能偏移。
> **门禁结果**：`check.cmd` → PASSED；`aapt2 compile --dir app\src\main\res` → exit 0；
> 资源引用全量比对（附 A-2）无缺失。**但门禁查不出下面 R1 和 R2。**

---

# 复评（第二轮）：修复核对

**结论：R1 / R2 / R3 / R5 / R7 已修好并逐条验证通过；R4 / R5b / R6 未做（属"建议修"，
可接受）；另发现两个新的小瑕疵（N1 / N2，⚪ 级）。门禁全绿。**

验证方式：不是看 diff，而是**直接验证问题是否消失**（脚本 + 读码）。

| 编号 | 状态 | 验证证据 |
| --- | --- | --- |
| **R1** 嵌套卡片 | ✅ 已修 | 附 A-1 脚本：7 个布局全部 `OK`；XML 树显示抽屉是 **5 张同级卡片**（接管情况 / 自动切换 / 导出与日志 / 桌面图标预览 / 引擎与后台） |
| **R2** 重编后不生效 | ✅ 已修 | 新增 `Switcher.reapplyIfCurrent(ctx, libId, wallpaperId)`：两个范围各自判断"是否在屏上"，覆盖→桌面 `notifyWallpaperChanged()`／锁屏 `setLockFromFile()`，删除→清指针并推进下一张；`EditActivity` 与 `MainActivity` 删除回调都接上了，且**都用 `item.libId` 反查**（绕开了重编模式下 Intent 不带 libId 的坑），都在后台线程 |
| **R3** 改名时返回退出 | ✅ 已修 | 引入 `renamingLibId` 状态 + `cancelRename()`；返回链路里"正在改名"排在"回库列表"之前；`commitRename` 加了重复提交防抖（editor action 与失焦各触发一次）+ `recycler.post(this::refreshLibs)` 延后重绑 |
| **R4** bind 里主线程 IO | ⬜ 未做 | `LibAdapter.onBindViewHolder` 仍在调 `WallpaperStore.loadByLib()`（1322/1331 行）。可接受，后续想优化再动 |
| **R5** 触摸目标 | ✅ 部分 | 铅笔/垃圾桶/齿轮已全部改成 **48dp**；「删除后 Snackbar 撤销」未做（可选项） |
| **R6** 死字符串 | ⬜ 未做 | `swipe_delete_hint` 仍定义在 `strings.xml` 且无引用 |
| **R7** `overwrite` 边角 | ✅ 已修 | 覆盖后 `Files.deleteIfExists(... LEGACY_FULL_EXT)` 清掉遗留 `.jpg`；缩略图 `recycle()` 带 `thumb != bitmap && !thumb.isRecycled()` 判断（`scaleToFit` 没缩时会返回入参本身，这个判断是对的） |
| **R8** 流程 | — | 无需动作 |

### 第三轮修复回填（实现者记录：修正上表 R4/R6 两行 + 处理 N1/N2）

上表是复评当时的快照，其中 **R4 / R6 两项在复评之后已补做**：

| 编号 | 状态 | 证据 |
| --- | --- | --- |
| **R4** | ✅ 已做 | `LibAdapter` 新增私有行模型 `Row{lib, count, thumbId}`：`setItems()` 里对每个库只读一次 `WallpaperStore.loadByLib()` + 解析当前指针（桌面优先、锁屏次之、库内第一张兜底），`onBindViewHolder` 只读 `rows.get(position)`，滚动不再做文件 IO。`WallpaperAdapter` 的 bind 内同步解码**保留**（评审已认定为既有模式 + 4MB `LruCache` 兜底，挪后台要处理位置漂移，收益不抵改动） |
| **R6** | ✅ 已删 | `strings.xml` 移除 `swipe_delete_hint`（原引用点随左滑删除一起消失，全仓库无引用；A-2 资源比对无缺失） |
| **N1** | ✅ 已修 | `LibAdapter.setItems()` 重绑时 `renamingLibId = null`（`notifyDataSetChanged` 会把改名中的 EditText 换回 TextView，标记必须同步清，否则返回键多拦一次）；`showWallpaperPage()` 离开库列表页时同样清 |
| **N2** | ✅ 已修 | `EditActivity.onConfirm` 在 `finish()` 之前取 `final Context appCtx = getApplicationContext()`，线程内改用 `WallpaperStore.get(appCtx, …)` / `Switcher.reapplyIfCurrent(appCtx, …)`（不再拿 Activity 当 Context）；`MainActivity` 壁纸删除回调里那个同类线程也一并改成 `getApplicationContext()` |

另外，**「四、需要用户拍板」已定**：用户选择**直接废除、不做数据迁移**（方案 A）。README 的 v3.14 条目已补
「**升级注意（v3.13 → v3.14，老用户）**」5 行用户向说明：老库沿用自己原有的 `enabled`/间隔 ⇒ 升级后打开 App
即开始到点自动切、`timer_enabled` 不再读取但也不清除、想停就关掉那个库的「启用」开关（关掉即退出接管）。
复评对该段逐条核对通过，无需再改。

## 本轮新发现（⚪ 级，不阻塞）

**N1 —— `renamingLibId` 没有在所有路径上清掉。** 它只在 `enterRename` 里置位、在
`commitRename` / `cancelRename` 里清空，`setItems()` / 切页时**不清理**。实际上极难触发
（点行内其他位置、点开关、长按别的行都会先让 EditText 失焦 → `commitRename` → 清空），
但万一留下脏值：库列表页按返回会去执行一次"取消改名"而不是退出，用户要按两次。
建议在 `setItems()` 或切页时顺手 `renamingLibId = null`。

**N2 —— `EditActivity` 的重新上屏线程持有 Activity。** 那段 `new Thread(...)` 里用的是
`this`（`EditActivity`），而紧接着第 178 行就 `finish()` 了 —— 线程会比 Activity 活得久，
用已销毁的 Activity 当 Context（虽然 `getSharedPreferences` / `getFilesDir` /
`WallpaperManager.getInstance()` 都是应用级的、功能上没问题，但 lint 会报 context leak）。
建议改用 `getApplicationContext()`。

## README 的「升级注意」核对（面向用户的说明，逐条对过代码）

- 字段名 `enabled` / `home` / `lock` / `interval_seconds` / `mode` —— 与
  `LibraryStore` 的 JSON 读写（129-135 行 / 65-70 行）**完全一致** ✅
- "v3.13 的默认库是 `enabled=true` + 30 分钟间隔" —— `migrateLegacyToLib` 里
  `def.enabled = prefs.getBoolean("home_enabled", true) || ...`（默认 true）、
  `DEFAULT_INTERVAL_SECONDS = 1800`（30 分钟）✅
- "不做任何数据迁移、`timer_enabled` 不再读取" —— 与 `TimerScheduler.isTimerEnabled()`
  恒为 `true` 一致 ✅
- 语义提醒：**新装**用户的新库是 `enabled=false`（`LibraryStore.create`），所以新装不会
  自动开切；只有**从旧版升级**且默认库仍启用着的用户会立即开始到点自动切 —— 与 README
  写的一致，且已在文档里向用户交代清楚 ✅

---

## 结论速览（第一轮原始记录，保留）

| 编号 | 级别 | 问题 | 位置 |
| --- | --- | --- | --- |
| **R1** | 🔴 必现界面 bug | 抽屉「自动切换」卡片**嵌套在**「接管情况」卡片内部，两块会重叠绘制 | `activity_main.xml` 222/225/282 行 |
| **R2** | 🟠 功能缺口 | 重新裁剪覆盖后**不通知引擎/不重设锁屏**，屏幕仍是旧图；删掉当前壁纸同理 | `EditActivity` 确认分支、`MainActivity` 删除回调 |
| R3 | 🟡 体验/健壮性 | 改名时按返回键会退出 App；`commitRename` 在焦点回调里 `notifyDataSetChanged` | `MainActivity` 141-162 / 1247-1257 |
| R4 | 🟡 性能 | `onBindViewHolder` 里主线程读 JSON / 同步解码缩略图 | `MainActivity` 1322-1345 / 1465-1467 |
| R5 | 🟡 体验 | 触摸目标 40dp（低于 48dp 建议），铅笔与垃圾桶只隔 16dp，删除无撤销 | `item_wallpaper.xml`、`item_library.xml` |
| R6 | ⚪ 清理 | `swipe_delete_hint` 已成死字符串 | `strings.xml` |
| R7 | ⚪ 边角 | `overwrite` 不清遗留 `.jpg` 全图、未 recycle 缩放位图 | `WallpaperStore.overwrite` |
| R8 | ⚪ 流程 | 删除 `SwipeRevealLayout.java` / `item_action_bg.xml` / `menu_main.xml` 前未按文档要求确认 | — |

---

## 一、必须修

### R1 —— 抽屉嵌套卡片，接管情况会被盖住（必现）

**现象**：打开设置抽屉，`接管情况` 卡片与 `自动切换` 卡片重叠；因为后画的卡片盖在上面，
`接管情况` 那块（含总开关）很可能整个看不见或被压成"卡中卡"。

**根因**：`activity_main.xml` 里卡片一（接管情况）没有在卡片二之前闭合 —— 第 222 行只闭合了
卡片**内层的 LinearLayout**，卡片自身一直开着，于是第 225 行开的「自动切换」卡片成了卡片一的
**子节点**；那个本该属于卡片一的 `</MaterialCardView>` 被写到了第 282 行。

```
140: <MaterialCardView>          <!-- 卡片一：接管情况 -->
149:   <LinearLayout>            <!-- 内层容器 -->
...
221:     </LinearLayout>          <!-- 最后一行状态 -->
222:   </LinearLayout>            <!-- 内层容器闭合（卡片一没闭合！）-->
224:   <!-- 卡片二：自动切换 -->
225:   <MaterialCardView>         <!-- ← 成了卡片一的子节点 -->
...
280:   </MaterialCardView>        <!-- 卡片二闭合 -->
282: </MaterialCardView>          <!-- 这个才闭上卡片一（位置错了）-->
```

**为什么门禁查不出**：`aapt2 compile` 只验 XML 合法性（标签是配平的，能过），`check.cmd` 只验
Java。CI 也不会报。**`MaterialCardView` 本质是 `FrameLayout`**，多个子 View 会叠在同一位置绘制，
所以这不是"多一层容器无所谓"，而是会真的重叠。

**证据**（附 A-1 脚本输出）：

```
!! activity_main.xml:  嵌套 MaterialCardView（缩进 5）
OK activity_edit.xml
OK dialog_lib_settings.xml / dialog_preview.xml / item_library.xml / item_wallpaper.xml / widget_layout.xml
```

注意：文件里的**缩进还有误导性**（225 行缩进看着像和 140 行同级，实际 XML 上是子节点），
别靠肉眼看缩进，跑脚本。

**修法**：把卡片一的闭合标签挪到卡片二**之前** —— 在 222 行后面补一个
`</com.google.android.material.card.MaterialCardView>`，并删掉 282 行那个多余的。
标签总数不变，只是顺序/位置对调。

**验证**：跑附 A-1（应全部 `OK`），然后真机/模拟器打开抽屉看两张卡是否上下排列、互不遮挡。

### R2 —— 重新编辑（或删除）后，屏幕上的壁纸不更新

**现象**：
1. 长按壁纸格 → 铅笔 → 重新裁剪 → 确认：**列表缩略图更新了，但桌面/锁屏还是旧图**，
   要等下一次自动或手动切换才变（可能十几分钟后）。
2. 删掉"当前正在显示的那张"壁纸：文件已删，屏幕仍挂着旧帧。

**根因**：全工程 `WallSwitchService.notifyWallpaperChanged()` **只在 `Switcher.next` 里调用一次**
（`Switcher.java:110`），`TakeoverManager.setLockFromFile(...)` 也只被 `Switcher` 与
`TakeoverManager` 自己调用。而 `EditActivity` 的确认分支只做了
`WallpaperStore.overwrite(this, itemId, result)`（`EditActivity.java:163` 附近），
**没有任何"让改动上屏"的动作**：

- 桌面：引擎是"收到通知才重绘"，不通知就一直是上一帧解出来的位图。
- 锁屏：系统里存的是 `setBitmap(FLAG_LOCK)` 时那份位图副本，改库里的文件不会影响它。

**修法建议**：覆盖（以及删除）完成后，判断这张是不是该范围的**当前壁纸**：

```
Switcher.getCurrent(ctx, libId, forHome) == itemId
```

是的话按范围重新上一次屏：桌面 `WallSwitchService.notifyWallpaperChanged()`；
锁屏 `TakeoverManager.setLockFromFile(ctx, WallpaperStore.getFullFile(ctx, id))`。
（注意 `notifyWallpaperChanged` 是静态方法、引擎在后台线程解码，别在主线程里做重活。）

**验证步骤（手工，必须做）**：
1. 让某张壁纸成为桌面当前图 → 长按它 → 铅笔 → 明显改变裁剪范围 → 确认。
2. 立刻看桌面：**应立即变成新构图**（不是等下一次切换）。
3. 对锁屏当前图重复一次，看锁屏是否立即更新。
4. 删掉桌面当前图：屏幕应立刻换到库里另一张（或至少不再显示已删的那张）。

---

## 二、建议修

### R3 —— 改名时按返回键会退出 App

返回键链路（`MainActivity.setupBackPressed`，141-162 行）只处理了：抽屉开着 → 收起浮出图标 →
壁纸页回库列表 → 退出。**没有把"正在就地改名"算进去**：库列表页改名时按返回，会走到
`setEnabled(false); onBackPressed()` → **退出应用**，未提交的改名可能丢。

另外 `commitRename`（1247 行）在 `etName` 的**焦点变化回调**里直接 `refreshLibs()`
（= `notifyDataSetChanged`），会在焦点回调过程中重绑正在编辑的那个 View，比较脆
（键盘状态、光标位置都可能出问题）。

**建议**：把"正在改名"纳入返回链路（返回 = 取消改名并收起输入法，而不是退出）；
提交改名的刷新改成 `notifyItemChanged(position)` 或 `post(...)` 到下一帧再整体刷新。

### R4 —— `onBindViewHolder` 里的主线程 IO 与解码

- `LibAdapter.onBindViewHolder`（1322-1345 行）**每行每次绑定都调用
  `WallpaperStore.loadByLib(...)`**（读 JSON 文件）来取张数与缩略图 id。库多、滚动时是
  反复的主线程文件读。建议在 `setItems` 时一次性算好每行的 `count` 与 `thumbId` 存进行模型。
- `WallpaperAdapter`（1465-1467 行）在 bind 里同步解码缩略图。已有 4MB `LruCache` 兜底，
  属于既有模式，但首屏/快速滚动仍会掉帧；必要时挪到后台线程 + 占位图。

### R5 —— 触摸目标与删除安全

- `item_wallpaper.xml` 的 `btn_edit` / `btn_delete` 与 `item_library.xml` 的
  `btn_lib_settings` / `btn_lib_delete` 都是 **40dp**，低于 Material 建议的 48dp。
  网格里铅笔与垃圾桶间距只有 16dp，而**壁纸删除不二次确认**（这是明确需求，保留），
  误触代价不可逆。
- **建议**：把可点区域做到 48dp（图标视觉尺寸可以不变，靠 padding/`TouchDelegate`）；
  删除后给一个 Snackbar「已删除 · 撤销」—— 不算二次确认，但能兜住误触。

### R6 —— 死字符串

`swipe_delete_hint`（已改成"长按壁纸可编辑或删除"）**没有任何地方引用**了（原来在左滑删除的
提示里）。要么用起来（比如空状态/首次进入时提示），要么删掉。

### R7 —— `overwrite` 的边角

`WallpaperStore.overwrite()`：
- 只写 `id + FULL_EXT`（`.png`）。如果这张图是**老数据**（`LEGACY_FULL_EXT = ".jpg"`），
  结果会同时存在 `.png` 和 `.jpg` 两份（`getFullFile` 优先读 `.png` 所以功能没错，但白占空间）。
  建议覆盖后顺手删掉遗留的另一个扩展名（可复用 `deleteFullFiles` 的思路）。
- `scaleToFit` 出来的缩略图位图没有 `recycle()`。

### R8 —— 流程

`docs/ui-redesign.md` 里对 `SwipeRevealLayout.java` / `item_action_bg.xml` 写的是
"若确认不再用左滑删除，可删（**先跟用户确认再删**）"，这三处删除（含 `menu_main.xml`）
没有先确认。**删除本身是对的**（代码里已无引用，`R.menu` 引用数为 0），只是流程上少了一步。

---

## 三、已确认按需求如此 —— 不要"修"回去

1. **手动切换放在库行的齿轮弹窗里**（`row_switch_now` → 「立即切换一张」），底部不再有切换按钮。
   这是**用户明确要求**的，不是"入口被埋了"的 bug。评审里提到过，用户已确认。
2. **没有全局「定时切换」总开关**：库启用 + 设了间隔 ⇒ 到点自动切；要停就停用该库。
   `TimerScheduler.isTimerEnabled()` 恒为 `true` 是**设计如此**。
3. **接管情况两行的取值只写 `WallPaper` / `系统`**，不加"（动态引擎）""随桌面"之类的机制说明。
4. **壁纸删除不二次确认，库删除二次确认**（确认按钮也是图标）。
5. **左滑删除整体移除**（`SwipeRevealLayout` 删除是对的）。

## 四、需要用户拍板（实现者不要自行决定）

- **升级后老用户会被"强制开启定时"**：`timer_enabled` 偏好不再读取，以前关掉定时的用户，
  升级后只要库是"启用"状态就会开始自动换壁纸。这符合"去掉总开关"的决策，但"升级即生效"
  这个副作用需要用户确认；若不接受，需要迁移方案（例如首次启动检测旧的 `timer_enabled == false`
  则把各库置为停用）。

## 五、已核对通过（改 R1/R2 时别破坏这些）

- **`check/stubs/` 的 5 处改动都是真实存在的 API**，不是为了过检查而放宽桩：
  `DialogInterface.BUTTON_POSITIVE`、`Builder.setPositiveButton(CharSequence, listener)`、
  `AlertDialog.getButton(int)`、`AppCompatActivity.getOnBackPressedDispatcher()`、
  `Toolbar.setTitle(int)` / `setNavigationIcon(int)`、`DrawerLayout.isDrawerOpen(int)`、
  `RecyclerView.Adapter.notifyItemChanged(int)`（真实库中确实是 `final`）。
- 删 `menu_main.xml` 后 `R.menu` 引用数为 0；`R.string/R.id/R.drawable/R.layout/R.color`
  与布局里的 `@string/@drawable/@color` 引用**全部有定义**。
- 返回链路分层正确：抽屉 → 收起浮出图标 → 壁纸页回库列表 → 库列表页才退出
  （`OnBackPressedCallback` 用法标准）。
- 首页=库列表、点库名就地改名、点行入库、长按浮出垃圾桶、删库二次确认
  （用 `dialog.getButton(BUTTON_POSITIVE)` 把确认按钮换成红色垃圾桶图标 —— 做法挺好）。
- 删库连带清理完整：`LibraryStore.delete` → `TimerScheduler.cancel` + `WallpaperStore.deleteByLib`
  + `Switcher.clearProgress`。删单张壁纸也清了缩略图与待编辑标题。
- 锁屏三态正确：`isLockTakenOver() || isLockTakenByEngine()`；
  `TakeoverManager.apply()` 在"引擎盖着锁屏"时不去还原锁屏 —— 与文档 3.4 节一致。
- 「接管」关着时手动切换仍被拦住（`switchLibNow` 开头 + `Switcher.next` 收口未动）。
- `EditActivity` 重编模式：保留 id/标题/归属库，取消不动任何文件，导入模式的收件箱逻辑没被破坏。
- 版本号已递增（`versionCode 21` / `versionName "3.14"`）、README 有 v3.14 条目、无新依赖。

## 六、修完的自检清单

```powershell
.\check\check.cmd                                              # 期望 == Java type check PASSED ==
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\aapt2.exe" compile --dir app\src\main\res -o "$env:TEMP\r.zip"
python <附 A-1 脚本>                                            # 期望所有布局都 OK
<附 A-2 脚本>                                                   # 期望输出为空
```

外加 R1 / R2 的**手工验证步骤**（见各自小节）—— 这两条门禁查不出来，必须手点一遍。

---

## 附 A：两个门禁查不出来的本地检查

### A-1 布局嵌套卡片检测（Python + ElementTree，在仓库根目录跑）

```python
import xml.etree.ElementTree as ET, glob, os
def short(tag): return tag.split('}')[-1].split('.')[-1]
for f in sorted(glob.glob('app/src/main/res/layout/*.xml')):
    r = ET.parse(f).getroot()
    problems = []
    def walk(e, d=0, cards=0):
        n = short(e.tag)
        if n == "MaterialCardView" and cards > 0:
            problems.append("嵌套 MaterialCardView（深度 %d）" % d)
        cards2 = cards + 1 if n == "MaterialCardView" else cards
        for c in list(e): walk(c, d + 1, cards2)
    walk(r)
    print(("!! " if problems else "OK ") + os.path.basename(f) + (": " + "; ".join(problems) if problems else ""))
```

当前输出（R1 未修之前）：

```
OK activity_edit.xml
!! activity_main.xml: 嵌套 MaterialCardView（深度 5）
OK dialog_lib_settings.xml / dialog_preview.xml / item_library.xml / item_wallpaper.xml / widget_layout.xml
```

### A-2 资源引用全量比对（PowerShell，在仓库根目录跑；期望输出无 `!!`）

```powershell
$ErrorActionPreference = 'SilentlyContinue'
function Defs($pattern, $files) {
  $o = @()
  foreach ($f in $files) {
    $m = (Select-String -Path $f -Pattern $pattern -AllMatches -CaseSensitive).Matches
    if ($m) { $o += $m | ForEach-Object { $_.Groups[1].Value } }
  }
  return $o | Sort-Object -Unique
}
$resFiles  = (Get-ChildItem -Recurse -File app\src\main\res\values | Where-Object { $_.Extension -eq '.xml' }).FullName
$allResXml = (Get-ChildItem -Recurse -File app\src\main\res -Include *.xml).FullName
$java      = (Get-ChildItem -Recurse -Filter *.java app\src\main\java).FullName
$defs = @{}
$defs['string']   = Defs 'name="([^"]+)"' $resFiles
$defs['id']       = Defs '@\+id/([A-Za-z0-9_]+)' $allResXml
$defs['drawable'] = (Get-ChildItem -File app\src\main\res\drawable).BaseName | Sort-Object -Unique
$defs['layout']   = (Get-ChildItem -File app\src\main\res\layout).BaseName | Sort-Object -Unique
$defs['menu']     = @((Get-ChildItem -File app\src\main\res\menu -ErrorAction SilentlyContinue).BaseName) | Sort-Object -Unique
$defs['color']    = Defs 'name="([^"]+)"' ($resFiles | Where-Object { $_ -like '*colors.xml' })
foreach ($t in @('string','id','drawable','layout','menu','color')) {
  $used = Defs ("R\.${t}\.([A-Za-z0-9_]+)") $java | Where-Object { $_ -ne 'equals' }
  $miss = $used | Where-Object { $defs[$t] -notcontains $_ }
  if ($miss) { "!! R.${t} 缺失: " + ($miss -join ', ') } else { "OK  R.${t}（$($used.Count) 个引用）" }
}
```

**本地 `check.cmd` 天生查不出资源漏定义**（它的 R 桩是按 Java 用法反推生成的），
v3.10 就因此炸过一次 CI（12 个字符串只在 Java 里引用、`strings.xml` 里没有）。
