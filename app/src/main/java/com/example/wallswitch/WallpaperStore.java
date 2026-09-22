package com.example.wallswitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
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
 * - wallpapers/ 全图，文件名 &lt;uuid&gt;.jpg（JPEG quality 90）
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
    // JPEG 压缩质量
    private static final int JPEG_QUALITY = 90;
    // 解码尺寸上限（防 OOM）
    private static final int MAX_DECODE_DIM = 2048;
    // 缩略图最长边
    private static final int THUMB_MAX_DIM = 256;

    /** 壁纸库条目元数据。 */
    public static class Item {
        public String id;
        public boolean home;
        public boolean lock;
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
                item.home = obj.optBoolean("home", true);
                item.lock = obj.optBoolean("lock", true);
                result.add(item);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** 把相册选中的图片原样复制进收件箱，返回仅带 id 的条目（尚未入库）。 */
    public static Item importToInbox(Context context, Uri uri) throws Exception {
        String id = UUID.randomUUID().toString();
        File inboxDir = new File(context.getFilesDir(), DIR_INBOX);
        if (!inboxDir.exists()) {
            inboxDir.mkdirs();
        }
        File target = new File(inboxDir, id);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Item item = new Item();
        item.id = id;
        return item;
    }

    /** 确认导入（无编辑结果时）：按上限解码收件箱原图后入库。 */
    public static Item confirmImport(Context context, String inboxId, boolean home, boolean lock) throws Exception {
        File inboxFile = getInboxFile(context, inboxId);
        Bitmap edited = decodeBounded(inboxFile, MAX_DECODE_DIM);
        return confirmImport(context, inboxId, edited, home, lock);
    }

    /** 确认导入（带编辑结果）：保存全图与缩略图、追加元数据，并删除收件箱原文件。 */
    public static Item confirmImport(Context context, String inboxId, Bitmap edited, boolean home, boolean lock) throws Exception {
        Bitmap bitmap = edited;
        if (bitmap == null) {
            File sourceFile = getInboxFile(context, inboxId);
            bitmap = decodeBounded(sourceFile, MAX_DECODE_DIM);
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
        // 保存全图
        File fullFile = new File(fullDir, id + ".jpg");
        FileOutputStream fullOut = new FileOutputStream(fullFile);
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fullOut);
        fullOut.close();
        // 保存缩略图
        Bitmap thumb = scaleToFit(bitmap, THUMB_MAX_DIM);
        File thumbFile = new File(thumbDir, id + ".jpg");
        FileOutputStream thumbOut = new FileOutputStream(thumbFile);
        thumb.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, thumbOut);
        thumbOut.close();
        // 追加元数据
        List<Item> items = load(context);
        Item item = new Item();
        item.id = id;
        item.home = home;
        item.lock = lock;
        items.add(item);
        saveLibrary(context, items);
        // 删除收件箱原文件
        File inboxFile = getInboxFile(context, inboxId);
        Files.deleteIfExists(inboxFile.toPath());
        return item;
    }

    /** 取消导入：删除收件箱中的待编辑文件。 */
    public static void cancelImport(Context context, String inboxId) {
        try {
            File inboxFile = getInboxFile(context, inboxId);
            Files.deleteIfExists(inboxFile.toPath());
        } catch (Exception ignored) {
        }
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
            ids.add(file.getName());
        }
        Collections.sort(ids);
        return ids;
    }

    /** 删除壁纸：移除全图、缩略图文件，并从 library.json 中移除记录。 */
    public static void delete(Context context, String id) {
        try {
            File fullFile = getFullFile(context, id);
            File thumbFile = getThumbFile(context, id);
            Files.deleteIfExists(fullFile.toPath());
            Files.deleteIfExists(thumbFile.toPath());
            List<Item> items = load(context);
            List<Item> remain = new ArrayList<>();
            for (Item item : items) {
                if (!item.id.equals(id)) {
                    remain.add(item);
                }
            }
            saveLibrary(context, remain);
        } catch (Exception ignored) {
        }
    }

    /** 更新某张壁纸的应用范围（桌面/锁屏）。 */
    public static void setScope(Context context, String id, boolean home, boolean lock) {
        try {
            List<Item> items = load(context);
            for (Item item : items) {
                if (item.id.equals(id)) {
                    item.home = home;
                    item.lock = lock;
                }
            }
            saveLibrary(context, items);
        } catch (Exception ignored) {
        }
    }

    /** 读取某张壁纸的缩略图，读不到返回 null。 */
    public static Bitmap getThumb(Context context, String id) {
        File thumbFile = getThumbFile(context, id);
        return BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
    }

    /** 获取某张壁纸的全图文件。 */
    public static File getFullFile(Context context, String id) {
        File dir = new File(context.getFilesDir(), DIR_FULL);
        return new File(dir, id + ".jpg");
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
        return scaleToFit(bitmap, maxDim);
    }

    /** 计算 inSampleSize（2 的幂），保证解码后最长边不超过 maxDim。 */
    private static int calcSampleSize(int width, int height, int maxDim) {
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / sample > maxDim) {
            sample *= 2;
        }
        return sample;
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
            obj.put("home", item.home);
            obj.put("lock", item.lock);
            arr.put(obj);
        }
        File libFile = new File(context.getFilesDir(), LIB_FILE);
        String json = arr.toString();
        byte[] bytes = json.getBytes("UTF-8");
        Files.write(libFile.toPath(), bytes);
    }
}
