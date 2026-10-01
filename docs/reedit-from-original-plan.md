# 从原图重复调整 — 实施计划

> 执行方式：本会话内联执行（inline），按任务顺序做完再统一交用户复核。
> 设计依据：`docs/reedit-from-original-design.md`

**目标：** 点壁纸格的铅笔后，取景框落在**原图**上并**复原上次构图**，微调只需拖几十像素；存量老壁纸可用一次性「关联原图」补上原图。

**架构：** `WallpaperStore` 新增 `originals/` 目录与 `library.json` 里的裁剪矩形（原图像素坐标，6 个可选字段）；`CropView` 新增按矩形复原取景框的一个方法；`EditActivity` 负责「源选谁 + 四格判定 + 保存后写参数 + 关联入口」；`BackupStore` 多打包一个目录并把 `library.json` 的序列化收口回 `WallpaperStore`（否则还原会把新字段抹掉）。上屏链路一行不改。

**技术栈：** Java 17、Android（minSdk 26 / targetSdk 35）、org.json、java.nio.file、无新增依赖。

**与默认模板的两处偏离（按本仓库规矩来）：**
- 不写单元测试步骤：项目里没有 `app/src/test`，`check/` 下是纯 JVM 夹具（`BackupRoundtripTest.java` 等，生产逻辑的副本 + `main` 里数 pass/fail）。本期沿用这个传统，新增 `check/CropRectTest.java` 验矩形换算的往返精度。
- 不做每任务一提交：AGENTS.md 规则 1 + 用户「攒改动再提交」的习惯，最后统一由用户确认后再 commit + 升版本号。

---

## 文件结构

| 文件 | 本期职责 |
| :-- | :-- |
| `app/src/main/java/com/example/wallswitch/WallpaperStore.java` | 原图目录与生命周期（搬入/关联/删除）、`Item` 的 6 个新字段与读写、`saveCropRect`、对外 `saveItems` |
| `app/src/main/java/com/example/wallswitch/CropView.java` | `restoreSourceRect()`：把取景框摆到位图坐标下的某个矩形 |
| `app/src/main/java/com/example/wallswitch/EditActivity.java` | 选源四格判定、解码后复原、确认时写参数、关联原图的按钮与后台复制 |
| `app/src/main/res/layout/activity_edit.xml` | 顶部提示条（胶囊里的 TextView + 「关联原图」按钮） |
| `app/src/main/res/values/strings.xml` | 3 条提示 + 2 条按钮/失败文案；三处「原图」措辞改成「成品图」并加原图计数 |
| `app/src/main/java/com/example/wallswitch/BackupStore.java` | `originals/` 打包与还原、manifest 计数、删掉本地 `saveItems` 改调 `WallpaperStore.saveItems` |
| `app/src/main/java/com/example/wallswitch/MainActivity.java` | 两处 `getString` 多传一个原图计数参数 |
| `check/CropRectTest.java` | 新建：矩形换算往返精度的纯 JVM 夹具 |

---

## Task 1：WallpaperStore — 原图留存 + 裁剪参数

**Files:** Modify `app/src/main/java/com/example/wallswitch/WallpaperStore.java`（常量区 :36-39、`Item` :78-83、`load` :99-110、`saveLibrary` :713-722、`confirmImport` :255-260、`delete` :320-338、`deleteByLib` :353-368、`getFullFile` :551-559 附近）

- [ ] **Step 1：加目录常量与访问器**

```java
    private static final String DIR_INBOX = "inbox";
    // 原图：用户从相册选的那张的原始字节，重复调整的源。文件名 <uuid>，无扩展名（读它的一方靠嗅探格式）
    private static final String DIR_ORIGINAL = "originals";
```

```java
    /** 某张壁纸的原图文件（可能不存在：历史数据没留存）。 */
    public static File getOriginalFile(Context context, String id) {
        return new File(new File(context.getFilesDir(), DIR_ORIGINAL), id);
    }

    /** 原图在不在：空文件按「没有」算，那是复制中断的半截文件。 */
    public static boolean hasOriginal(Context context, String id) {
        File f = getOriginalFile(context, id);
        return f.isFile() && f.length() > 0L;
    }
```

- [ ] **Step 2：`Item` 加 6 个字段**

```java
    public static class Item {
        public String id;
        public String libId;
        // 壁纸标题：默认取导入时的原文件名（去扩展名），可在预览弹窗里改；历史数据可能为空
        public String title;
        // 上次成品图是从原图的哪块矩形导出的 —— 坐标一律是**原图像素坐标**。
        // srcWidth/srcHeight <= 0 就是「没有参数」，此时 crop* 四个值不参与任何判断。
        public int srcWidth;
        public int srcHeight;
        public float cropLeft;
        public float cropTop;
        public float cropRight;
        public float cropBottom;

        /** 有没有可复原的构图参数。 */
        public boolean hasCropRect() {
            return srcWidth > 0 && srcHeight > 0 && cropRight > cropLeft && cropBottom > cropTop;
        }
    }
```

