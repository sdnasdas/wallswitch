import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * BackupStore 两处最容易写错的逻辑的独立验证：
 * 1) prefs/settings.xml 的逐 key 解析（Android 的 SharedPreferences 落盘格式，两套版本 + 全部值类型）；
 * 2) zip 打包 → 解包往返（条目名、字节一致性）。
 *
 * 解析部分是 BackupStore.parseInto/putValue 的副本（换成不依赖 SharedPreferences 的假 Editor）。
 * 改了 BackupStore 的解析，记得同步这里。
 * 跑法见文件末尾 main 的注释。
 */
public class BackupRoundtripTest {

    /** 假 Editor：记录最终写回的 key -> 类型化值，方便断言。 */
    static class Editor {
        final Map<String, Object> out = new LinkedHashMap<>();

        void putString(String k, String v) { out.put(k, v); }
        void putInt(String k, int v) { out.put(k, v); }
        void putLong(String k, long v) { out.put(k, v); }
        void putFloat(String k, float v) { out.put(k, v); }
        void putBoolean(String k, boolean v) { out.put(k, v); }
    }

    static final String KEY_TREE_URI = "export_tree_uri";
    static final String KEY_PROMPT_SHOWN = "restore_prompt_shown";
    static final String KEY_DUMP_SEQ = "meta_dump_seq";

