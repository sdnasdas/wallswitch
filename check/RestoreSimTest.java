import org.json.JSONArray;
import org.json.JSONObject;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把 BackupStore.restore() 的流程在 JVM 上一比一跑一遍（吃真机导出的备份包）：
 * 落文件 -> prefs 逐 key 写回 -> dropMissingImages -> retagUnknownLibs，然后和
 * 用户还原前那份「元数据快照」做 diff，看还原到底动了什么。
 * 解析/剪枝/改挂的逻辑是 BackupStore 的副本（去掉 android 依赖），改了产品代码要同步这里。
 */
public class RestoreSimTest {

    static final Set<String> SKIP_KEYS = new HashSet<>();
    static {
        SKIP_KEYS.add("export_tree_uri");
        SKIP_KEYS.add("restore_prompt_shown");
        SKIP_KEYS.add("meta_dump_seq");
    }

    static class Editor {
        final Map<String, Object> out = new LinkedHashMap<>();
        final Map<String, String> types = new LinkedHashMap<>();

        void put(String k, String type, Object v) { out.put(k, v); types.put(k, type); }
    }

    // ===== BackupStore.isValueTag / parseInto 的副本 =====
    static boolean isValueTag(String tag) {
        if (tag.indexOf('_') >= 0) {
            return false;
        }
        return "string".equals(tag) || "int".equals(tag) || "long".equals(tag)
                || "float".equals(tag) || "boolean".equals(tag) || "hash".equals(tag);
    }