- [ ] **Step 3：`load` 读新字段（带缺省，旧包/旧文件读不出就是 0）**

在 `item.title = obj.optString("title", "");` 之后加：

```java
                item.srcWidth = obj.optInt("src_w", 0);
                item.srcHeight = obj.optInt("src_h", 0);
                item.cropLeft = (float) obj.optDouble("crop_l", 0d);
                item.cropTop = (float) obj.optDouble("crop_t", 0d);
                item.cropRight = (float) obj.optDouble("crop_r", 0d);
                item.cropBottom = (float) obj.optDouble("crop_b", 0d);
```

- [ ] **Step 4：`saveLibrary` 写出新字段（只在有参数时写，别让老条目凭空长出一堆 0）**

在 `obj.put("title", ...)` 之后加：

```java
            // 参数存在才写：旧条目读出 srcWidth=0，写回时不带这六个键，文件不虚胖
            if (item.hasCropRect()) {
                obj.put("src_w", item.srcWidth);
                obj.put("src_h", item.srcHeight);
                obj.put("crop_l", round2(item.cropLeft));
                obj.put("crop_t", round2(item.cropTop));
                obj.put("crop_r", round2(item.cropRight));
                obj.put("crop_b", round2(item.cropBottom));
            }
```

并加两个私有工具（放在 `saveLibrary` 下面）：

```java
    /** 矩形坐标保留两位小数（0.01 像素的精度对取景复原足够，又能让 library.json 不啰嗦）。 */
    private static double round2(float v) {
        return Math.round(v * 100d) / 100d;
    }

    /** 整表写回 library.json。BackupStore 还原时也走这里，保证字段序列化只有一处定义。 */
    public static void saveItems(Context context, List<Item> items) throws Exception {
        saveLibrary(context, items);
    }
```

- [ ] **Step 5：`confirmImport` 把收件箱那份**搬**进 `originals/`（原来是删）**

把 :255-259 那段 `try { File inboxFile = ...; Files.deleteIfExists(...); } catch` 整块换成：

```java
        // 原图搬进 originals/ 长期留存（重复调整的源）：移动而不是复制，省一次全量 IO，
        // 也不会让收件箱留下已经入库的孤儿文件。搬不走（极少数 ROM 的限制）就退回原来的删除语义。
        File inboxFile = getInboxFile(context, inboxId);
        File originalFile = getOriginalFile(context, id);
        try {
            originalFile.getParentFile().mkdirs();
            Files.move(inboxFile.toPath(), originalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception | OutOfMemoryError e) {
            try {
                Files.deleteIfExists(inboxFile.toPath());
            } catch (Exception ignored) {
            }
        }
```

`StandardCopyOption` 已在文件头 import（:20）。

- [ ] **Step 6：`linkOriginal` — 存量壁纸补一张原图（原始字节复制，失败不留半截）**

放在 `importToInbox`（:113-141）之后：

```java
    /**
     * 给一张已入库的存量壁纸补原图：把相册 URI 的原始字节复制进 originals/&lt;id&gt;。
     * 只服务历史数据 —— 新导入的走 confirmImport 里的移动，不需要这个动作。
     * 复制失败（授权失效、流断了）返回 false 并清掉半截文件，绝不留下一个「存在但解不开」的原图。
     */
    public static boolean linkOriginal(Context context, String id, Uri uri) {
        if (id == null || id.isEmpty() || uri == null) {
            return false;
        }
        File target = getOriginalFile(context, id);
        try {
            target.getParentFile().mkdirs();
        } catch (Exception ignored) {
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return false;
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target.isFile() && target.length() > 0L;
        } catch (Exception | OutOfMemoryError e) {
            try {
                Files.deleteIfExists(target.toPath());
            } catch (Exception ignored) {
            }
            return false;
        }
    }
```

- [ ] **Step 7：`saveCropRect`**

放在 `setTitle`/`getTitle` 附近（同一类"改条目字段并整表写回"的动作）：

