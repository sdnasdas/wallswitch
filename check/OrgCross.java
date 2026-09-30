import org.json.*;
import java.util.*;

/**
 * 用真 org.json 交叉验证 MetaFiles 的救回结果：
 * 候选必须被 JSONArray 接受，且解出来的条目数等于期望。
 * （参考实现容忍尾逗号，Android 的实现不一定，所以算法一律退让到没有尾逗号的位置 —— 两边都合法。）
 */
public class OrgCross {
    static int pass = 0, fail = 0;

    static void check(String name, String input, int wantKept) {
        String cand = MetaSalvageTest.longestValidPrefix(input);
        int got = -1;
        String why = "null";
        if (cand != null) {
            try {
                JSONArray a = new JSONArray(cand);
                got = 0;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    if (o.has("id")) got++;
                }
                why = "len=" + a.length();
            } catch (Exception e) {
                got = -2;
                why = e.getClass().getSimpleName();
            }
        }
        boolean ok = got == wantKept;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + "  want=" + wantKept + " got=" + got + "  " + why);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) {
        String full = MetaSalvageTest.lib(5);
        int o2 = full.indexOf("{\"id\":\"id-2\"");
        int o3 = full.indexOf("{\"id\":\"id-3\"");
        check("cut inside 3rd object", full.substring(0, o2 + 12), 2);
        int thirdEnd = full.indexOf('}', o2) + 1;
        check("cut right after 3rd object", full.substring(0, thirdEnd), 3);
        // 第三条已写完、只多写了一个逗号：第三条完整，救回 3 条（丢的是后面没写完的）
        check("cut after trailing comma", full.substring(0, thirdEnd + 1), 3);
        int titleStart = full.indexOf("\"title\"", o3);
        check("cut inside string", full.substring(0, titleStart + 12), 3);

        String big = MetaSalvageTest.lib(1000);
        check("1000 items cut at 940", big.substring(0, big.indexOf("{\"id\":\"id-940\"") + 20), 940);
        // 极端：只写了 [ 就断（一条都没写完 -> 判为救不出，check 里 null 记为 -1）
        check("only [", "[", -1);
        // 括号全配对的整份内容：候选就是整份，真解析器照样收（这条路径只在内容本身不合法时走到）
        check("already complete -> whole text", full, 5);
        // 历史脏数据形态：某条缺 id（整份合法，真解析器收下 2 条，条目级跳交由调用方）
        check("complete but entry lacks id", "[{\"lib_id\":\"L1\"},{\"id\":\"x\",\"lib_id\":\"L1\"}]", 1);

        // 救回结果再写回后必须能被正常解析（模拟 writeJson -> 下次 load）
        String salv = MetaSalvageTest.longestValidPrefix(full.substring(0, o2 + 12));
        try {
            JSONArray a = new JSONArray(salv);
            String round = a.toString();
            JSONArray again = new JSONArray(round);
            boolean ok = again.length() == 2;
            System.out.println((ok ? "PASS  " : "FAIL  ") + "round-trip keeps 2  got=" + again.length());
            if (ok) pass++; else fail++;
        } catch (Exception e) {
            System.out.println("FAIL  round-trip threw " + e);
            fail++;
        }

        System.out.println("== org cross: " + pass + " passed, " + fail + " failed ==");
        if (fail > 0) System.exit(1);
    }
}
