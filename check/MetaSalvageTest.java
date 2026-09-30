import java.util.*;

/**
 * MetaFiles 前缀救回算法的独立验证：算法部分原样抄自 MetaFiles.java，
 * 校验用一个严格的 JSON 解析器（会拒尾逗号、拒未闭合字符串），行为对齐 org.json。
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
    static int pass = 0, fail = 0;

    static void selfTestParser() {
        String[] good = {"[]", "[1]", "[{}]", "[{\"a\":1}]", "[{\"a\":1},{\"b\":[1,2]}]", "[\"x\",\"y\"]",
                "[{\"a\":\"x\"}]"};
        String[] bad = {"", "  ", "1", "{}", "[{},]", "[{\"a\":1},]", "[{\"a\":", "[{]", "junk",
                "[{\"a\":\"x}", "[1,2"};
        for (String g : good) {
            boolean ok = StrictJson.isArray(g);
            System.out.println((ok ? "PASS  " : "FAIL  ") + "parser accepts " + g);
            if (ok) pass++; else fail++;
        }
        for (String b : bad) {
            boolean ok = !StrictJson.isArray(b);
            System.out.println((ok ? "PASS  " : "FAIL  ") + "parser rejects \"" + b + "\"");
            if (ok) pass++; else fail++;
        }
    }

    static void expect(String name, String input, Integer wantKept) {
        String got = longestValidPrefix(input);
        Integer gotCount = got == null ? null : countTopLevelElements(got);
        boolean ok = Objects.equals(gotCount, wantKept);
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + "   want=" + wantKept + "  got=" + gotCount);
        if (!ok && got != null) {
            System.out.println("        -> " + got);
        }
        if (ok) pass++; else fail++;
    }

    static String lib(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"id-").append(i).append("\",\"lib_id\":\"L1\",\"title\":\"壁纸 ").append(i).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    static int objStart(String full, int idx) {
        return full.indexOf("{\"id\":\"id-" + idx + "\"");
    }

    public static void main(String[] args) {
        // 0. 先自验严格解析器本身（它错了后面全是假 PASS）
        selfTestParser();

        String full = lib(5);

        // 1. 截在第 3 条对象内部 -> 保住前 2 条
        expect("cut inside 3rd object", full.substring(0, objStart(full, 2) + 12), 2);
        // 2. 截在两条之间（对象刚写完，逗号还没写）-> 保住前 3 条
        int thirdEnd = full.indexOf('}', objStart(full, 2)) + 1;
        expect("cut right after 3rd object", full.substring(0, thirdEnd), 3);
        // 3. 截在第三条之后、只多写了一个逗号 -> 第三条本身是完整的，应当保住 3 条
        expect("cut after trailing comma", full.substring(0, thirdEnd + 1), 3);
        // 3a. 截在「逗号 + 下一条对象刚起了个头」-> 仍然保住 3 条
        expect("cut at 4th object head", full.substring(0, objStart(full, 3) + 1), 3);
        // 3b. 截在第四条内部（前一条已写完 + 逗号）-> 丢掉没写完的那条，保住 3 条
        expect("cut inside 4th after comma", full.substring(0, objStart(full, 3) + 5), 3);
        // 4. 截在字符串字面量内部（标题写到一半）-> 该条不可信，保住前 3 条
        int titleStart = full.indexOf("\"title\"", objStart(full, 3));
        expect("cut inside string", full.substring(0, titleStart + 12), 3);
        // 5. 空文件（truncate 之后、首笔写之前被读到）
        expect("empty file", "", null);
        expect("whitespace only", "   \n ", null);
        // 6. 只有开括号
        expect("only [", "[", null);
        expect("[{ with nothing else", "[{", null);
        // 7. 首条就没写完
        expect("cut in first object", "{\"id\":\"a\",", null);
        // 8. 括号错配（不是截断的形态）
        expect("mismatched braces", "[}", null);
        // 9. 完整且括号全配对：候选就是整份内容（这条路径只在「整份合法但内容不成句」时才走到，
        //    正常完整文件在上一层就已经解析成功了）
        expect("already complete", full, 5);
        // 9b. 完整但某个条目缺 id（历史脏数据形态）：救回应给出整份，交由调用方逐条跳过
        String missingId = "[{\"lib_id\":\"L1\"},{\"id\":\"x\",\"lib_id\":\"L1\"}]";
        expect("complete but entry lacks id", missingId, 2);
        // 10. 真实规模：1000 条截掉尾部
        String big = lib(1000);
        int cutAt = objStart(big, 940) + 20;
        String salv = longestValidPrefix(big.substring(0, cutAt));
        int kept = salv == null ? -1 : countTopLevelElements(salv);
        System.out.println((kept == 940 ? "PASS  " : "FAIL  ") + "1000-item salvage  want=940  got=" + kept);
        if (kept == 940) pass++; else fail++;
        // 11. countTopLevelElements 对齐「原文件有几条」
        System.out.println((countTopLevelElements(full) == 5 ? "PASS  " : "FAIL  ")
                + "count full=5  got=" + countTopLevelElements(full));
        if (countTopLevelElements(full) == 5) pass++; else fail++;
        System.out.println((countTopLevelElements(big) == 1000 ? "PASS  " : "FAIL  ")
                + "count big=1000  got=" + countTopLevelElements(big));
        if (countTopLevelElements(big) == 1000) pass++; else fail++;

        System.out.println("== salvage test: " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) System.exit(1);
    }
}