```java
    /**
     * 记下这次成品图是从原图的哪块矩形导出的（原图像素坐标），供下次进编辑页复原取景框。
     * 读-改-写整份 library.json：这条只有编辑页确认那一下会触发，用户手速级并发，不额外加锁。
     */
    public static void saveCropRect(Context context, String id, int srcWidth, int srcHeight,
                                    float left, float top, float right, float bottom) {
        if (id == null || srcWidth <= 0 || srcHeight <= 0) {
            return;
        }
        try {
            List<Item> items = load(context);
            boolean changed = false;
            for (Item item : items) {
                if (!item.id.equals(id)) {
                    continue;
                }
                item.srcWidth = srcWidth;
                item.srcHeight = srcHeight;
                item.cropLeft = left;
                item.cropTop = top;
                item.cropRight = right;
                item.cropBottom = bottom;
                changed = true;
            }
            if (changed) {
                saveLibrary(context, items);
            }
        } catch (Exception ignored) {
        }
    }
```

- [ ] **Step 8：删除路径连带删原图**

`delete`（:320-338）里 `Files.deleteIfExists(thumbFile.toPath());` 之后加一行，`deleteByLib`（:353-368）里同名位置同样加：

```java
            Files.deleteIfExists(getOriginalFile(context, id).toPath());
```

- [ ] **Step 9：类头注释补上 originals/ 与新字段**

把 :26-33 的目录约定注释加两行：`- originals/ 原图（重复调整的源），文件名 <uuid>，保留原始字节；历史数据可能没有` 与 `  元数据条目另带 src_w/src_h/crop_l/crop_t/crop_r/crop_b（原图坐标的裁剪矩形）`。

- [ ] **Step 10：验证**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File check\javacheck.ps1`
Expected: `== Java type check PASSED ==`

## Task 2：CropView — 复原取景框

**Files:** Modify `app/src/main/java/com/example/wallswitch/CropView.java`（`resetLayout` :88-108 之后）

- [ ] **Step 1：加 `restoreSourceRect`**

```java
    /**
     * 按「当前位图坐标下的可见矩形」复原取景框，等价于用户自己捏到那个位置。
     * 矩形来自 library.json（那里存的是**原图坐标**），调用方要先换算成位图坐标 ——
     * 位图可能因像素预算被降采样过，直接拿原图坐标会偏。
     * 位图/视图未就绪或矩形非法时不动，保持 fit-cover 默认态（宁可构图回到默认，也不能把钳制区间留空）。
     */
    public void restoreSourceRect(RectF rect) {
        if (bitmap == null || rect == null) {
            return;
        }
        // 先让 minScale/maxScale 有值：跳过这一步的话钳制区间停在字段初值 1f/1f，
        // 表现就是「捏合钳死在 1 倍」那种静默失效（见 matrixReady 字段的注释）
        ensureMatrixReady();
        if (!matrixReady) {
            return;
        }
        float viewW = getWidth();
        float viewH = getHeight();
        float rectW = rect.width();
        if (viewW <= 0f || viewH <= 0f || rectW <= 0f || rect.height() <= 0f) {
            return;
        }
        // 只按宽轴算比例：这块矩形本来就是从同一个满屏取景框里截出来的，
        // 高轴的浮点/密度微差交给下面的 clampTranslate 吸收，不立第二套比例约定
        float scale = viewW / rectW;
        scale = Math.max(minScale, Math.min(maxScale, scale));
        matrix.reset();
        matrix.setScale(scale, scale);
        matrix.postTranslate(-rect.left * scale, -rect.top * scale);
        clampTranslate();
        invalidate();
    }
```

- [ ] **Step 2：验证**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File check\javacheck.ps1`
Expected: `== Java type check PASSED ==`

## Task 3：EditActivity — 选源四格判定 + 写参数 + 关联入口

**Files:** Modify `app/src/main/java/com/example/wallswitch/EditActivity.java`

- [ ] **Step 1：加字段**

在 `private int maxDim;`（:63）之后：

```java
    // 本次编辑的源是不是原图（导入模式的收件箱文件、重编模式找到 originals/ 的都算）
    private boolean sourceIsOriginal;
    // 待复原的矩形，存的是原图坐标；解码回来后换算成位图坐标再交给 CropView
    private RectF pendingRestoreRect;
    private View noticeBar;
    private TextView noticeText;
    private View noticeLinkButton;
    private ActivityResultLauncher<PickVisualMediaRequest> linkOriginalLauncher;
```

- [ ] **Step 2：注册关联启动器 + 绑提示条**

`onCreate` 里 `shotLauncher = registerForActivityResult(...)` 之后加：

```java
        linkOriginalLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), this::onOriginalPicked);
        noticeBar = findViewById(R.id.edit_notice_bar);
        noticeText = findViewById(R.id.tv_edit_notice);
        noticeLinkButton = findViewById(R.id.btn_link_original);
        if (noticeLinkButton != null) {
            noticeLinkButton.setOnClickListener(v -> pickOriginalForLinking());
        }
```

- [ ] **Step 3：换掉选源那两行，改成分支判定**

把 :91-95 的

