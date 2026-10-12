package com.example.wallswitch.check;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * 纯 JVM 夹具：复刻 WidgetConsoleProvider#cellDpFor 的算式，验两件事
 *   ① 卡片不会比槽位大（大就是 v3.79 那种被切）；
 *   ② 地板值/上限这些夹持在什么情况下会反过来把卡片顶出槽位。
 * v3.97 起两种格位共用同一段算式（横竖各一份 spec），所以这里也按两份常数各跑一遍：
 *   竖版 2 列 x 3 行、开销 32/26dp；横版 3 列 x 2 行、开销 50/20dp。
 *
 * <p>2026-10-12 从 check/ 挪进 Gradle 单测：原「main + failures 计数器」跑法改为
 * JUnit，装不下的槽位直接断言失败（fail-fast），只报数的行保持只打印。
 */
public class WidgetCellSizeTest {

    // 与 WidgetConsoleProvider 里 SPEC_CONSOLE / SPEC_CONSOLE_WIDE 同一套常数（改那边要改这里，
    // 这份夹具的全部意义就是让这两处对得上）
    static final int V_H_SPARE = 32, V_V_SPARE = 26, V_COLS = 2, V_ROWS = 3;
    static final int W_H_SPARE = 50, W_V_SPARE = 20, W_COLS = 3, W_ROWS = 2;
    static final int CELL_MIN = 28;
    static final int CELL_MAX = 84;   // v3.98 从 64 抬上来；v3.99 探针实测：3x2 槽位 224x132 -> 格 56，拉到整屏 304x428 -> 格 84（正好吃满这档）
    static final int CELL_FLOOR = 44;  // 读不到申报值时

    static int cellDp(int w, int h, int hSpare, int vSpare, int cols, int rows) {
        if (w <= 0 || h <= 0) {
            return CELL_FLOOR;
        }
        int cell = Math.min((w - hSpare) / cols, (h - vSpare) / rows);
        return Math.max(CELL_MIN, Math.min(cell, CELL_MAX));
    }

    /** mustFit: true = 卡片必须装得进槽位（否则断言失败）；false = 只报数不判失败。 */
    static void probe(String label, boolean mustFit, int w, int h,
                      int hSpare, int vSpare, int cols, int rows) {
        int cell = cellDp(w, h, hSpare, vSpare, cols, rows);
        int cardW = hSpare + cols * cell;
        int cardH = vSpare + rows * cell;
        boolean fits = cardW <= w && cardH <= h;
        // 标签一律 ASCII：本机控制台是 GBK，中文输出会糊成一团（数字才是要看的东西）
        // dW/dH = 槽位减卡片，负数就是撑出去了（两个方向都要非负才算装得下）
        System.out.printf("%-34s slot %3dx%-3d -> cell %2ddp card %3dx%-3d %2dx%2d cells  %s dW=%+d dH=%+d%n",
                label, w, h, cell, cardW, cardH, cols, rows,
                fits ? "fits    " : "OVERFLOW", w - cardW, h - cardH);
        if (mustFit) {
            assertTrue(label + " -> card " + cardW + "x" + cardH + " overflows slot " + w + "x" + h,
                    fits);
        }
    }

    static void vertical(String label, boolean mustFit, int w, int h) {
        probe(label, mustFit, w, h, V_H_SPARE, V_V_SPARE, V_COLS, V_ROWS);
    }

    static void horizontal(String label, boolean mustFit, int w, int h) {
        probe(label, mustFit, w, h, W_H_SPARE, W_V_SPARE, W_COLS, W_ROWS);
    }

    @Test
    public void verticalSlotsHoldTheirCards() {
        System.out.println("--- vertical console: 2 cols x 3 rows, spare 32/26 ---");
        vertical("2 rows record, v3.79 176x200", true, 176, 200);
        // 探针实测推出来的：3x2 槽位报回 224x132 -> 一格约 74.7 宽 / 66 高，那么要 2x2 的竖版
        // 拿到的应是 2x74.7=149 宽、2x66=132 高（高度与 3x2 同为两行，正好对得上）
        vertical("measured-implied 2x2 149x132", true, 149, 132);
        vertical("2 rows, thinner 150x120", true, 150, 120);
        vertical("3 rows, current slot 176x300", true, 176, 300);
        vertical("3 rows, wide 220x300", true, 220, 300);
    }

    @Test
    public void wideSlotsHoldTheirCards() {
        System.out.println("--- wide console: 3 cols x 2 rows, spare 50/20 ---");
        // 探针实测（v3.99 的 Toast）：默认 3x2 占位报回 min 224x132 —— 之前按截图估的 300x194 是错的
        horizontal("measured 3x2 slot 224x132", true, 224, 132);
        // 同一块拉到整屏：报回 min 304x428 -> 横向 (304-50)/3=84 正好吃满上限
        horizontal("measured stretched 304x428", true, 304, 428);
        horizontal("cap bites at 340x220", true, 340, 220);
        horizontal("minResize 145x90", true, 145, 90);
        horizontal("minResize w, thin h 145x80", true, 145, 80);
        horizontal("tablet 400x300", true, 400, 300);
    }

    @Test
    public void infoOnlyRowsJustReport() {
        // 地板值夹持的已知取舍：120x100 比我们自己的请求（120x120）还薄，真机不会给；
        // 28dp 地板会把三行的卡片顶成 110dp 高，比槽位高 10dp —— v3.95 注释里那条"已知让位于简单性"。
        vertical("no options (floor path)", false, 0, 0);
        vertical("2 rows, thinnest 120x100", false, 120, 100);
        horizontal("no options (floor path)", false, 0, 0);
        horizontal("below minResize 120x80", false, 120, 80);
        // 地板值那份对横版是"比请求宽 2dp"：请求 180dp，地板 44dp 画出 182dp 宽的卡。
        // 只有"桌面恰好只给请求那么宽、又不报槽位数"这种组合才会切 2dp（真机现在给约 300dp，够）
        System.out.println("note: wide floor card 182dp vs the 180dp we ask -> 2dp clip only if");
        System.out.println("      the launcher hands exactly the request AND reports no options");
        System.out.println("wide at max clamp " + CELL_MAX + "dp: card "
                + (W_H_SPARE + W_COLS * CELL_MAX) + "x" + (W_V_SPARE + W_ROWS * CELL_MAX)
                + "dp (needs a slot at least this big to be honest)");
    }
}
