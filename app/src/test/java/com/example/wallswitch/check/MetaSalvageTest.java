package com.example.wallswitch.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * MetaFiles 前缀救回算法的独立验证：算法部分原样抄自 MetaFiles.java，
 * 校验用一个严格的 JSON 解析器（会拒尾逗号、拒未闭合字符串），行为对齐 org.json。
 *
 * <p>2026-10-12 从 check/ 挪进 Gradle 单测：原「main + pass/fail 计数器」跑法改为
 * JUnit（gradle testDebugUnitTest），断言粒度从"整份报告"细化到单条用例。
 * 改了 MetaFiles 的救回算法，记得同步这里。OrgCross 复用本类的 longestValidPrefix/lib。
 */
public class MetaSalvageTest {

    static final int MAX_NESTING = 32;
    static final int MAX_NOTICE_CHARS = 256 * 1024;
    static final int MAX_SALVAGE_TRIES = 8;

    // ===== 与 MetaFiles 同构的实现 =====
    static String longestValidPrefix(String text) {
        List<int[]> closes = completedElementCloses(text);
        if (closes == null) {
            return null;
        }
        for (int i = closes.size() - 1, tries = 0; i >= 0 && tries < MAX_SALVAGE_TRIES; i--, tries++) {
            int end = closes.get(i)[0];
            String suffix = closes.get(i)[1] == 0 ? "" : "]";
            String candidate = text.substring(0, end + 1) + suffix;
            if (candidate.length() > MAX_NOTICE_CHARS) {
                continue;
            }
            if (StrictJson.isArray(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static List<int[]> completedElementCloses(String text) {
        List<int[]> closes = new ArrayList<>();
        char[] stack = new char[MAX_NESTING + 1];
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = skipString(text, i);
                if (end < 0) {
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

    static int skipString(String text, int quoteIndex) {
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

    static int countTopLevelElements(String text) {
        int count = 0, depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = skipString(text, i);
                if (end < 0) {
                    break;
                }
                if (depth == 1) count++;
                i = end;
                continue;
            }
            if (c == '[' || c == '{') {
                if (depth == 1) count++;
                if (depth < MAX_NESTING) depth++;
                continue;
            }
            if (c == ']' || c == '}') {
                if (depth > 0) depth--;
            }
        }
        return count;
    }

    // ===== 严格 JSON：必须是数组，元素之间必须有逗号，不许尾逗号 =====
    static class StrictJson {
        String s;
        int p;

        static boolean isArray(String text) {
            try {
                StrictJson j = new StrictJson();
                j.s = text;
                j.ws();
                if (j.p >= j.s.length() || j.s.charAt(j.p) != '[') {
                    return false;
                }
                j.arr();
                j.ws();
                return j.p == j.s.length();
            } catch (RuntimeException e) {
                return false;
            }
        }

        void value() {
            ws();
            if (p >= s.length()) throw bad("eof");
            char c = s.charAt(p);
            if (c == '[') { arr(); return; }
            if (c == '{') { obj(); return; }
            if (c == '"') { str(); return; }
            if (c == 't' || c == 'f') { bool(); return; }
            if (c == 'n') { nul(); return; }
            num();
        }

        void arr() {
            p++; // [
            ws();
            if (p < s.length() && s.charAt(p) == ']') { p++; return; }
            boolean trailingComma = false;
            while (true) {
                ws();
                // 值的位置直接出现 ] 说明刚吃过一个逗号 -> 尾逗号，拒
                if (p < s.length() && s.charAt(p) == ']') {
                    if (trailingComma) throw bad("trailing comma in array");
                    p++;
                    return;
                }
                value();
                trailingComma = false;
                ws();
                if (p < s.length() && s.charAt(p) == ',') { p++; trailingComma = true; continue; }
                if (p < s.length() && s.charAt(p) == ']') { p++; return; }
                throw bad("arr");
            }
        }

        void obj() {
            p++; // {
            ws();
            if (p < s.length() && s.charAt(p) == '}') { p++; return; }
            boolean trailingComma = false;
            while (true) {
                ws();
                if (p < s.length() && s.charAt(p) == '}') {
                    if (trailingComma) throw bad("trailing comma in object");
                    p++;
                    return;
                }
                if (p >= s.length() || s.charAt(p) != '"') throw bad("key");
                str();
                ws();
                if (p >= s.length() || s.charAt(p) != ':') throw bad("colon");
                p++;
                value();
                trailingComma = false;
                ws();
                if (p < s.length() && s.charAt(p) == ',') { p++; trailingComma = true; continue; }
                if (p < s.length() && s.charAt(p) == '}') { p++; return; }
                throw bad("obj");
            }
        }

        void str() {
            p++; // opening quote
            while (p < s.length()) {
                char c = s.charAt(p++);
                if (c == '"') return;
                if (c == '\\') {
                    if (p >= s.length()) throw bad("esc");
                    p++;
                }
            }
            throw bad("unterminated string");
        }

        void num() {
            int start = p;
            while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
            if (p == start) throw bad("num");
        }

        void bool() {
            if (s.startsWith("true", p)) p += 4;
            else if (s.startsWith("false", p)) p += 5;
            else throw bad("bool");
        }

        void nul() {
            if (s.startsWith("null", p)) p += 4;
            else throw bad("null");
        }

        void ws() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        }

        RuntimeException bad(String m) {
            return new IllegalStateException(m + " at " + p);
        }
    }

    // ===== 用例 =====

    /** 先自验严格解析器本身（它错了后面全是假 PASS）。 */
    @Test
    public void strictParserAcceptsAndRejects() {
        String[] good = {"[]", "[1]", "[{}]", "[{\"a\":1}]", "[{\"a\":1},{\"b\":[1,2]}]", "[\"x\",\"y\"]",
                "[{\"a\":\"x\"}]"};
        String[] bad = {"", "  ", "1", "{}", "[{},]", "[{\"a\":1},]", "[{\"a\":", "[{]", "junk",
                "[{\"a\":\"x}", "[1,2"};
        for (String g : good) {
            assertTrue("parser must accept " + g, StrictJson.isArray(g));
        }
        for (String b : bad) {
            assertFalse("parser must reject \"" + b + "\"", StrictJson.isArray(b));
        }
    }

    private static String full5() {
        return lib(5);
    }

    static void expect(String name, String input, Integer wantKept) {
        String got = longestValidPrefix(input);
        Integer gotCount = got == null ? null : countTopLevelElements(got);
        assertEquals(name + "  want=" + wantKept + "  got=" + gotCount, wantKept, gotCount);
    }

    static String lib(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"id-").append(i).append("\",\"lib_id\":\"L1\",\"title\":\"t-").append(i).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    static int objStart(String full, int idx) {
        return full.indexOf("{\"id\":\"id-" + idx + "\"");
    }

    @Test
    public void cutInsideThirdObjectKeepsTwo() {
        String full = full5();
        expect("cut inside 3rd object", full.substring(0, objStart(full, 2) + 12), 2);
    }

    @Test
    public void cutRightAfterThirdObjectKeepsThree() {
        String full = full5();
        int thirdEnd = full.indexOf('}', objStart(full, 2)) + 1;
        expect("cut right after 3rd object", full.substring(0, thirdEnd), 3);
    }

    @Test
    public void cutAfterTrailingCommaKeepsThree() {
        String full = full5();
        int thirdEnd = full.indexOf('}', objStart(full, 2)) + 1;
        expect("cut after trailing comma", full.substring(0, thirdEnd + 1), 3);
    }

    @Test
    public void cutAtFourthObjectHeadKeepsThree() {
        String full = full5();
        expect("cut at 4th object head", full.substring(0, objStart(full, 3) + 1), 3);
    }

    @Test
    public void cutInsideFourthAfterCommaKeepsThree() {
        String full = full5();
        expect("cut inside 4th after comma", full.substring(0, objStart(full, 3) + 5), 3);
    }

    @Test
    public void cutInsideStringKeepsThree() {
        String full = full5();
        int titleStart = full.indexOf("\"title\"", objStart(full, 3));
        expect("cut inside string", full.substring(0, titleStart + 12), 3);
    }

    @Test
    public void emptyOrWhitespaceOnlySalvagesNothing() {
        expect("empty file", "", null);
        expect("whitespace only", "   \n ", null);
    }

    @Test
    public void onlyOpenBracketsSalvageNothing() {
        expect("only [", "[", null);
        expect("[{ with nothing else", "[{", null);
    }

    @Test
    public void unfinishedFirstObjectSalvagesNothing() {
        expect("cut in first object", "{\"id\":\"a\",", null);
    }

    @Test
    public void mismatchedBracesSalvageNothing() {
        expect("mismatched braces", "[}", null);
    }

    @Test
    public void alreadyCompleteKeepsAll() {
        expect("already complete", full5(), 5);
    }

    @Test
    public void completeButEntryLacksIdStillSalvagesWhole() {
        String missingId = "[{\"lib_id\":\"L1\"},{\"id\":\"x\",\"lib_id\":\"L1\"}]";
        expect("complete but entry lacks id", missingId, 2);
    }

    @Test
    public void realScaleThousandItemsCutAt940() {
        String big = lib(1000);
        int cutAt = objStart(big, 940) + 20;
        String salv = longestValidPrefix(big.substring(0, cutAt));
        assertTrue("1000-item salvage must produce a candidate", salv != null);
        assertEquals("1000-item salvage kept", 940, countTopLevelElements(salv));
    }

    @Test
    public void countTopLevelElementsMatchesOriginalFile() {
        String full = full5();
        assertEquals("count full=5", 5, countTopLevelElements(full));
        assertEquals("count big=1000", 1000, countTopLevelElements(lib(1000)));
    }
}