```java
        sourceFile = itemId != null
                ? WallpaperStore.getFullFile(this, itemId)
                : WallpaperStore.getInboxFile(this, inboxId);
        readOriginalBounds();
        startDecode();
```

换成：

```java
        chooseSource();
        readOriginalBounds();
        startDecode();
```

新增方法（放在 `readOriginalBounds` 前）：

```java
    /**
     * 决定这次编辑的源，并顺手定下「复原还是默认构图」「提示说什么」。四种情形：
     * 1) 有原图 + 参数尺寸与原图对得上 → 源=原图，复原上次构图（微调就靠这一格）
     * 2) 有原图 + 没参数（刚关联上的存量壁纸）→ 源=原图，默认构图，提示要重摆
     * 3) 有原图 + 参数尺寸对不上（换过原图）→ 源=原图，默认构图，提示位置作废
     * 4) 没原图（历史数据）→ 源=成品图，等同今天的行为，给「关联原图」按钮
     * 导入模式的收件箱文件本身就是原图，走第 1/2 格（第一次多半没参数）。
     */
    private void chooseSource() {
        if (itemId == null) {
            sourceFile = WallpaperStore.getInboxFile(this, inboxId);
            sourceIsOriginal = true;
            return;
        }
        if (WallpaperStore.hasOriginal(this, itemId)) {
            sourceFile = WallpaperStore.getOriginalFile(this, itemId);
            sourceIsOriginal = true;
            WallpaperStore.Item item = WallpaperStore.get(this, itemId);
            if (item != null && item.hasCropRect()) {
                pendingRestoreRect = new RectF(item.cropLeft, item.cropTop,
                        item.cropRight, item.cropBottom);
            }
            return;
        }
        sourceFile = WallpaperStore.getFullFile(this, itemId);
        sourceIsOriginal = false;
        pendingRestoreRect = null;
    }
```

- [ ] **Step 4：解码回来后校验尺寸并复原**

`startDecode()` 的 UI 回调里，把 `cropView.setBitmap(decoded);`（:128）换成：

```java
                cropView.setBitmap(decoded);
                applyPendingRestore();
```

新增两个方法：

```java
    /**
     * 复原取景框。这里才做尺寸校验：readOriginalBounds 读的是 sourceFile 的文件头，
     * 参数是不是写给「眼前这张原图」的，只有在这儿能判定。
     * 对不上就当没有参数（默认构图），并把提示改成「位置已作废」。
     */
    private void applyPendingRestore() {
        if (pendingRestoreRect == null || originalWidth <= 0 || originalHeight <= 0) {
            showNotice(sourceIsOriginal ? 0 : R.string.edit_notice_no_original, !sourceIsOriginal);
            return;
        }
        if (pendingRestoreRect.width() <= 0f || pendingRestoreRect.height() <= 0f) {
            pendingRestoreRect = null;
            showNotice(R.string.edit_notice_original_changed, false);
            return;
        }
        // 原图坐标 → 位图坐标：两轴各按「位图 / 原图」的比例换算（与导出用的换算是反方向）
        float kx = cropView.getSourceWidth() / (float) originalWidth;
        float ky = cropView.getSourceHeight() / (float) originalHeight;
        cropView.restoreSourceRect(new RectF(
                pendingRestoreRect.left * kx, pendingRestoreRect.top * ky,
                pendingRestoreRect.right * kx, pendingRestoreRect.bottom * ky));
        pendingRestoreRect = null;
    }

    /** 顶部提示条：resId 为 0 表示不提示（有原图、正常进编辑，别多占一块屏幕）。 */
    private void showNotice(int resId, boolean withLinkButton) {
        if (noticeBar == null || noticeText == null) {
            return;
        }
        if (resId == 0) {
            noticeBar.setVisibility(View.GONE);
            return;
        }
        noticeText.setText(resId);
        noticeBar.setVisibility(View.VISIBLE);
        if (noticeLinkButton != null) {
            noticeLinkButton.setVisibility(withLinkButton ? View.VISIBLE : View.GONE);
        }
    }
```

- [ ] **Step 5：确认时写参数**

`onConfirm()` 里 `WallpaperStore.overwrite(...)` / `confirmImport(...)` 之后（同一个 try 块内、`finish()` 前）加：

```java
            // 记下这次的可见矩形：下次点铅笔就落回这里（微调只需拖几十像素）。
            // 必须在 overwrite/confirmImport 之后写 —— 导入模式下条目是 confirmImport 刚建的那一条。
            if (result != null) {
                saveCropRectIfNeeded(itemId != null ? itemId : inboxId);
            }
```

新增方法：

