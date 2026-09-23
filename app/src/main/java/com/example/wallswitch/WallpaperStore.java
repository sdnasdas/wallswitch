package com.example.wallswitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.net.Uri;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 壁纸库存储层。
 * 目录约定（均在 App 私有 filesDir 下）：
 * - wallpapers/ 全图，文件名 &lt;uuid&gt;.jpg（无损 PNG，不再二次压缩）
 * - thumbs/     缩略图，同名，最长边 256px
 * - inbox/      待编辑收件箱，文件名 &lt;uuid&gt;，保留原始字节
 * 元数据：filesDir/library.json，JSON 数组，每个元素 {"id":"&lt;uuid&gt;","home":true,"lock":true}
 */
public class WallpaperStore {

    // 目录名常量
    private static final String DIR_FULL = "wallpapers";
    private static final String DIR_THUMB = "thumbs";
    private static final String DIR_INBOX = "inbox";
    // 元数据文件名
    private static final String LIB_FILE = "library.json";
    // 待编辑项标题（导入时的原始文件名，确认导入后写入元数据）临时存储
    private static final String TITLE_PREFS = "pending_titles";
    // 缩略图的 JPEG 压缩质量（缩略图只用于列表显示，没必要无损）
    private static final int JPEG_QUALITY = 90;
    // 全图扩展名：无损 PNG —— 全图是最终要上屏的图，再压一次 JPEG 会白掉画质
    private static final String FULL_EXT = ".png";
    // 历史数据用的是 .jpg，读取与删除都要兼容，避免老壁纸失效或留下孤儿文件
    private static final String LEGACY_FULL_EXT = ".jpg";
    // 壁纸分辨率下限：小屏机型也至少按这个处理
    private static final int MIN_WALLPAPER_DIM = 2048;
    // 壁纸分辨率上限：控内存（ARGB_8888 下约 4.6M 像素 ≈ 18MB）
    private static final int MAX_WALLPAPER_DIM = 4096;
    // 缩略图最长边
    private static final int THUMB_MAX_DIM = 256;

    /** 屏幕长边：壁纸要铺满整屏，这就是「不被放大」所需的最小长边。 */
    public static int screenLongSide(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return Math.max(dm.widthPixels, dm.heightPixels);
    }

    /**
     * 壁纸处理的分辨率上限（解码 / 导出 / 上屏统一用它）。
     * 下限 2048 保住小屏机型质量；上限按屏幕长边抬高 —— 写死 2048 而屏幕长边是 2800 时，
     * 存下来的图会被上屏放大 1.37 倍，肉眼可见发虚。封顶 4096 控制内存。
     */
    public static int maxWallpaperDim(Context context) {
        return Math.max(MIN_WALLPAPER_DIM, Math.min(screenLongSide(context), MAX_WALLPAPER_DIM));
    }

    /** 壁纸条目元数据：id 为图片文件标识，libId 为所属壁纸库 id，title 为壁纸标题（可空）。 */
    public static class Item {
        public String id;
        public String libId;
        // 壁纸标题：默认取导入时的原文件名（去扩展名），可在预览弹窗里改；历史数据可能为空
        public String title;
    }

    /** 从 library.json 读取壁纸库列表，文件不存在时返回空列表。 */
    public static List<Item> load(Context context) {
        List<Item> result = new ArrayList<>();
        File libFile = new File(context.getFilesDir(), LIB_FILE);
        if (!libFile.exists()) {
            return result;
        }
        try {
            byte[] bytes = Files.readAllBytes(libFile.toPath());
            String json = new String(bytes, "UTF-8");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Item item = new Item();
                item.id = obj.getString("id");
                item.libId = obj.optString("lib_id", "");
                item.title = obj.optString("title", "");
                result.add(item);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** 把相册选中的图片原样复制进收件箱，返回仅带 id 与标题的条目（尚未入库）。失败时清理半截文件后再抛出。 */
    public static Item importToInbox(Context context, Uri uri) throws Exception {
        String id = UUID.randomUUID().toString();
        File inboxDir = new File(context.getFilesDir(), DIR_INBOX);
        if (!inboxDir.exists()) {
            inboxDir.mkdirs();
        }
        File target = new File(inboxDir, id);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IllegalStateException("内容提供者返回空流");
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // 复制中断会残留半截文件，必须清掉，否则会被当成待编辑项反复弹编辑页再解码失败
            try {
                Files.deleteIfExists(target.toPath());
            } catch (Exception ignored) {
            }
            throw e;
        }
        // 用原文件名（去扩展名）作为壁纸标题默认值，确认导入时写进元数据
        String title = stripExtension(displayName(context, uri));
        putPendingTitle(context, id, title);
        Item item = new Item();
        item.id = id;
        item.title = title;
        return item;
    }

    /** 读取相册 URI 的原始文件名（OpenableColumns.DISPLAY_NAME），读不到返回空串。 */
    public static String displayName(Context context, Uri uri) {
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 去掉文件扩展名（标题里不带 .jpg 之类后缀）。 */
    private static String stripExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 取出某待编辑项的标题（确认导入时使用），取出后不清除（取消导入时由 cancelImport 清理）。 */
    private static String pendingTitle(Context context, String inboxId) {
        return context.getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE)
                .getString(inboxId, "");
    }

    /** 记录某待编辑项的标题。 */
    private static void putPendingTitle(Context context, String inboxId, String title) {
        context.getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE).edit()
                .putString(inboxId, title == null ? "" : title)
                .apply();
    }

