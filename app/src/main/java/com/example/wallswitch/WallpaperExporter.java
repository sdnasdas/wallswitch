package com.example.wallswitch;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把壁纸导出到「用户自己选的目录」（SAF，ACTION_OPEN_DOCUMENT_TREE）。
 *
 * 为什么用 SAF：Android 10 起分区存储，直接写 /sdcard 这种路径已不可行。SAF 让用户自己指定一个
 * 文件夹（例如 Documents/wallswitch），App 拿到该目录的持久化读写授权后就能往里写。
 * 相比 MediaStore 的好处：文件不会出现在系统相册里，且用户完全掌控位置。
 *
 * 注意三点：
 * 1) 导出的是**已入库的成品图文件的原始字节**，不解码不重压，所以不会引入任何画质损失；
 * 2) 卸载重装后持久化授权会失效（需要重新点一次选目录），但**已经导出的文件仍然在**；
 * 3) v3.89 起**图片**只剩两个手动入口：设置页「立即导出全部壁纸」与「备份整库」（zip 也写这个
 *    目录）。以前 {@code confirmImport} 每导入一张会自动复制一份出去，已按要求删掉 ——
 *    它会在用户没动手的情况下往目录里塞图，且重编过的壁纸不会更新那一份，只会叠副本。
 *    目录里还住着切换日志的两份 txt（{@link SwitchLog} 每次记日志同步一份过来），
 *    那是文本镜像不是图片出口，用户明确要求保留。
 */
public final class WallpaperExporter {

    private static final String PREFS_NAME = "settings";
    private static final String KEY_TREE_URI = "export_tree_uri";
    private static final String KEY_DOC_URI = "export_text_doc_uri";

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
     * 把一张已入库的成品图按原始字节复制到导出目录（不解码、不重压，零画质损失）。
     *
     * <p><b>同名是覆盖而不是叠副本</b>：SAF 的 {@code createDocument} 遇到同名不会覆盖，
     * 会自动改名成「标题_ab12cd(1).png」，所以必须先按显示名在目录里找出已有那份、
     * 拿它的 URI 重写。不这么做的话，每按一次「立即导出全部」目录里就多一整副本堆，
     * 而且改过构图的壁纸永远更新不到。
     *
     * <p>纯 IO，调用方放在后台线程。
     */
    public static boolean exportFile(Context context, File source, String title, String id) {
        return exportFile(context, source, title, id, null);
    }

    /**
     * @param index 「显示名 → 文档 URI」索引；传 null 表示自己列一次目录。
     *              批量导出由 {@link #exportAll} 建一份复用，免得每张都整表扫一遍。
     */
    private static boolean exportFile(Context context, File source, String title, String id,
                                      Map<String, Uri> index) {
        Uri tree = treeUri(context);
        if (tree == null || source == null || !source.exists()) {
            return false;
        }
        String name = exportName(title, id, source);
        String mime = source.getName().endsWith(FULL_EXT_PNG) ? "image/png" : "image/jpeg";
        InputStream in = null;
        OutputStream out = null;
        try {
            ContentResolver cr = context.getContentResolver();
            if (index == null) {
                index = listDocs(cr, tree);
            }
            Uri doc = index.get(name);
            if (doc == null) {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                doc = DocumentsContract.createDocument(cr, parent, mime, name);
                if (doc == null) {
                    return false;
                }
            }
            in = new FileInputStream(source);
            // 带 "wt"：截断重写。不传 mode 时个别 provider 会在旧内容上续写，留下半截旧尾巴
            out = cr.openOutputStream(doc, "wt");
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
     * 列一遍导出目录，返回「显示名 → 文档 URI」。
     *
     * <p>SAF 的 query 不保证支持按 DISPLAY_NAME 过滤（各家 provider 行为不一，有的直接忽略
     * selection），所以整表扫回来在内存里比。目录里通常就几十上百个文件，一次扫完复用。
     */
    private static Map<String, Uri> listDocs(ContentResolver cr, Uri tree) {
        Map<String, Uri> map = new HashMap<>();
        try (Cursor c = cr.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree)),
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (c == null) {
                return map;
            }
            while (c.moveToNext()) {
                String docId = c.getString(0);
                String displayName = c.getString(1);
                if (docId == null || displayName == null || map.containsKey(displayName)) {
                    continue;   // 目录里已有重名（用户自己放的，SAF 允许）：只认第一个
                }
                map.put(displayName, DocumentsContract.buildDocumentUriUsingTree(tree, docId));
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    /**
     * 把一段文本写到导出目录（同名覆盖）。用于同步切换日志。
     * 首次创建文档并记下 URI，之后用 "wt" 截断重写；文档被删/移动时清掉记录，下次重建。
     *
     * <p>URI 按<b>文件名</b>分开存：这里同时背着桌面日志、锁屏日志两个名字（清空时还有个历史
     * 名），以前共用一个键，等于第二个名字写进来时拿到的还是第一份文档的 URI —— 于是导出目录里
     * 只有一份文件，内容还是后写的那份。私有目录里两份都全，所以只有导出副本会串。
     */
    public static boolean writeTextFile(Context context, String displayName, String mime,
                                        String content) {
        Uri tree = treeUri(context);
        if (tree == null || content == null) {
            return false;
        }
        OutputStream out = null;
        try {
            Uri doc = existingDocUri(context, displayName);
            if (doc == null) {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                doc = DocumentsContract.createDocument(
                        context.getContentResolver(), parent, mime, displayName);
                if (doc == null) {
                    return false;
                }
                putDocUri(context, displayName, doc);
            }
            out = context.getContentResolver().openOutputStream(doc, "wt");
            if (out == null) {
                return false;
            }
            out.write(content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            prefs(context).edit().remove(docKey(displayName)).apply();
            return false;
        } finally {
            closeQuietly(out);
        }
    }

    /** 每个文件名一个键；老的那个单键（v3.89 前共用）不再读，留着无害。 */
    private static String docKey(String displayName) {
        return KEY_DOC_URI + "#" + displayName;
    }

    private static Uri existingDocUri(Context context, String displayName) {
        String value = prefs(context).getString(docKey(displayName), null);
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    private static void putDocUri(Context context, String displayName, Uri doc) {
        prefs(context).edit().putString(docKey(displayName), doc.toString()).apply();
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
        // 目录只列一次，整批复用（每张都扫一遍的话，库大一点就是几十次整表查询）
        Map<String, Uri> index = listDocs(context.getContentResolver(), treeUri(context));
        List<WallpaperStore.Item> items = WallpaperStore.load(context);
        for (WallpaperStore.Item item : items) {
            File file = WallpaperStore.getFullFile(context, item.id);
            if (exportFile(context, file, item.title, item.id, index)) {
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
