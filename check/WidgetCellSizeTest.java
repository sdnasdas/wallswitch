/**
 * 纯 JVM 夹具：复刻 WidgetConsoleProvider#cellDpFor 的算式，验两件事
 *   ① 卡片不会比槽位大（大就是 v3.79 那种被切）；
 *   ② 地板值/上限这些夹持在什么情况下会反过来把卡片顶出槽位。
 * v3.97 起两种格位共用同一段算式（横竖各一份 spec），所以这里也按两份常数各跑一遍：
 *   竖版 2 列 x 3 行、开销 32/26dp；横版 3 列 x 2 行、开销 50/20dp。
 * 不是单元测试，是"把算式单独拎出来跑一遍数字"的草稿，跑法见文件末尾。
 */
public class WidgetCellSizeTest {

    // 与 WidgetConsoleProvider 里 SPEC_CONSOLE / SPEC_CONSOLE_WIDE 同一套常数（改那边要改这里，
    // 这份夹具的全部意义就是让这两处对得上）
    static final int V_H_SPARE = 32, V_V_SPARE = 26, V_COLS = 2, V_ROWS = 3;
    static final int W_H_SPARE = 50, W_V_SPARE = 20, W_COLS = 3, W_ROWS = 2;
    static final int CELL_MIN = 28;
    static final int CELL_MAX = 84;   // v3.98 从 64 抬上来：真机截图量到槽位约 300x194dp，卡片只画了 242x148
    static final int CELL_FLOOR = 44;  // 读不到申报值时

    static int cellDp(int w, int h, int hSpare, int vSpare, int cols, int rows) {
        if (w <= 0 || h <= 0) {
            return CELL_FLOOR;
        }
        int cell = Math.min((w - hSpare) / cols, (h - vSpare) / rows);
        return Math.max(CELL_MIN, Math.min(cell, CELL_MAX));
    }

    static int failures;
    static int infos;

    /** expect: "fits" = 必须装得下（否则记一条失败）；"info" = 只报数不判失败。 */
    static void probe(String label, String expect, int w, int h,
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
        if (!fits && "fits".equals(expect)) {
            failures++;
        }
        if ("info".equals(expect)) {
            infos++;
        }
    }

    static void vertical(String label, String expect, int w, int h) {
        probe(label, expect, w, h, V_H_SPARE, V_V_SPARE, V_COLS, V_ROWS);
    }

    static void horizontal(String label, String expect, int w, int h) {
        probe(label, expect, w, h, W_H_SPARE, W_V_SPARE, W_COLS, W_ROWS);
    }

    public static void main(String[] args) {
        System.out.println("--- vertical console: 2 cols x 3 rows, spare 32/26 ---");
        vertical("no options (floor path)", "info", 0, 0);
        vertical("2 rows record, v3.79 176x200", "fits", 176, 200);
        vertical("2 rows back-inferred 176x133", "fits", 176, 133);
        vertical("2 rows, thinner 150x120", "fits", 150, 120);
        // 120x100 比我们自己的请求（120x120）还薄，真机不会给；这里只报数：28dp 地板会把
        // 三行的卡片顶成 110dp 高，比槽位高 10dp —— 就是 v3.95 注释里那条"已知让位于简单性"的取舍
        vertical("2 rows, thinnest 120x100", "info", 120, 100);
        vertical("3 rows, current slot 176x300", "fits", 176, 300);
        vertical("3 rows, wide 220x300", "fits", 220, 300);

        System.out.println();
        System.out.println("--- wide console: 3 cols x 2 rows, spare 50/20 ---");
        horizontal("no options (floor path)", "info", 0, 0);
        // 真机截图量的那块槽位（v3.98）：约 300x194dp，卡片当时只画了 242x148 —— 两向同时空 = 上限在夹
        horizontal("measured slot 300x194", "fits", 300, 194);
        horizontal("cap bites at 340x220", "fits", 340, 220);
        // 旧假设值（176/2 反推的一格 88dp -> 3 格 264dp），留着做对照：算式在两种口径下都不撑出槽位
        horizontal("old estimate 264x133", "fits", 264, 133);
        horizontal("minResize 145x90", "fits", 145, 90);
        horizontal("minResize w, thin h 145x80", "fits", 145, 80);
        horizontal("dragged max 400x180", "fits", 400, 180);
        horizontal("below minResize 120x80", "info", 120, 80);
        horizontal("tablet 400x300", "fits", 400, 300);

        System.out.println();
        System.out.println("floor 44dp: vertical card " + (V_H_SPARE + V_COLS * CELL_FLOOR) + "x"
                + (V_V_SPARE + V_ROWS * CELL_FLOOR) + "dp, wide card "
                + (W_H_SPARE + W_COLS * CELL_FLOOR) + "x" + (W_V_SPARE + W_ROWS * CELL_FLOOR)
                + "dp -- what a missing-options readout asks for");
        // 地板值那份对横版是"比请求宽 2dp"：请求 180dp，地板 44dp 画出 182dp 宽的卡。
        // 只有"桌面恰好只给请求那么宽、又不报槽位数"这种组合才会切 2dp（真机现在给约 300dp，够）
        System.out.println("note: wide floor card 182dp vs the 180dp we ask -> 2dp clip only if");
        System.out.println("      the launcher hands exactly the request AND reports no options");

        System.out.println("wide at max clamp " + CELL_MAX + "dp: card " + (W_H_SPARE + W_COLS * CELL_MAX) + "x"
                + (W_V_SPARE + W_ROWS * CELL_MAX) + "dp (needs a slot at least this big to be honest)");
        System.out.println();
        System.out.println(failures == 0
                ? ("PASSED: every probed slot holds its card; " + infos + " info-only rows")
                : ("FAILED: " + failures + " slot(s) cannot hold the card"));
        if (failures != 0) {
            System.exit(1);
        }
    }
}

/* 跑法（本机默认 java 是 1.8，必须指到那个 JDK17；classpath 只有一条，别让 bash 拆分号）：
 *   J=/c/Users/EDY/.jdks/corretto-17.0.20.1
 *   "$J/bin/javac" -encoding UTF-8 -d /tmp/cellsize check/WidgetCellSizeTest.java \
 *     && "$J/bin/java" -cp /tmp/cellsize WidgetCellSizeTest
 */