```java
    /**
     * 把当前可见区域换算回**原图坐标**存下来。只有源是原图时才写 —— 源是成品图时那块矩形的
     * 坐标意义是「成品图的坐标」，跟 src_w/src_h 记的原图尺寸不同源，读回来会给出错误构图。
     */
    private void saveCropRectIfNeeded(String id) {
        if (!sourceIsOriginal || originalWidth <= 0 || originalHeight <= 0) {
            return;
        }
        int srcW = cropView.getSourceWidth();
        int srcH = cropView.getSourceHeight();
        if (srcW <= 0 || srcH <= 0) {
            return;
        }
        RectF visible = new RectF();
        if (!cropView.getVisibleSourceRect(visible)) {
            return;
        }
        // 与 exportByRegion 同一套换算（两轴分别算，避开 inSampleSize 向上取整的偏差），再夹回原图范围
        float kx = originalWidth / (float) srcW;
        float ky = originalHeight / (float) srcH;
        float left = Math.max(0f, Math.min(originalWidth, visible.left * kx));
        float top = Math.max(0f, Math.min(originalHeight, visible.top * ky));
        float right = Math.max(0f, Math.min(originalWidth, visible.right * kx));
        float bottom = Math.max(0f, Math.min(originalHeight, visible.bottom * ky));
        if (right <= left || bottom <= top) {
            return;
        }
        WallpaperStore.saveCropRect(this, id, originalWidth, originalHeight, left, top, right, bottom);
    }
```

- [ ] **Step 6：关联原图**

```java
    /** 提示条上的「关联原图」：开相册选一张，选完就地换成原图源。 */
    private void pickOriginalForLinking() {
        if (itemId == null) {
            return;
        }
        PickVisualMediaRequest.Builder builder = new PickVisualMediaRequest.Builder();
        builder.setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE);
        linkOriginalLauncher.launch(builder.build());
    }

    /**
     * 关联回来：复制进 originals/ → 源换成原图 → 参数作废（历史成品图不知道自己从哪儿裁的）
     * → 重新解码并从默认构图开始，接着裁接着保存。
     */
    private void onOriginalPicked(Uri uri) {
        if (uri == null || itemId == null) {
            return;
        }
        setLoading(true);
        final Context appCtx = getApplicationContext();
        new Thread(() -> {
            final boolean linked = WallpaperStore.linkOriginal(appCtx, itemId, uri);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!linked) {
                    setLoading(false);
                    Toast.makeText(this, R.string.link_original_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                sourceFile = WallpaperStore.getOriginalFile(this, itemId);
                sourceIsOriginal = true;
                pendingRestoreRect = null;
                readOriginalBounds();
                startDecode();
                showNotice(R.string.edit_notice_linked, false);
            });
        }, "link-original").start();
    }
```

`Context` 已在文件头 import（:3）。

## Task 4：布局与文案

**Files:** Modify `activity_edit.xml`（`edit_controls` 里，操作提示胶囊之后）+ `values/strings.xml`（:354-369 一带、`edit_hint` 附近）

- [ ] **Step 1：提示条**

在 `activity_edit.xml` :48（顶部提示胶囊那段）之后插入：

```xml
        <!-- 重复调整的原图状态条：只有「没有原图 / 原图换过 / 刚关联」这三种情况才出现 -->
        <LinearLayout
            android:id="@+id/edit_notice_bar"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="top|center_horizontal"
            android:layout_marginTop="52dp"
            android:background="@drawable/overlay_pill_bg"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingStart="14dp"
            android:paddingTop="4dp"
            android:paddingEnd="6dp"
            android:paddingBottom="4dp"
            android:visibility="gone">

            <TextView
                android:id="@+id/tv_edit_notice"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="@android:color/white"
                android:textSize="12sp" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btn_link_original"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:minWidth="0dp"
                android:minHeight="0dp"
                android:text="@string/edit_link_original"
                android:textColor="@android:color/white"
                android:textSize="12sp"
                app:rippleColor="#33FFFFFF" />
        </LinearLayout>
```

- [ ] **Step 2：新文案**

`strings.xml` 里 `edit_hint` 附近加：

```xml
    <!-- 重复调整：原图缺失/更换后的状态提示 -->
    <string name="edit_notice_no_original">这张壁纸没有保存原图，现在是在成品图上调整</string>
    <string name="edit_notice_original_changed">原图换过了，上次的位置已作废，请重摆构图</string>
    <string name="edit_notice_linked">已关联原图，需要重摆一次构图</string>
    <string name="edit_link_original">关联原图</string>
    <string name="link_original_failed">关联原图失败，可能没拿到那张图的读取授权</string>
```

- [ ] **Step 3：三处「原图」措辞按 AGENTS.md 改成「成品图」，并把原图计数带上**

