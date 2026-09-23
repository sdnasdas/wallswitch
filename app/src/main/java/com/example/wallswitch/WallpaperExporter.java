package com.example.wallswitch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 把壁纸导出到「用户自己选的目录」（SAF，ACTION_OPEN_DOCUMENT_TREE）。
 *
 * 为什么用 SAF：Android 10 起分区存储，直接写 /sdcard 这种路径已不可行。SAF 让用户自己指定一个
 * 文件夹（例如 Documents/wallswitch），App 拿到该目录的持久化读写授权后就能往里写。
 * 相比 MediaStore 的好处：文件不会出现在系统相册里，且用户完全掌控位置。
 *
 * 注意两点：
 * 1) 导出的是**已入库的全图文件的原始字节**，不解码不重压，所以不会引入任何画质损失；
 * 2) 卸载重装后持久化授权会失效（需要重新点一次选目录），但**已经导出的文件仍然在**。
 */
public final class WallpaperExporter {

    private static final String PREFS_NAME = "settings";
    private static final String KEY_TREE_URI = "export_tree_uri";

    private WallpaperExporter() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 用户选定的导出目录；未设置返回 null。 */
    public static Uri treeUri(Context context) {
        String value = prefs(context).getString(KEY_TREE_URI, null);
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    /** 是否已设置导出目录。 */
    public static boolean isConfigured(Context context) {
        return treeUri(context) != null;
    }

    /** 记住导出目录，并申请持久化授权（个别 ROM 可能拒绝持久化，此时本次进程内仍可用）。 */
    public static void setTreeUri(Context context, Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply();
    }

    /** 取消导出目录（不清除已经导出的文件）。 */
    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_TREE_URI).apply();
    }

    /** 目录显示名（路径最后一段），仅用于界面回显。 */
    public static String displayName(Context context) {
        Uri tree = treeUri(context);
        if (tree == null) {
            return "";
        }
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 把一张已入库的全图按原始字节复制到导出目录（不解码、不重压，零画质损失）。
     * 同名文件由系统自动改名。纯 IO，调用方放在后台线程。
     */
    public static boolean exportFile(Context context, File source, String title, String id) {
        Uri tree = treeUri(context);
        if (tree == null || source == null || !source.exists()) {
            return false;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            String mime = source.getName().endsWith(FULL_EXT_PNG) ? "image/png" : "image/jpeg";
            Uri doc = DocumentsContract.createDocument(
                    context.getContentResolver(), parent, mime, exportName(title, id, source));
            if (doc == null) {
                return false;
            }
            in = new FileInputStream(source);
            out = context.getContentResolver().openOutputStream(doc);
            if (out == null) {
                return false;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /**
     * 把库内全部壁纸导出到目录，返回成功张数。
     * 纯 IO，调用方放在后台线程。
     */
    public static int exportAll(Context context) {
        if (!isConfigured(context)) {
            return 0;
        }
        int ok = 0;
        List<WallpaperStore.Item> items = WallpaperStore.load(context);
        for (WallpaperStore.Item item : items) {
            File file = WallpaperStore.getFullFile(context, item.id);
            if (exportFile(context, file, item.title, item.id)) {
                ok++;
            }
        }
        return ok;
    }

    /** 导出文件名：标题 + id 前 6 位，避免重名；标题里的非法字符替换掉。 */
    private static String exportName(String title, String id, File source) {
        String base = title == null || title.isEmpty() ? "wallpaper" : title;
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        String suffix = id == null ? "0" : id.substring(0, Math.min(6, id.length()));
        return base + "_" + suffix + (source.getName().endsWith(FULL_EXT_PNG) ? ".png" : ".jpg");
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 与 WallpaperStore 保持一致：全图的新格式扩展名。 */
    private static final String FULL_EXT_PNG = ".png";
}
