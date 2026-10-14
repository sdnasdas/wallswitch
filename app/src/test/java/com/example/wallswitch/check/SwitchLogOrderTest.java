package com.example.wallswitch.check;

import com.example.wallswitch.SwitchLog;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 切换日志弹窗的展示顺序：倒序是「版本段 → 日期组 → 记录行」三层各自反转，
 * 段头与日期行必须仍领着它们自己的记录（不能出现记录在上、它的日期在下）。
 *
 * <p>直接调生产代码 {@link SwitchLog#reorder}（纯字符串，不碰 Context），
 * 因为弹窗渲染在本地测不了，这段分层是唯一能在 JVM 里验的一环。
 * 样本用 {@code SwitchLog} 的真实排版：段前一个空行、段头后一个空行、每个日期行前一个空行。
 */
public class SwitchLogOrderTest {

    private static final String ASC = "=== v3.67 · 桌面 · 每 60 分钟 · 随机切换 ===\n"
            + "\n"
            + "2026-09-30\n"
            + "  01:35  雪山  （间隔 16分 · 手动 · 下次约 02:35）\n"
            + "  02:38  海岸  （间隔 63分，误差 3分）\n"
            + "\n"
            + "2026-10-01\n"
            + "  08:02  溪谷  （间隔 94分，误差 4分）\n"
            + "\n"
            + "=== v3.68 · 桌面 · 每 30 分钟 · 顺序切换 ===\n"
            + "\n"
            + "2026-10-02\n"
            + "  09:00  林间  （间隔 30分，误差 0分）\n";

    private static final String DESC = "=== v3.68 · 桌面 · 每 30 分钟 · 顺序切换 ===\n"
            + "\n"
            + "2026-10-02\n"
            + "  09:00  林间  （间隔 30分，误差 0分）\n"
            + "\n"
            + "=== v3.67 · 桌面 · 每 60 分钟 · 随机切换 ===\n"
            + "\n"
            + "2026-10-01\n"
            + "  08:02  溪谷  （间隔 94分，误差 4分）\n"
            + "\n"
            + "2026-09-30\n"
            + "  02:38  海岸  （间隔 63分，误差 3分）\n"
            + "  01:35  雪山  （间隔 16分 · 手动 · 下次约 02:35）\n";

    @Test
    public void newestFirstReversesAllThreeLevels() {
        assertEquals(DESC, SwitchLog.reorder(ASC, true));
    }

    @Test
    public void chronologicalReturnsAsIs() {
        assertEquals(ASC, SwitchLog.reorder(ASC, false));
        assertEquals("", SwitchLog.reorder("", true));
    }

    /** 倒过来读，每条记录仍在自己那个日期行下面（这里逐对核顺序，不靠整串相等）。 */
    @Test
    public void dateLineStillHeadsItsOwnEntries() {
        String out = SwitchLog.reorder(ASC, true);
        int v368 = out.indexOf("=== v3.68");
        int v367 = out.indexOf("=== v3.67");
        int d1002 = out.indexOf("2026-10-02");
        int t0900 = out.indexOf("09:00");
        int d1001 = out.indexOf("2026-10-01");
        int t0802 = out.indexOf("08:02");
        int d0930 = out.indexOf("2026-09-30");
        int t0238 = out.indexOf("02:38");
        int t0135 = out.indexOf("01:35");
        assertTrue(v368 < d1002 && d1002 < t0900);
        assertTrue(v367 < d1001 && d1001 < t0802);
        assertTrue(t0900 < v367);
        assertTrue(t0802 < d0930 && d0930 < t0238 && t0238 < t0135);
    }

    /** 老格式/被手工编辑过的文件里可能一段段头都没有：记录照样按时间倒着排，不丢行。 */
    @Test
    public void headerlessEntriesStillReverse() {
        String out = SwitchLog.reorder("  08:00  甲\n  09:00  乙\n", true);
        assertEquals("  09:00  乙\n  08:00  甲\n", out);
    }

    /** 没有日期行、只有段头（同一天内连续写多条）时段头仍领着它那几条。 */
    @Test
    public void headerWithoutDateLine() {
        String out = SwitchLog.reorder("=== v3.70 · 桌面 ===\n  01:00  甲\n  02:00  乙\n", true);
        assertEquals("=== v3.70 · 桌面 ===\n  02:00  乙\n  01:00  甲\n", out);
    }
}