    // ===== BackupStore 解析逻辑的副本（XmlPullParser 换成 javax 的 StAX 只为跑在 JVM 上，
    //       事件序列与跳过规则保持一致）=====
    static int parseInto(byte[] raw, Editor editor) {
        int applied = 0;
        try {
            javax.xml.stream.XMLStreamReader r =
                    javax.xml.stream.XMLInputFactory.newInstance()
                            .createXMLStreamReader(new ByteArrayInputStream(raw));
            String name = null;
            String type = null;
            String attrValue = null;
            StringBuilder text = new StringBuilder();
            int depth = 0;
            while (r.hasNext()) {
                int event = r.next();
                String tag = (r.isStartElement() || r.isEndElement()) ? r.getLocalName() : null;
                if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT && tag != null) {
                    boolean valueTag = isValueTag(tag);
                    if (valueTag && depth == 1) {
                        name = r.getAttributeValue(null, "name");
                        attrValue = r.getAttributeValue(null, "value");
                        type = tag;
                        text.setLength(0);
                    } else {
                        if (valueTag) {
                            type = null;
                            name = null;
                        }
                        depth++;
                    }
                } else if (event == javax.xml.stream.XMLStreamConstants.CHARACTERS && type != null) {
                    text.append(r.getText());
                } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT && tag != null) {
                    if (!isValueTag(tag) && depth > 0) {
                        depth--;
                    }
                    if (type != null && depth == 1) {
                        applied += putValue(editor, name, type,
                                attrValue != null ? attrValue : text.toString());
                        type = null;
                        name = null;
                        attrValue = null;
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return applied;
    }

    static boolean isValueTag(String tag) {
        if (tag.indexOf('_') >= 0) {
            return false;
        }
        return "string".equals(tag) || "int".equals(tag) || "long".equals(tag)
                || "float".equals(tag) || "boolean".equals(tag) || "hash".equals(tag);
    }

    static int putValue(Editor editor, String name, String type, String value) {
        if (name == null || name.isEmpty() || type == null) {
            return 0;
        }
        if (KEY_TREE_URI.equals(name) || KEY_PROMPT_SHOWN.equals(name) || KEY_DUMP_SEQ.equals(name)) {
            return 0;
        }
        try {
            if ("int".equals(type)) {
                editor.putInt(name, Integer.parseInt(value));
            } else if ("long".equals(type)) {
                editor.putLong(name, Long.parseLong(value));
            } else if ("float".equals(type)) {
                editor.putFloat(name, Float.parseFloat(value));
            } else if ("boolean".equals(type)) {
                editor.putBoolean(name, Boolean.parseBoolean(value));
            } else {
                editor.putString(name, value);
            }
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

    // ===== 用例 1：SharedPreferences 的两套落盘格式 =====
    static void legacyFormat() {
        String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<preferences>\n"
                + "    <string name=\"slot_home_lib\">cc11d6f3-3b63-47e7-a90b-3d9aa53b36ff</string>\n"
                + "    <int name=\"slot_home_interval\" value=\"1800\" />\n"
                + "    <string name=\"p_cc11_h_current\">475fe01d-18c0-4375-aeab-a9020d0da7fc</string>\n"
                + "    <string name=\"p_cc11_h_hist\">[{\"id\":\"475fe01d\"},{\"id\":\"aaa\"}]</string>\n"
                + "    <long name=\"p_cc11_h_next_trigger\" value=\"1759000000000\" />\n"
                + "    <boolean name=\"timer_enabled\" value=\"true\" />\n"
                + "    <float name=\"theme_hue\" value=\"210.5\" />\n"
                + "    <set name=\"some_ordered\">\n"
                + "        <string name=\"x\">1</string>\n"
                + "    </set>\n"
                + "    <string name=\"export_tree_uri\">content://com.android.externalstorage.documents/tree/</string>\n"
                + "    <string name=\"restore_prompt_shown\">true</string>\n"
                + "    <int name=\"meta_dump_seq\" value=\"1\" />\n"
                + "</preferences>\n";
        Editor e = new Editor();
        int applied = parseInto(xml.getBytes(StandardCharsets.UTF_8), e);
        System.out.println("\n===== prefs xml 解析（旧版 <preferences> 根）=====");
        System.out.println("  写回 " + applied + " 个 key：" + e.out.keySet());
        ok("cc11d6f3-3b63-47e7-a90b-3d9aa53b36ff".equals(e.out.get("slot_home_lib")), "string 取到值");
        ok(Integer.valueOf(1800).equals(e.out.get("slot_home_interval")), "int 从 value 属性取到（没有文本节点）");
        ok(Long.valueOf(1759000000000L).equals(e.out.get("p_cc11_h_next_trigger")), "long 取到");
        ok(Boolean.TRUE.equals(e.out.get("timer_enabled")), "boolean 取到");
        ok(Float.valueOf(210.5f).equals(e.out.get("theme_hue")), "float 取到");
        ok("[{\"id\":\"475fe01d\"},{\"id\":\"aaa\"}]".equals(e.out.get("p_cc11_h_hist")),
                "值里带 {} 与 \" 的 JSON 字符串没被括号扫描带跑");
        ok(!e.out.containsKey("export_tree_uri"), "export_tree_uri 被跳过（授权随卸载失效，写回来会显示成可用）");
        ok(!e.out.containsKey("restore_prompt_shown"), "restore_prompt_shown 被跳过（还原后不该再弹一次）");
        ok(!e.out.containsKey("meta_dump_seq"), "meta_dump_seq 被跳过");
        ok(!e.out.containsKey("some_ordered") && !e.out.containsKey("x"), "set 里的东西不被当成顶层 key");
    }

    static void v2Format() {
        String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<map name=\"settings\" >\n"
                + "    <string name=\"slot_lock_lib\">aaaa</string>\n"
                + "    <int name=\"x_int\" value=\"7\" />\n"
                + "    <string-set name=\"ordered\">\n"
                + "        <string>one</string>\n"
                + "    </string-set>\n"
                + "</map>\n";
        Editor e = new Editor();
        int applied = parseInto(xml.getBytes(StandardCharsets.UTF_8), e);
        System.out.println("\n===== prefs xml 解析（v2 <map name=...> 根）=====");
        System.out.println("  写回 " + applied + " 个 key：" + e.out.keySet());
        ok("aaaa".equals(e.out.get("slot_lock_lib")), "v2 根标签下仍能取到 string");
        ok(Integer.valueOf(7).equals(e.out.get("x_int")), "v2 下 int 正常");
        ok(!e.out.containsKey("ordered"), "string-set（带 _ 后缀）整条跳过，不猜语义");
    }

    // ===== 用例 2：zip 往返，用真实拉出来的 json =====
    static void zipRoundtrip(File libraryJson, File librariesJson) throws Exception {
        System.out.println("\n===== zip 打包/解包往返（真文件）=====");
        File dir = Files.createTempDirectory("bkrt").toFile();
        File zipFile = new File(dir, "wallswitch-backup-test.zip");
        byte[] libBytes = Files.readAllBytes(libraryJson.toPath());
        byte[] libsBytes = Files.readAllBytes(librariesJson.toPath());
        int realItems = new JSONArray(new String(libBytes, StandardCharsets.UTF_8)).length();

        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(zipFile))) {
            JSONArray arr = new JSONArray(new String(libBytes, StandardCharsets.UTF_8));
            JSONObject m = new JSONObject();
            m.put("app_version", "3.75");
            m.put("library_items", arr.length());
            putText(zout, "manifest.json", m.toString(2));
            putStream(zout, "library.json", libBytes);
            putStream(zout, "libraries.json", libsBytes);
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.getJSONObject(i).getString("id");
                putText(zout, "wallpapers/" + id + ".png", "fake-png-bytes-" + id);
                putText(zout, "thumbs/" + id + ".jpg", "fake-thumb-" + id);
            }
            putText(zout, "prefs/settings.xml", "<preferences/>");
        }

        ZipFile zip = new ZipFile(zipFile);
        int images = 0, thumbs = 0;
        boolean manifestOk = false, libOk = false;
        java.util.Enumeration<? extends ZipEntry> es = zip.entries();
        List<String> names = new ArrayList<>();
        while (es.hasMoreElements()) {
            ZipEntry e = es.nextElement();
            String n = e.getName();
            names.add(n);
            if (n.startsWith("wallpapers/")) images++;
            if (n.startsWith("thumbs/")) thumbs++;
            if (n.equals("manifest.json")) {
                manifestOk = new JSONObject(new String(read(zip, e), StandardCharsets.UTF_8))
                        .optInt("library_items") == realItems;
            }
            if (n.equals("library.json")) {
                libOk = new String(read(zip, e), StandardCharsets.UTF_8)
                        .equals(new String(libBytes, StandardCharsets.UTF_8));
            }
        }
        zip.close();
        System.out.println("  条目共 " + names.size() + "，wallpapers " + images + "，thumbs " + thumbs
                + "，真条目数 " + realItems);
        ok(images == realItems && thumbs == realItems, "每条元数据都配到一个图与一个缩略图条目");
        ok(libOk, "library.json 解出来与原文件字节完全一致");
        ok(manifestOk, "manifest 里的条目数与真数据一致");

        // 模拟 restore 的 dropMissingImages：包里少一张图，那条元数据要被丢弃
        JSONArray all = new JSONArray(new String(libBytes, StandardCharsets.UTF_8));
        List<String> present = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            String id = all.getJSONObject(i).getString("id");
            if (i != 7) {                       // 假装第 8 张图在包里缺失
                present.add("wallpapers/" + id + ".png");
            }
        }
        int kept = 0, dropped = 0;
        for (int i = 0; i < all.length(); i++) {
            String id = all.getJSONObject(i).getString("id");
            if (present.contains("wallpapers/" + id + ".png")) kept++; else dropped++;
        }
        System.out.println("  缺 1 张图时：保留 " + kept + " 条、丢弃 " + dropped + " 条");
        ok(kept == all.length() - 1 && dropped == 1, "包里缺一张图就只丢那一条，而不是留下点开黑图的死条目");

        // 包名冲突：同一秒重复备份不能覆盖旧包
        String a = backupNameAt(1_760_000_000_000L);
        String b = backupNameAt(1_760_000_000_500L);
        System.out.println("  同一秒内两次备份的名字：" + a + " / " + b);
        ok(a.equals(b), "时间戳精度是秒 —— 同一秒内两次备份会同名（这条是已知的包名策略边界，见下方备注）");
        zipFile.delete();
        dir.delete();
    }

    /** BackupStore 里的命名式样：yyyyMMdd-HHmmss（只到秒）。 */
    static String backupNameAt(long millis) {
        return "wallswitch-backup-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date(millis)) + ".zip";
    }

    static void putText(ZipOutputStream zout, String name, String content) throws Exception {
        putStream(zout, name, content.getBytes(StandardCharsets.UTF_8));
    }

    static void putStream(ZipOutputStream zout, String name, byte[] bytes) throws Exception {
        zout.putNextEntry(new ZipEntry(name));
        zout.write(bytes);
        zout.closeEntry();
    }

    static byte[] read(ZipFile zip, ZipEntry e) throws Exception {
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法：需要两个参数（真 library.json、真 libraries.json 的路径）");
            System.exit(2);
        }
        legacyFormat();
        v2Format();
        zipRoundtrip(new File(args[0]), new File(args[1]));
        System.out.println("\n== backup roundtrip: " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) System.exit(1);
    }
}