    static int parseInto(byte[] raw, Editor editor) {
        int applied = 0;
        try {
            XMLStreamReader r = XMLInputFactory.newInstance()
                    .createXMLStreamReader(new ByteArrayInputStream(raw));
            String name = null, type = null, attrValue = null;
            StringBuilder text = new StringBuilder();
            int depth = 0;
            while (r.hasNext()) {
                int event = r.next();
                String tag = (r.isStartElement() || r.isEndElement()) ? r.getLocalName() : null;
                if (event == XMLStreamConstants.START_ELEMENT && tag != null) {
                    boolean valueTag = isValueTag(tag);
                    if (valueTag && depth == 1) {
                        name = r.getAttributeValue(null, "name");
                        attrValue = r.getAttributeValue(null, "value");
                        type = tag;
                        text.setLength(0);
                    } else {
                        if (valueTag) { type = null; name = null; }
                        depth++;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && type != null) {
                    text.append(r.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT && tag != null) {
                    if (!isValueTag(tag) && depth > 0) depth--;
                    if (type != null && depth == 1) {
                        applied += putValue(editor, name, type, attrValue != null ? attrValue : text.toString());
                        type = null; name = null; attrValue = null;
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return applied;
    }

    static int putValue(Editor editor, String name, String type, String value) {
        if (name == null || name.isEmpty() || type == null) {
            return 0;
        }
        if (SKIP_KEYS.contains(name)) {
            return 0;
        }
        try {
            if ("int".equals(type)) editor.put(name, type, Integer.parseInt(value));
            else if ("long".equals(type)) editor.put(name, type, Long.parseLong(value));
            else if ("float".equals(type)) editor.put(name, type, Float.parseFloat(value));
            else if ("boolean".equals(type)) editor.put(name, type, Boolean.parseBoolean(value));
            else editor.put(name, type, value);
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    static int pass = 0, fail = 0;

    static void ok(boolean cond, String what) {
        System.out.println((cond ? "PASS  " : "FAIL  ") + what);
        if (cond) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法：RestoreSimTest <备份包.zip> <还原前的快照目录>");
            System.exit(2);
        }
        File zipFile = new File(args[0]);
        File snapshot = new File(args[1]);
        File out = new File("check/work/restore");
        recursiveDelete(out);
        out.mkdirs();
        new File(out, "wallpapers").mkdirs();
        new File(out, "thumbs").mkdirs();

        ZipFile zip = new ZipFile(zipFile);          // 构造即校验中央目录；坏包在这里就抛
        System.out.println("包打开成功（中央目录在位，说明传输没被截断）");

        int images = 0, thumbs = 0;
        byte[] libraryRaw = null, librariesRaw = null;
        Map<String, byte[]> prefs = new LinkedHashMap<>();
        java.util.Enumeration<? extends ZipEntry> es = zip.entries();
        while (es.hasMoreElements()) {
            ZipEntry e = es.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName();
            byte[] bytes = read(zip, e);
            if (n.startsWith("wallpapers/")) { write(new File(new File(out, "wallpapers"), base(n)), bytes); images++; }
            else if (n.startsWith("thumbs/")) { write(new File(new File(out, "thumbs"), base(n)), bytes); thumbs++; }
            else if (n.equals("library.json")) libraryRaw = bytes;
            else if (n.equals("libraries.json")) librariesRaw = bytes;
            else if (n.startsWith("prefs/")) prefs.put(n, bytes);
            else write(new File(out, base(n)), bytes);          // 底图/日志/manifest 原样落
        }
        System.out.println("落盘：wallpapers " + images + " 个、thumbs " + thumbs + " 个、prefs " + prefs.size() + " 份");

        // ===== 1. 图片/缩略图与元数据一一对上（决定 dropMissingImages 会不会剪） =====
        JSONArray lib = new JSONArray(new String(libraryRaw, StandardCharsets.UTF_8));
        JSONArray libs = new JSONArray(new String(librariesRaw, StandardCharsets.UTF_8));
        Set<String> libIds = new HashSet<>();
        for (int i = 0; i < libs.length(); i++) libIds.add(libs.getJSONObject(i).getString("id"));

        int missing = 0;
        List<String> missingIds = new ArrayList<>();
        for (int i = 0; i < lib.length(); i++) {
            String id = lib.getJSONObject(i).getString("id");
            boolean present = new File(new File(out, "wallpapers"), id + ".png").exists()
                    || new File(new File(out, "wallpapers"), id + ".jpg").exists();
            if (!present) { missing++; missingIds.add(id); }
        }
        System.out.println("\n===== dropMissingImages 会剪几条 =====");
        System.out.println("  元数据 " + lib.length() + " 条，盘上缺图的 " + missing + " 条 " + missingIds);
        ok(missing == 0, "包自洽：每条元数据都有对应图片，还原不会剪掉任何一条");

        // ===== 2. retagUnknownLibs 会不会触发 =====
        int unknown = 0;
        for (int i = 0; i < lib.length(); i++) {
            String lid = lib.getJSONObject(i).optString("lib_id", "");
            if (lid.isEmpty() || !libIds.contains(lid)) unknown++;
        }
        System.out.println("\n===== retagUnknownLibs =====");
        System.out.println("  库里 " + libs.length() + " 个 id；指向未知库的条目 " + unknown + " 条");
        ok(unknown == 0, "归属全部对得上，不会触发改写（改挂是永久写回 library.json 的操作）");

        // ===== 3. prefs 逐 key 写回 =====
        System.out.println("\n===== prefs 写回 =====");
        for (Map.Entry<String, byte[]> p : prefs.entrySet()) {
            Editor ed = new Editor();
            int applied = parseInto(p.getValue(), ed);
            System.out.println("  " + p.getKey() + "：写回 " + applied + " 个 key，文件里共 "
                    + countEntries(p.getValue()) + " 个");
            if (p.getKey().endsWith("settings.xml")) {
                ok(applied == countEntries(p.getValue()) - skippedCount(p.getValue()), "写回数 = 文件里的键数减去跳过的本机 key");
                ok(!ed.out.containsKey("export_tree_uri"), "export_tree_uri 被跳过（授权已随 UID 失效）");
                // 键名按真实存储来：锚点是 next_trigger_<库id>，没有 timer_enabled 这个键
                String homeLib = String.valueOf(ed.out.get("slot_home_lib"));
                System.out.println("    桌面槽位：库=" + homeLib
                        + " 模式=" + ed.out.get("slot_home_mode")
                        + " 间隔=" + ed.out.get("slot_home_interval") + "s"
                        + " 锚点=" + ed.out.get("next_trigger_" + homeLib));
                System.out.println("    锁屏槽位：库=" + ed.out.get("slot_lock_lib")
                        + " 间隔=" + ed.out.get("slot_lock_interval") + "s"
                        + " 暂停=" + ed.out.get("slot_lock_paused"));
                System.out.println("    桌面当前张=" + ed.out.get("p_" + homeLib + "_h_current")
                        + " 序号=" + ed.out.get("p_" + homeLib + "_h_seq"));
                System.out.println("    跳过未写回的：" + skippedNames(p.getValue()));
            }
        }

        // ===== 4. 与还原前快照 diff =====
        File before = new File(snapshot, "library.json");
        System.out.println("\n===== 与还原前的快照 diff（library.json）=====");
        if (before.exists()) {
            JSONArray old = new JSONArray(new String(Files.readAllBytes(before.toPath()), StandardCharsets.UTF_8));
            System.out.println("  快照 " + old.length() + " 条 -> 包 " + lib.length() + " 条");
            int same = 0, moved = 0, renamed = 0;
            List<String> added = new ArrayList<>(), gone = new ArrayList<>();
            Map<String, JSONObject> oldById = new LinkedHashMap<>();
            for (int i = 0; i < old.length(); i++) { JSONObject o = old.getJSONObject(i); oldById.put(o.getString("id"), o); }
            Map<String, JSONObject> newById = new LinkedHashMap<>();
            for (int i = 0; i < lib.length(); i++) { JSONObject o = lib.getJSONObject(i); newById.put(o.getString("id"), o); }
            for (String id : newById.keySet()) if (!oldById.containsKey(id)) added.add(id);
            for (String id : oldById.keySet()) if (!newById.containsKey(id)) gone.add(id);
            for (String id : newById.keySet()) {
                JSONObject a = oldById.get(id), b = newById.get(id);
                if (a == null) continue;
                if (a.optString("lib_id").equals(b.optString("lib_id"))
                        && a.optString("title").equals(b.optString("title"))) same++;
                else if (!a.optString("lib_id").equals(b.optString("lib_id"))) moved++;
                else renamed++;
            }
            System.out.println("  共有条目里：完全一致 " + same + "、归属变了 " + moved
                    + "、标题变了 " + renamed + "；新增 " + added.size() + "、少了 " + gone.size());
            ok(moved == 0 && renamed == 0 && gone.isEmpty(),
                    "还原没动既有壁纸的归属与标题（新增 " + added.size() + " 条是你备份前自己加的）");
            for (String id : gone) System.out.println("    少了的条目：" + id + "（" + oldById.get(id).optString("title") + "）");
        } else {
            System.out.println("  （没找到还原前的快照目录，跳过 diff）");
        }

        zip.close();
        System.out.println("\n== restore sim: " + pass + " passed, " + fail + " failed ==");
        System.out.println("== 解出来的目录留在 " + out.getAbsolutePath() + "，可自行翻查 ==");
        if (fail > 0) System.exit(1);
    }

    static String shortPrefix(Map<String, Object> out) {
        for (String k : out.keySet()) {
            if (k.startsWith("p_") && k.endsWith("_h_current")) {
                return k.substring(2, k.length() - "_h_current".length());
            }
        }
        return "?";
    }

    static int histLen(Map<String, Object> out) {
        for (Map.Entry<String, Object> e : out.entrySet()) {
            if (e.getKey().endsWith("_h_hist")) {
                try { return new JSONArray(String.valueOf(e.getValue())).length(); } catch (Exception ex) { return -1; }
            }
        }
        return -1;
    }

    /** 文件里顶层键的总数（含被跳过的三个本机 key）。 */
    static int countEntries(byte[] raw) {
        return countAll(raw);
    }

    static int countAll(byte[] raw) {
        final int[] n = {0};
        try {
            XMLStreamReader r = XMLInputFactory.newInstance().createXMLStreamReader(new ByteArrayInputStream(raw));
            int depth = 0;
            while (r.hasNext()) {
                int event = r.next();
                String tag = (r.isStartElement() || r.isEndElement()) ? r.getLocalName() : null;
                if (event == XMLStreamConstants.START_ELEMENT && tag != null) {
                    if (isValueTag(tag) && depth == 1) n[0]++;
                    else depth++;
                } else if (event == XMLStreamConstants.END_ELEMENT && tag != null && !isValueTag(tag) && depth > 0) {
                    depth--;
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return n[0];
    }

    /** 文件里出现、且被我们跳过的 key 个数（好让「写回数 + 跳过数 = 总数」对得上）。 */
    static int skippedCount(byte[] raw) {
        int n = 0;
        for (String name : topLevelNames(raw)) {
            if (SKIP_KEYS.contains(name)) n++;
        }
        return n;
    }

    static List<String> skippedNames(byte[] raw) {
        List<String> out = new ArrayList<>();
        for (String name : topLevelNames(raw)) {
            if (SKIP_KEYS.contains(name)) out.add(name);
        }
        return out;
    }

    static List<String> topLevelNames(byte[] raw) {
        List<String> names = new ArrayList<>();
        try {
            XMLStreamReader r = XMLInputFactory.newInstance().createXMLStreamReader(new ByteArrayInputStream(raw));
            int depth = 0;
            while (r.hasNext()) {
                int event = r.next();
                String tag = (r.isStartElement() || r.isEndElement()) ? r.getLocalName() : null;
                if (event == XMLStreamConstants.START_ELEMENT && tag != null) {
                    if (isValueTag(tag) && depth == 1) names.add(r.getAttributeValue(null, "name"));
                    else depth++;
                } else if (event == XMLStreamConstants.END_ELEMENT && tag != null && !isValueTag(tag) && depth > 0) {
                    depth--;
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return names;
    }

    static String base(String entryName) {
        String n = entryName.replace('\\', '/');
        int s = n.lastIndexOf('/');
        return s >= 0 ? n.substring(s + 1) : n;
    }

    static byte[] read(ZipFile zip, ZipEntry e) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = zip.getInputStream(e)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    static void write(File f, byte[] bytes) throws Exception {
        Files.write(f.toPath(), bytes);
    }

    static void recursiveDelete(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) recursiveDelete(k);
        f.delete();
    }
}