```xml
    <string name="backup_desc">打成一个 zip（壁纸成品图、原图、缩略图、库与标题、槽位与定时、开关）放进导出目录；卸载重装后能原样接回来</string>
    <string name="backup_done">已备份 %1$s：%2$d 条元数据 / %3$d 张成品图 / %4$d 张原图 / %5$d 张缩略图</string>
    <string name="restore_confirm">备份时间 %1$s，来自 v%2$s。\n\n包里：元数据 %3$d 条、成品图 %4$d 张、原图 %5$d 张、缩略图 %6$d 张，约 %7$d MB。\n本机现在有 %8$d 条。\n\n同一 id 的壁纸会被包里的覆盖；包里没有的条目保留。定时任务会按恢复后的槽位重新排一遍。</string>
```

## Task 5：BackupStore — 打包 originals/ + 收口序列化

**Files:** Modify `BackupStore.java`（:33-43 注释、:57-59 常量、`BackupResult`/`Manifest` :65-82、`backup` :112-127、`inspect` :166-179、`restore` :204-231、`writeManifest` :268-293、`saveItems` :336-347）；`MainActivity.java`（:2436-2437、:2471）

- [ ] **Step 1：常量与计数字段**

```java
    private static final String DIR_IN_ZIP = "wallpapers/";
    private static final String THUMB_DIR_IN_ZIP = "thumbs/";
    // 原图（重复调整的源）：老包没这个目录，读到就跳过，不能报错
    private static final String ORIGINAL_DIR_IN_ZIP = "originals/";
    private static final String PREFS_DIR_IN_ZIP = "prefs/";
```

`BackupResult` 加 `public int originals;`；`Manifest` 加 `public int originals;`。

- [ ] **Step 2：`backup` 多列一个目录**

在 `thumbs` 那行之后：

```java
            java.util.List<File> originals = listFiles(new File(context.getFilesDir(), "originals"));
            r.thumbs = thumbs.size();
            r.originals = originals.size();
```

并在两圈 `for` 之后加：

```java
            for (File f : originals) {
                writeFile(zout, f, ORIGINAL_DIR_IN_ZIP + f.getName());
            }
```

`writeManifest` 调用改成带 originals 的形式：

```java
            writeManifest(context, zout, r, images, thumbs, originals);
```

- [ ] **Step 3：`writeManifest` 签名与字段**

```java
    private static void writeManifest(Context context, java.util.zip.ZipOutputStream zout,
                                      BackupResult r, java.util.List<File> images,
                                      java.util.List<File> thumbs,
                                      java.util.List<File> originals) throws Exception {
```

方法体里 `thumbBytes` 之后加一段同样的累加，`o.put("thumbs", ...)` 之后加：

```java
        long originalBytes = 0;
        for (File f : originals) {
            originalBytes += f.length();
        }
```

```java
        o.put("originals", originals.size());
        o.put("original_bytes", originalBytes);
```

- [ ] **Step 4：`inspect` 数一下包里的原图**

在 `else if (n.startsWith(THUMB_DIR_IN_ZIP))` 分支**之前**插一条（`originals/` 与 `wallpapers/` 前缀不冲突，顺序无所谓，放在这里是让三个计数挨着）：

```java
                } else if (n.startsWith(ORIGINAL_DIR_IN_ZIP)) {
                    m.originals++;
```

`fillFromManifest` 里加一条兜底：

```java
            if (m.originals <= 0) {
                m.originals = o.optInt("originals", 0);
            }
```

- [ ] **Step 5：`restore` 落回原图目录**

`new File(context.getFilesDir(), "thumbs").mkdirs();` 之后加：

```java
            new File(context.getFilesDir(), "originals").mkdirs();
```

在 `THUMB_DIR_IN_ZIP` 分支之后加一条：

```java
                } else if (n.startsWith(ORIGINAL_DIR_IN_ZIP)) {
                    // 条目名安全化后落回同名文件；老包不会走到这条分支
                    extract(context, zip, e, new File(context.getFilesDir(), "originals"), safeName(n));
```

- [ ] **Step 6：删掉本地 `saveItems`，改调 `WallpaperStore.saveItems`**

`dropMissingImages` 与 `retagUnknownLibs` 里的 `saveItems(context, ...)` 改成 `WallpaperStore.saveItems(context, ...)`，并**删除** :336-347 那个私有副本 —— 它只写 id/lib_id/title，留着就会在还原时把新写的裁剪字段整表抹掉（这是本期最容易踩的一处）。

- [ ] **Step 7：`MainActivity` 两处调用补参数**

```java
                Toast.makeText(this, getString(R.string.backup_done, r.name, r.entries,
                        r.images, r.originals, r.thumbs), Toast.LENGTH_LONG).show();
```

```java
                        m.libraryItems, m.images, m.originals, m.thumbs, m.bytes / (1024 * 1024), local))
```