    /** 修改某张壁纸的标题（预览弹窗里改，空串表示未命名）。 */
    public static void setTitle(Context context, String id, String title) {
        try {
            List<Item> items = load(context);
            boolean changed = false;
            for (Item item : items) {
                if (item.id.equals(id)) {
                    item.title = title == null ? "" : title;
                    changed = true;
                }
            }
            if (changed) {
                saveLibrary(context, items);
            }
        } catch (Exception ignored) {
        }
    }

    /** 读取某张壁纸的标题，无记录/未命名返回空串。 */
    public static String getTitle(Context context, String id) {
        try {
            for (Item item : load(context)) {
                if (item.id.equals(id)) {
                    return item.title == null ? "" : item.title;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 确认导入：保存全图与缩略图、按所属壁纸库追加元数据，并删除收件箱原文件。 */
    public static Item confirmImport(Context context, String inboxId, Bitmap edited, String libId) throws Exception {
        Bitmap bitmap = edited;
        if (bitmap == null) {
            File sourceFile = getInboxFile(context, inboxId);
            bitmap = decodeBounded(sourceFile, maxWallpaperDim(context));
        }
        if (bitmap == null) {
            throw new IllegalStateException("图片解码失败");
        }
        String id = inboxId;
        File fullDir = new File(context.getFilesDir(), DIR_FULL);
        File thumbDir = new File(context.getFilesDir(), DIR_THUMB);
        if (!fullDir.exists()) {
            fullDir.mkdirs();
        }
        if (!thumbDir.exists()) {
            thumbDir.mkdirs();
        }
        // 保存全图（无损 PNG）
        File fullFile = new File(fullDir, id + FULL_EXT);
        FileOutputStream fullOut = new FileOutputStream(fullFile);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fullOut);
        fullOut.close();
        // 保存缩略图
        Bitmap thumb = scaleToFit(bitmap, THUMB_MAX_DIM);
        File thumbFile = new File(thumbDir, id + ".jpg");
        FileOutputStream thumbOut = new FileOutputStream(thumbFile);
        thumb.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, thumbOut);
        thumbOut.close();
        // 追加元数据（归属指定壁纸库，标题取导入时记录的原文件名）
        List<Item> items = load(context);
        Item item = new Item();
        item.id = id;
        item.libId = libId == null ? "" : libId;
        item.title = pendingTitle(context, inboxId);
        items.add(item);
        saveLibrary(context, items);
        // 顺手导出一份到用户选定的导出目录（未设置直接跳过；失败不影响入库）。
        // 放后台线程：导出是纯 IO，不该拖慢确认按钮。
        final File exportSource = fullFile;
        final String exportTitle = item.title;
        final String exportId = id;
        new Thread(() -> {
            try {
                WallpaperExporter.exportFile(context, exportSource, exportTitle, exportId);
            } catch (Exception ignored) {
            }
        }, "wallpaper-export").start();
        // 删除收件箱原文件：元数据已写完，此时删除失败不应让调用方误报「保存失败」
        try {
            File inboxFile = getInboxFile(context, inboxId);
            Files.deleteIfExists(inboxFile.toPath());
        } catch (Exception ignored) {
        }
        return item;
    }

    /** 取消导入：删除收件箱中的待编辑文件与其中的标题记录。 */
    public static void cancelImport(Context context, String inboxId) {
        try {
            File inboxFile = getInboxFile(context, inboxId);
            Files.deleteIfExists(inboxFile.toPath());
        } catch (Exception ignored) {
        }
        context.getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE).edit()
                .remove(inboxId)
                .apply();
    }

    /** 列出收件箱中待编辑的文件 id（按名称排序，保证顺序稳定）。 */
    public static List<String> pendingInbox(Context context) {
        List<String> ids = new ArrayList<>();
        File inboxDir = new File(context.getFilesDir(), DIR_INBOX);
        File[] files = inboxDir.listFiles();
        if (files == null) {
            return ids;
        }
        for (File file : files) {
            // 空文件是复制中断残留的半截文件，无法解码，直接清理并跳过
            if (file.length() <= 0L) {
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (Exception ignored) {
                }
                continue;
            }
            ids.add(file.getName());
        }
        Collections.sort(ids);
        return ids;
    }

    /** 删除壁纸：移除全图、缩略图文件，并从 library.json 中移除记录。 */
    public static void delete(Context context, String id) {
        try {
            File thumbFile = getThumbFile(context, id);
            deleteFullFiles(context, id);
            Files.deleteIfExists(thumbFile.toPath());
            List<Item> items = load(context);
            List<Item> remain = new ArrayList<>();
            for (Item item : items) {
                if (!item.id.equals(id)) {
                    remain.add(item);
                }
            }
            saveLibrary(context, remain);
            // 顺带清掉可能残留的待编辑标题记录
            context.getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE).edit()
                    .remove(id)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** 加载指定壁纸库内的全部壁纸（按添加顺序）。 */
    public static List<Item> loadByLib(Context context, String libId) {
        List<Item> result = new ArrayList<>();
        for (Item item : load(context)) {
            if (item.libId != null && item.libId.equals(libId)) {
                result.add(item);
            }
        }
        return result;
    }

    /** 删除整个壁纸库的壁纸（文件与元数据），删除库时调用。 */
    public static void deleteByLib(Context context, String libId) {
        try {
            List<Item> items = load(context);
            List<Item> remain = new ArrayList<>();
            for (Item item : items) {
                if (libId.equals(item.libId)) {
                    deleteFullFiles(context, item.id);
                    Files.deleteIfExists(getThumbFile(context, item.id).toPath());
                } else {
                    remain.add(item);
                }
            }
            saveLibrary(context, remain);
        } catch (Exception ignored) {
        }
    }

    /** 旧版迁移：把没有 lib_id 的历史壁纸全部归入指定库（仅补空值，幂等）。 */
    public static void migrateLegacyToLib(Context context, String libId) {
        try {
            List<Item> items = load(context);
            boolean changed = false;
            for (Item item : items) {
                if (item.libId == null || item.libId.isEmpty()) {
                    item.libId = libId;
                    changed = true;
                }
            }
            if (changed) {
                saveLibrary(context, items);
            }
        } catch (Exception ignored) {
        }
    }

    /** 读取某张壁纸的缩略图，读不到返回 null。 */
    public static Bitmap getThumb(Context context, String id) {
        File thumbFile = getThumbFile(context, id);
        return BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
    }

    /** 获取某张壁纸的全图文件（新数据是无损 PNG，历史数据是 JPEG，都兼容）。 */
    public static File getFullFile(Context context, String id) {
        File dir = new File(context.getFilesDir(), DIR_FULL);
        File current = new File(dir, id + FULL_EXT);
        if (current.exists()) {
            return current;
        }
        File legacy = new File(dir, id + LEGACY_FULL_EXT);
        return legacy.exists() ? legacy : current;
    }

    /** 删除某张壁纸的全图（PNG 与历史 JPEG 都要删，避免切到无损后留下孤儿文件）。 */
    private static void deleteFullFiles(Context context, String id) throws IOException {
        File dir = new File(context.getFilesDir(), DIR_FULL);
        Files.deleteIfExists(new File(dir, id + FULL_EXT).toPath());
        Files.deleteIfExists(new File(dir, id + LEGACY_FULL_EXT).toPath());
    }

    /** 获取收件箱中的待编辑文件。 */
    public static File getInboxFile(Context context, String inboxId) {
        File dir = new File(context.getFilesDir(), DIR_INBOX);
        return new File(dir, inboxId);
    }

    /** 获取某张壁纸的缩略图文件。 */
    private static File getThumbFile(Context context, String id) {
        File dir = new File(context.getFilesDir(), DIR_THUMB);
        return new File(dir, id + ".jpg");
    }

    /** 按最长边上限解码文件位图，超大图自动降采样防 OOM；解码失败返回 null。 */
    static Bitmap decodeBounded(File file, int maxDim) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = calcSampleSize(bounds.outWidth, bounds.outHeight, maxDim);
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        Bitmap scaled = scaleToFit(bitmap, maxDim);
        // 缩放会产生第二份位图，原始那份要立刻回收，否则大图会瞬时占两份内存
        if (scaled != bitmap && bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        return scaled;
    }

    /** 计算 inSampleSize（2 的幂），保证解码后最长边不超过 maxDim。 */
    private static int calcSampleSize(int width, int height, int maxDim) {
        long longest = Math.max(width, height);
        int sample = 1;
        // 只要「再放粗一倍后最长边仍不低于 maxDim」就继续放粗，保证解码结果不小于目标分辨率。
        // 老逻辑是「一旦低于 maxDim 就停」，会把 8981 的源图解成 2245（2 的幂只能取 1/2/4…，
        // 4 就掉到 2245、2 又太大），于是裁剪取景框被放大、导出连屏幕分辨率都达不到。
        while (sample * 2L * maxDim <= longest) {
            sample *= 2;
        }
        // 像素预算兜底：超过堆预算就继续放粗，宁可略糊也不能 OOM
        long budget = decodePixelBudget();
        while ((long) (width / sample) * (height / sample) > budget) {
            sample *= 2;
        }
        return sample;
    }

    /** 解码像素预算：应用堆上限的 1/4 再按 4 字节/像素折算，下限 4MP、上限 24MP。 */
    private static long decodePixelBudget() {
        long pixels = Runtime.getRuntime().maxMemory() / 16;
        if (pixels < 4L * 1024 * 1024) {
            return 4L * 1024 * 1024;
        }
        return Math.min(pixels, 24L * 1024 * 1024);
    }

    /**
     * 解码裁剪页源图：在像素预算内取<b>最小</b>的 2 的幂采样（采样越小越清晰），解码后不再二次缩小。
     * 原来的 decodeBounded 会在采样之后再用 scaleToFit 缩到一个固定上限，等于把预算里还剩的
     * 清晰度又扔掉一次 —— 这是「尽可能清晰」的关键一步。
     * 返回位图像素数不超过 decodePixelBudget()，因此内存可控。
     */
    public static Bitmap decodeCropSource(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        long budget = decodePixelBudget();
        int sample = 1;
        while (pixelsAt(bounds.outWidth, bounds.outHeight, sample) > budget) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    /**
     * 只解码原图里的某个区域：导出时按实际取景框回原图取那一块，
     * 这样「放大多少倍」都能拿到原图分辨率，不再依赖事先猜测的放大余量。
     *
     * 采样取「该区域最长边不低于 maxDim」的最小 2 的幂（越小越清晰），解完再精确缩到 maxDim。
     * 格式不支持、区域越界等情况下返回 null，调用方回退到「从内存位图裁」。
     */
    public static Bitmap decodeRegion(File file, Rect region, int maxDim) {
        if (file == null || region == null || region.width() <= 0 || region.height() <= 0) {
            return null;
        }
        BitmapRegionDecoder decoder = null;
        try {
            decoder = BitmapRegionDecoder.newInstance(file.getAbsolutePath(), false);
            if (decoder == null) {
                return null;
            }
            int longest = Math.max(region.width(), region.height());
            int sample = 1;
            while (sample * 2L * maxDim <= longest) {
                sample *= 2;
            }
            long budget = decodePixelBudget();
            while (pixelsAt(region.width(), region.height(), sample) > budget) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap regionBitmap = decoder.decodeRegion(region, opts);
            if (regionBitmap == null) {
                return null;
            }
            Bitmap scaled = scaleToFit(regionBitmap, maxDim);
            if (scaled != regionBitmap && !regionBitmap.isRecycled()) {
                regionBitmap.recycle();
            }
            return scaled;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (decoder != null) {
                decoder.recycle();
            }
        }
    }

    /** 按采样倍数估算解码后的像素数（向上取整，宁可保守）。 */
    private static long pixelsAt(int width, int height, int sample) {
        return (long) Math.ceil(width / (double) sample) * (long) Math.ceil(height / (double) sample);
    }

    /** 等比缩放位图使最长边不超过 maxDim；位图为空或无需缩放时原样返回。 */
    private static Bitmap scaleToFit(Bitmap src, int maxDim) {
        if (src == null) {
            return null;
        }
        int longest = Math.max(src.getWidth(), src.getHeight());
        if (longest <= maxDim) {
            return src;
        }
        float scale = (float) maxDim / longest;
        int newW = Math.round(src.getWidth() * scale);
        int newH = Math.round(src.getHeight() * scale);
        return Bitmap.createScaledBitmap(src, newW, newH, true);
    }

    /** 把列表写回 library.json。 */
    private static void saveLibrary(Context context, List<Item> items) throws Exception {
        JSONArray arr = new JSONArray();
        for (Item item : items) {
            JSONObject obj = new JSONObject();
            obj.put("id", item.id);
            obj.put("lib_id", item.libId == null ? "" : item.libId);
            obj.put("title", item.title == null ? "" : item.title);
            arr.put(obj);
        }
        File libFile = new File(context.getFilesDir(), LIB_FILE);
        String json = arr.toString();
        byte[] bytes = json.getBytes("UTF-8");
        Files.write(libFile.toPath(), bytes);
    }
}
