/**
 * 纯 JVM 夹具：复刻 WidgetConsoleProvider#cellDpFor 的算式，验两件事
 *   ① 卡片不会比槽位大（大就是 v3.79 那种被切）；
 *   ② 地板值/上限这些夹持在什么情况下会反过来把卡片顶出槽位。
 * 不是单元测试，是"把算式单独拎出来跑一遍数字"的草稿，跑法见文件末尾。
 */
public class WidgetCellSizeTest {

    // 与 WidgetConsoleProvider 里同一套常数（改那边要改这里，这份夹具的全部意义就是让这两处对得上）
    static final int H_SPARE = 32;   // 卡片内边距 8 + 外侧留白 6 + 列间距 18
    static final int V_SPARE = 26;   // 内边距 8 + 三行上下留白 18
    static final int COLS = 2;
    static final int ROWS = 3;
    static final int CELL_MIN = 28;
    static final int CELL_MAX = 64;
    static final int CELL_FLOOR = 44;  // 读不到申报值时

    static int cellDp(int w, int h) {
        if (w <= 0 || h <= 0) {
            return CELL_FLOOR;
        }
        int cell = Math.min((w - H_SPARE) / COLS, (h - V_SPARE) / ROWS);
        return Math.max(CELL_MIN, Math.min(cell, CELL_MAX));
    }

    static void probe(String label, int w, int h) {
        int cell = cellDp(w, h);
        int cardW = H_SPARE + COLS * cell;
        int cardH = V_SPARE + ROWS * cell;
        boolean fits = cardW <= w && cardH <= h;
        // 标签一律 ASCII：本机控制台是 GBK，中文输出会糊成一团（数字才是要看的东西）
        System.out.printf("%-28s slot %3dx%-3d -> cell %2ddp card %3dx%-3d  %s%n",
                label, w, h, cell, cardW, cardH,
                fits ? "fits" : "!!! OVERFLOW by " + (cardW - w) + "x" + (cardH - h) + "dp");
    }

    public static void main(String[] args) {
        probe("no options (0x0)", 0, 0);
        probe("2 rows, v3.79 record 176x200", 176, 200);
        probe("2 rows, back-inferred 176x133", 176, 133);
        probe("2 rows, thinner 150x120", 150, 120);
        probe("2 rows, thinnest 120x100", 120, 100);
        probe("3 rows, current slot 176x300", 176, 300);
        probe("3 rows, wide 220x300", 220, 300);
        probe("tablet 400x400", 400, 400);
        System.out.println();
        System.out.println("floor 44dp draws a card " + (H_SPARE + 2 * CELL_FLOOR)
                + "x" + (V_SPARE + 3 * CELL_FLOOR) + "dp -- what a missing-options readout asks for");
    }
}

/* 跑法（本机默认 java 是 1.8，必须指到那个 JDK17；classpath 只有一条，别让 bash 拆分号）：
 *   J=/c/Users/EDY/.jdks/corretto-17.0.20.1
 *   "$J/bin/javac" -encoding UTF-8 -d /tmp/cellsize check/WidgetCellSizeTest.java \
 *     && "$J/bin/java" -cp /tmp/cellsize WidgetCellSizeTest
 */