- [ ] **Step 8：`BackupStore` 类头注释的清单加一行**

```java
 * - originals/*                 原图原始字节：重复调整的源；老包没这条，还原时跳过
```

## Task 6：夹具与验证

**Files:** Create `check/CropRectTest.java`

- [ ] **Step 1：写往返精度夹具（纯 JVM，复刻生产那两段换算）**

夹具验的是本期唯一一处「写错过得静默」的数学：可见矩形 原图坐标 ⇄ 位图坐标 的双向换算，加上 `restoreSourceRect` 的比例式。android.jar 里的类在 JVM 上一调用就抛 `Stub!`，所以这里用 `float[]` 手写矩阵等价式（无旋转：`screen = src*scale + tx`）。断言：复原之后再一次换算出的矩形应与存进去的差 **< 0.5 像素**。

```java
/**
 * 裁剪矩形往返：EditActivity 存的是原图坐标，CropView 复原要位图坐标，保存时又换算回原图坐标。
 * 两处换算（kx/ky 各轴独立）与 restoreSourceRect 里的 scale = viewW / rect.width 是本期唯一
 * 「写错也不报错」的地方，所以照生产代码的算式抄一份纯 float 版本，断言往返误差 < 0.5px。
 * 跑法：javac -encoding UTF-8 check/CropRectTest.java && java -cp check CropRectTest
 * （注意本机默认 java 是 1.8，用 JDK17 那个 javac 更稳）
 */
public class CropRectTest {
    static int pass = 0, fail = 0;

    /** 断言实际值与期望值差在 tol 内。 */
    static void near(String what, double expect, double actual, double tol) { ... }

    /**
     * 一次往返：原图 origW×origH，按像素预算降采样成位图 bmpW×bmpH，
     * 视图 viewW×viewH；存进去的原图矩形 rect → 复原后重新换算回原图坐标，应仍等于 rect。
     */
    static void roundtrip(String caseName, int origW, int origH, int bmpW, int bmpH,
                          int viewW, int viewH,
                          double rl, double rt, double rr, double rb) {
        // 存的时候：位图坐标的可见区（= 取景框逆变换）→ 原图坐标（EditActivity.saveCropRectIfNeeded）
        // 这里直接给定原图矩形，模拟「已经存进 library.json」
        // 读回来：原图坐标 → 位图坐标（applyPendingRestore）
        double kxb = (double) bmpW / origW, kyb = (double) bmpH / origH;
        double bl = rl * kxb, bt = rt * kyb, br = rr * kxb, bb = rb * kyb;
        // 复原取景框：scale = viewW / 位图矩形宽，平移让矩形左上角落在 (0,0)（CropView.restoreSourceRect）
        double scale = viewW / (br - bl);
        // 保存时再换算回原图坐标：可见区在位图坐标 = (0,0)-(viewW/scale, viewH/scale)，先减掉平移量
        double visL = bl, visT = bt;
        double visR = bl + viewW / scale, visB = bt + viewH / scale;
        double kx = (double) origW / bmpW, ky = (double) origH / bmpH;
        near(caseName + " left", rl, visL * kx, 0.5);
        near(caseName + " top", rt, visT * ky, 0.5);
        near(caseName + " right", rr, visR * kx, 0.5);
        near(caseName + " bottom", rb, visB * ky, 0.5);
    }

    public static void main(String[] args) {
        // 1) 默认 fit-cover 构图（竖屏手机 + 4032×3024 原图被预算解成 2016×1512）
        roundtrip("cover", 4032, 3024, 2016, 1512, 1264, 2800, 0, 0, 4032, 3024);
        // 2) 裁左右：存的是「竖着的一条」，位置偏右
        roundtrip("crop-side", 4032, 3024, 2016, 1512, 1264, 2800, 812.5, 0, 2412.5, 2700);
        // 3) 放大 3 倍后停在左上角附近（微调最常见的现场）
        roundtrip("zoom-top", 4032, 3024, 2016, 1512, 1264, 2800, 200, 150, 828, 1544);
        // 4) 贴右下边界（clampTranslate 起作用的位置，误差最容易暴露）
        roundtrip("bottom-right", 4032, 3024, 2016, 1512, 1264, 2800, 3204, 1824, 4032, 3024);
        System.out.println("\n== crop rect: " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) System.exit(1);
    }
}
```

- [ ] **Step 2：跑夹具**

Run：`check\croprect.cmd`（新建，内容照 `check.cmd` 那两行的写法，纯 ASCII，走 JDK17 javac）
Expected：`== crop rect: 16 passed, 0 failed ==`

- [ ] **Step 3：跑类型检查**

Run：`.\check\check.cmd`
Expected：`== Java type check PASSED ==`

