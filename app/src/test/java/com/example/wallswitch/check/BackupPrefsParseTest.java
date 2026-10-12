package com.example.wallswitch.check;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * BackupStore 最容易写错的逻辑之一：prefs/settings.xml 的逐 key 解析
 * （Android 的 SharedPreferences 落盘格式，两套版本 + 全部值类型）。
 *
 * 解析部分是 BackupStore.parseInto/putValue 的副本（XmlPullParser 换成 javax 的 StAX 只为跑在
 * JVM 上，事件序列与跳过规则保持一致）。改了 BackupStore 的解析，记得同步这里。
 *
 * <p>2026-10-12 从 check/BackupRoundtripTest.java 拆出挪进 Gradle 单测（原 main + 计数器
 * 跑法 → JUnit）。同文件里的 zip 打包往返需要真机导出的 library.json/libraries.json，
 * 拆成 check/BackupZipRoundtripTest.java 留在 .cmd 路线（check/backupzip.cmd）。
 */
public class BackupPrefsParseTest {

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
            XMLStreamReader r =
                    XMLInputFactory.newInstance()
                            .createXMLStreamReader(new ByteArrayInputStream(raw));
            String name = null;
            String type = null;
            String attrValue = null;
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
                        if (valueTag) {
                            type = null;
                            name = null;
                        }
                        depth++;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && type != null) {
                    text.append(r.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT && tag != null) {
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

    // ===== 用例 1：SharedPreferences 的两套落盘格式 =====

    @Test
    public void legacyFormat() {
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
        System.out.println("===== prefs xml (legacy <preferences> root): wrote back "
                + applied + " keys: " + e.out.keySet());
        assertEquals("cc11d6f3-3b63-47e7-a90b-3d9aa53b36ff", e.out.get("slot_home_lib"));
        assertEquals(Integer.valueOf(1800), e.out.get("slot_home_interval"));
        assertEquals(Long.valueOf(1759000000000L), e.out.get("p_cc11_h_next_trigger"));
        assertEquals(Boolean.TRUE, e.out.get("timer_enabled"));
        assertEquals(Float.valueOf(210.5f), e.out.get("theme_hue"));
        assertEquals("[{\"id\":\"475fe01d\"},{\"id\":\"aaa\"}]", e.out.get("p_cc11_h_hist"));
        assertFalse("export_tree_uri must be skipped (grant dies with uninstall)",
                e.out.containsKey("export_tree_uri"));
        assertFalse("restore_prompt_shown must be skipped (no second prompt after restore)",
                e.out.containsKey("restore_prompt_shown"));
        assertFalse("meta_dump_seq must be skipped", e.out.containsKey("meta_dump_seq"));
        assertFalse("set children are not top-level keys", e.out.containsKey("some_ordered"));
        assertFalse("set children are not top-level keys", e.out.containsKey("x"));
    }

    @Test
    public void v2Format() {
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
        System.out.println("===== prefs xml (v2 <map name=...> root): wrote back "
                + applied + " keys: " + e.out.keySet());
        assertEquals("aaaa", e.out.get("slot_lock_lib"));
        assertEquals(Integer.valueOf(7), e.out.get("x_int"));
        assertTrue("string-set (underscore suffix) is skipped whole, semantics not guessed",
                !e.out.containsKey("ordered"));
    }
}
