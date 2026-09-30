package com.example.wallswitch;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 结构化元数据文件（library.json / libraries.json）的读写守卫。
 *
 * 这两个文件是全库唯一的元数据来源，写坏一次就全库消失，所以：
 * 1) 写一律「写临时文件 + 刷盘 + rename 覆盖」，让 rename 成为唯一的提交点。
 *    直接覆写会先 truncate 成 0 字节再写，中途被 force-stop 就留下半截文件；
 *    更要紧的是 truncate 与首次写之间只要有人读到，拿到的就是空文件。
 * 2) 读失败先按「括号配对的完整前缀」救回前面那些条目，救回来的立刻原子写回，
 *    并把残缺的原件留档成 <文件名>.broken，让人还能手工捡。
 * 3) 救不回来时只记录、不清场：文件留在原地，下一次读照样失败，任何写路径都会先读到它，
 *    于是自动覆盖只会在「上一轮已经拿到完整数据」之后发生，不会存在覆盖坏文件的窗口。
 *    （反过来「读失败就把文件改名挪走」是最危险的：文件没了，load() 从此返回空列表
 *    且看不出异常，下一次 saveLibrary 就把这份空当基线写回去，全库静默消失。）
 */
public final class MetaFiles {

    /** 上一次读元数据时留下的提示，null 表示一切正常。 */
    public static final class Notice {
        public final String fileName;
        /** true：救回了一部分（已原子写回）；false：整份读不动（已留档，原文件保持坏着以拦住写路径）。 */
        public final boolean salvaged;
        public final int kept;
        public final int dropped;
        public final String archivePath;

        Notice(String fileName, boolean salvaged, int kept, int dropped, String archivePath) {
            this.fileName = fileName;
            this.salvaged = salvaged;
            this.kept = kept;
            this.dropped = dropped;
            this.archivePath = archivePath;
        }
    }

    // 括号嵌套上限：真实载荷最多两层，超过这个数只会是坏数据造成的一串开括号
    private static final int MAX_NESTING = 32;
    // 救回/留档只处理这个长度以内的内容，超大坏文件不再逐条试解析（本来就是极端场景）
    private static final int MAX_NOTICE_CHARS = 256 * 1024;
    // 往前退让的条数上限：正常断开只会落在最后一条元素附近，退太多只会把成本摊到整份文件上
    private static final int MAX_SALVAGE_TRIES = 8;

    private static volatile Notice pendingNotice;

    private MetaFiles() {
    }