- [ ] **Step 4：构建与真机用例**

按用户节奏：先只跑 `check.cmd`，等他一句「构建吧」再走完整构建/提交。真机手工用例见设计文档 §12 的 8 条，其中 2、3、4、5 是本期主证据链。

## 执行结果（2026-10-02 内联实现完成）

Task 1-6 全部落地，改动统计：`AGENTS.md`、`WallpaperStore`(+143)、`EditActivity`(+190)、`BackupStore`(±95)、`CropView`(+34)、`activity_edit.xml`(+43)、`strings.xml`(±12)、`MainActivity`(±4)，新建 `check/CropRectTest.java` 与 `check/croprect.cmd`。

验证：

- `.\check\check.cmd` → `== Java type check PASSED ==`
- `.\check\croprect.cmd` → `== crop rect: 77 passed, 0 failed ==`（7 个合法视图状态 × 11 条断言，含 cover、裁左右、3 倍、8 倍贴右下角、未降采样小图、超宽图、竖窄图）
- 两个 XML 用 `XmlDocument.Load` 校验通过（注意：PowerShell 的 `[xml](Get-Content)` 在 GBK 控制台上会把 UTF-8 读成乱码并报假错）

实现中偏离计划的两处，都是计划里没预见的真问题：

1. `BackupStore` 那个私有 `saveItems` 删掉之后，`collectLibIds` 的单行注释被我一次替换误伤（`/**` 没闭合，方法被当注释吃进去），第一次 `check.cmd` 就是靠这个报错抓出来的 —— 已修复。
2. 「刚关联原图」的提示原计划在 `startDecode()` 之后直接 `showNotice`，但解码回调随后跑到 `applyPendingRestore` 会把提示抹掉（表现是提示一闪就没）。改成 `pendingNoticeResId` 字段，由解码回调统一发，`applyPendingRestore` 优先处理它。

未做的事（与设计一致）：成品图仍是无损 PNG；没动上屏链路；没处理导出目录同名不覆盖；EXIF 旋转与 HEIF 区域解码仍走既有兜底。未提交、未升版本号。

### 用户复核后的两处收口（同日）

- 「关联原图」按用户定位降级为**临时修数据的工具**（存量老壁纸补原图，以后会整条删掉）：删掉了成功后那条「已关联原图，需要重摆一次构图」提示与配套的 `pendingNoticeResId` 状态字段（本来就是为了绕开解码回调把提示抹掉而加的），关联完直接静默用默认构图打开；`edit_notice_linked` 字符串一并删除。设计文档新增 §6.1「以后删这一条时要动哪几处」的清单，`pickOriginalForLinking()` 的注释指向它。
- 用户提到的「60 秒限制」在代码里不存在：grep 过 `EditActivity` 与 `WallpaperStore` 的 `60` / `TimeUnit` / `postDelayed` / `SystemClock` / `currentTimeMillis` / `timeout`，唯一命中是小组件注释里的「一格 60~70dp」。关联链路是「点按钮 → 开相册 → 复制字节 → 重新解码」，没有任何等待或超时。

复验：`.\check\check.cmd` → PASSED；`.\check\croprect.cmd` → 77 passed, 0 failed；`EditActivity` 引用的 `R.string.*` / `R.id.*` 与资源文件逐条对得上（无 dangling 引用）。

## 自检（写完计划后跑过）

- **覆盖设计**：§3 文件布局 → T1；§4 元数据 → T1；§5 四格判定 → T3 Step3/4；§6 关联 → T1 Step6 + T3 Step6 + T4；§7 `restoreSourceRect` → T2；§8 保存路径 → T3 Step5；§9 备份/还原/删除 → T1 Step8 + T5；§11 EXIF 与 HEIF 不做 → 未出现在任务里，符合预期；§12 验收 → T6。
- **占位符**：T6 的 `near(...)` 留了方法体省略号，执行时补全（`if (Math.abs(expect-actual) <= tol) pass++ else {fail++; 打印}`）—— 这是唯一一处，实现时不能偷懒。
- **类型一致**：`saveCropRect(Context, String, int, int, float, float, float, float)`、`restoreSourceRect(RectF)`、`getOriginalFile/hasOriginal/linkOriginal`、字段名 `srcWidth/srcHeight/cropLeft..cropBottom` 与 JSON 键 `src_w/src_h/crop_l/crop_t/crop_r/crop_b` 在各任务间核对一致；`WallpaperStore.saveItems` 与 T5 调用处一致。
- **本期最容易踩的一处**：`BackupStore` 里那个只写三个字段的私有 `saveItems` —— 不删掉就会在还原后把裁剪参数抹光（表现为「原图在、参数没了，每次打开都是默认构图」）。T5 Step6 专门处理它。
