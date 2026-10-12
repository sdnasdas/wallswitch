package com.example.wallswitch.check;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * 用真 org.json 交叉验证 MetaFiles 的救回结果：
 * 候选必须被 JSONArray 接受，且解出来的条目数等于期望。
 * （参考实现容忍尾逗号，Android 的实现不一定，所以算法一律退让到没有尾逗号的位置 —— 两边都合法。）
 *
 * <p>2026-10-12 从 check/ 挪进 Gradle 单测（原 main + 计数器跑法 → JUnit）。
 * 复用同包的 {@link MetaSalvageTest#longestValidPrefix} / {@link MetaSalvageTest#lib}。
 */
public class OrgCross {

    /**
     * @param wantKept 期望救回的条目数；传 -1 表示期望"救不出候选"（longestValidPrefix 返回 null）
     */
    static void crossCheck(String name, String input, int wantKept) {
        String cand = MetaSalvageTest.longestValidPrefix(input);
        if (wantKept < 0) {
            assertNull(name + " -> expect no salvage candidate, got " + cand, cand);
            return;
        }
        assertNotNull(name + " -> no salvage candidate", cand);
        int got;
        try {
            JSONArray a = new JSONArray(cand);
            got = 0;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.has("id")) got++;
            }
        } catch (Exception e) {
            throw new AssertionError(name + " -> real org.json rejected candidate: " + cand, e);
        }
        assertEquals(name + " kept", wantKept, got);
    }

    @Test
    public void cutInsideThirdObject() {
        String full = MetaSalvageTest.lib(5);
        int o2 = full.indexOf("{\"id\":\"id-2\"");
        crossCheck("cut inside 3rd object", full.substring(0, o2 + 12), 2);
    }

    @Test
    public void cutRightAfterThirdObject() {
        String full = MetaSalvageTest.lib(5);
        int o2 = full.indexOf("{\"id\":\"id-2\"");
        int thirdEnd = full.indexOf('}', o2) + 1;
        crossCheck("cut right after 3rd object", full.substring(0, thirdEnd), 3);
    }

    @Test
    public void cutAfterTrailingComma() {
        String full = MetaSalvageTest.lib(5);
        int o2 = full.indexOf("{\"id\":\"id-2\"");
        int thirdEnd = full.indexOf('}', o2) + 1;
        // 第三条已写完、只多写了一个逗号：第三条完整，救回 3 条（丢的是后面没写完的）
        crossCheck("cut after trailing comma", full.substring(0, thirdEnd + 1), 3);
    }

    @Test
    public void cutInsideString() {
        String full = MetaSalvageTest.lib(5);
        int o3 = full.indexOf("{\"id\":\"id-3\"");
        int titleStart = full.indexOf("\"title\"", o3);
        crossCheck("cut inside string", full.substring(0, titleStart + 12), 3);
    }

    @Test
    public void thousandItemsCutAt940() {
        String big = MetaSalvageTest.lib(1000);
        crossCheck("1000 items cut at 940", big.substring(0, big.indexOf("{\"id\":\"id-940\"") + 20), 940);
    }

    @Test
    public void onlyOpenBracketSalvagesNothing() {
        // 极端：只写了 [ 就断（一条都没写完 -> 判为救不出）
        crossCheck("only [", "[", -1);
    }

    @Test
    public void alreadyCompleteWholeText() {
        // 括号全配对的整份内容：候选就是整份，真解析器照样收
        crossCheck("already complete -> whole text", MetaSalvageTest.lib(5), 5);
    }

    @Test
    public void completeButEntryLacksId() {
        // 历史脏数据形态：某条缺 id（整份合法，真解析器收下 2 条，条目级跳交由调用方）
        crossCheck("complete but entry lacks id", "[{\"lib_id\":\"L1\"},{\"id\":\"x\",\"lib_id\":\"L1\"}]", 1);
    }

    @Test
    public void salvagedResultReparsesCleanly() throws Exception {
        // 救回结果再写回后必须能被正常解析（模拟 writeJson -> 下次 load）
        String full = MetaSalvageTest.lib(5);
        int o2 = full.indexOf("{\"id\":\"id-2\"");
        String salv = MetaSalvageTest.longestValidPrefix(full.substring(0, o2 + 12));
        assertNotNull("salvage candidate", salv);
        JSONArray a = new JSONArray(salv);
        String round = a.toString();
        JSONArray again = new JSONArray(round);
        assertEquals("round-trip keeps 2", 2, again.length());
    }
}