    /** 原子写：临时文件 → flush+fd.sync → 原子 rename 覆盖目标（rename 是唯一的提交点）。 */
    public static void writeJson(Context context, String fileName, String content) throws IOException {
        File dir = context.getFilesDir();
        String tmpName = fileName + ".tmp-" + UUID.randomUUID();
        File tmp = new File(dir, tmpName);
        try {
            try (FileOutputStream out = new FileOutputStream(tmp);
                 Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                writer.write(content);
                writer.flush();
                // sync 保证 rename 抢在前头时数据也已经在盘上（ROM 强杀进程时尤其要紧）
                out.getFD().sync();
            }
            try {
                Files.move(tmp.toPath(), new File(dir, fileName).toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 极少数文件系统不支持原子移动（或开发机是 Windows：rename 不允许覆盖已存在文件），
                // 退回「先删再改名」；设备上 tmp 与目标同在 filesDir，正常都走得到上面的 ATOMIC_MOVE
                File dst = new File(dir, fileName);
                if (dst.exists() && !dst.delete()) {
                    throw e;
                }
                if (!tmp.renameTo(dst)) {
                    throw e;
                }
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
    }

    /**
     * 读数组载荷：正常解析失败时先试前缀救回，再留档并登记提示。
     * 调用方拿到的就是「完整数据」或「空数组」，空数组这一次不会覆盖磁盘上的原件。
     */
    public static JSONArray readJson(Context context, String fileName, byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        try {
            return new JSONArray(text);
        } catch (JSONException ignored) {
        }
        String prefix = longestValidPrefix(text);
        if (prefix != null) {
            try {
                JSONArray arr = new JSONArray(prefix);
                // 救回来的东西必须立刻原子写回：只留在内存里，中途被杀就又只剩那份坏文件
                writeJson(context, fileName, arr.toString());
                String archive = archiveCorrupt(context, fileName, raw);
                int total = countTopLevelElements(text);
                note(new Notice(fileName, true, arr.length(), Math.max(0, total - arr.length()), archive));
                return arr;
            } catch (Exception | OutOfMemoryError ignored2) {
            }
        }
        // 救不动：原件原样留在原地（后续每次读都会同样失败，于是写路径被自然拦住，
        // 不存在「load() 返回空 → 下一次保存把空写回去」的覆盖窗口），只另存一份供人工捡。
        String archive = archiveCorrupt(context, fileName, raw);
        note(new Notice(fileName, false, 0, 0, archive));
        return new JSONArray();
    }

    /** 取走并清空上一次登记的提示（界面层启动时用一次）。 */
    public static Notice takeNotice() {
        Notice n = pendingNotice;
        pendingNotice = null;
        return n;
    }

    private static void note(Notice notice) {
        pendingNotice = notice;
    }

    /** 把残缺原件另存一份留档，供人工捡；原文件本身保持不动。 */
    private static String archiveCorrupt(Context context, String fileName, byte[] raw) {
        File src = new File(context.getFilesDir(), fileName);
        if (!src.exists()) {
            return null;
        }
        File dst = new File(context.getFilesDir(), fileName + ".broken-" + System.currentTimeMillis());
        try {
            Files.write(dst.toPath(), raw);
            return dst.getAbsolutePath();
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    /**
     * 半截 JSON 的成因是「写到一半被打断」，所以断点之前的若干条元素一定是完整的。
     * 这里从头扫一遍，记下每个「一条元素刚好写完」的位置，从最靠后的那个开始往前逐条退让，
     * 每次补上当时还开着的括号的收尾再试解析。
     * 逐条退让（而不是只在断开点收尾试一次）是为了对付两种常见断法：
     * 断在「两条之间的逗号之后」—— 补上收尾会得 `[{a},{b},]`，尾逗号不是合法 JSON；
     * 断在「某条对象的中间」—— 那一层还没闭合，只能在它前面那条的末尾收尾。
     */
    private static String longestValidPrefix(String text) {
        List<int[]> closes = completedElementCloses(text);
        if (closes == null) {
            // 括号错配（不是截断会造成的形态），交上层留档
            return null;
        }
        for (int i = closes.size() - 1, tries = 0; i >= 0 && tries < MAX_SALVAGE_TRIES; i--, tries++) {
            int end = closes.get(i)[0];
            String suffix = closes.get(i)[1] == 0 ? "" : "]";
            String candidate = text.substring(0, end + 1) + suffix;
            if (candidate.length() > MAX_NOTICE_CHARS) {
                continue;
            }
            try {
                new JSONArray(candidate);
                return candidate;
            } catch (JSONException ignored) {
            }
        }
        return null;
    }

    /**
     * 收集「顶层数组里一条元素刚好闭合」的位置，以及收在那儿时还开着几层括号（0 或 1）。
     * 返回 null 表示括号错配；空列表表示一条都没写完（救不出东西）。
     */
    private static List<int[]> completedElementCloses(String text) {
        List<int[]> closes = new ArrayList<>();
        char[] stack = new char[MAX_NESTING + 1];
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = skipString(text, i);
                if (end < 0) {
                    // 字符串没结束就被打断：它所属的那条元素不算写完，后面也没有可信内容了
                    break;
                }
                i = end;
                continue;
            }
            if (c == '[' || c == '{') {
                if (depth >= MAX_NESTING) {
                    break;
                }
                stack[depth++] = c;
                continue;
            }
            if (c != ']' && c != '}') {
                continue;
            }
            if (depth == 0 || (c == ']') != (stack[depth - 1] == '[')) {
                // 括号错配（不是截断会造成的小文件形态）：交给上层留档
                return null;
            }
            depth--;
            if (c == '}' && depth == 1) {
                closes.add(new int[]{i, depth});
            } else if (c == ']' && depth == 0) {
                closes.add(new int[]{i, 0});
            }
        }
        return closes;
    }

    /** 跳过从 quote 开始的字符串字面量，返回结束引号下标；没结束返回 -1。 */
    private static int skipString(String text, int quoteIndex) {
        for (int i = quoteIndex + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /** 原文件里顶层元素的条数（用来算「可能丢了几条」）：只数括号配对到顶层的那些。 */
    private static int countTopLevelElements(String text) {
        int count = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = skipString(text, i);
                if (end < 0) {
                    break;
                }
                if (depth == 1) {
                    count++;
                }
                i = end;
                continue;
            }
            if (c == '[' || c == '{') {
                if (depth == 1) {
                    count++;
                }
                if (depth < MAX_NESTING) {
                    depth++;
                }
                continue;
            }
            if (c == ']' || c == '}') {
                if (depth > 0) {
                    depth--;
                }
            }
        }
        return count;
    }
}
