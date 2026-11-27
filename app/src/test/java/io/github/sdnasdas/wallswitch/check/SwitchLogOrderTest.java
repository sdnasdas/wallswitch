package io.github.sdnasdas.wallswitch.check;

import io.github.sdnasdas.wallswitch.SwitchLog;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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

    private static final String SAME_VISIBLE_LINE =
            "  10:21  桌面「自然」（间隔 0分 · 手动 · 下次约 11:21）";
    private static final String DUPLICATE_LINES = SAME_VISIBLE_LINE + " [w:wallpaper-a l:lib]\n"
            + SAME_VISIBLE_LINE + " [w:wallpaper-b l:lib]\n"
            + SAME_VISIBLE_LINE + " [w:wallpaper-c l:lib]\n";

    private static List<String> wallpaperIds(String content, boolean newestFirst) {
        List<String> ids = new ArrayList<>();
        for (SwitchLog.DisplayLine line : SwitchLog.parseDisplayLines(SwitchLog.reorder(content, newestFirst))) {
            if (line.ref != null) {
                ids.add(line.ref.wallpaperId);
            }
        }
        return ids;
    }

    /** 同分钟、同库、同文案连续切三张：每行仍只带自己的 id，不能合成一个三图列表。 */
    @Test
    public void identicalVisibleLinesKeepTheirOwnRefs() {
        List<SwitchLog.DisplayLine> lines = SwitchLog.parseDisplayLines(DUPLICATE_LINES);
        List<SwitchLog.DisplayLine> entries = new ArrayList<>();
        for (SwitchLog.DisplayLine line : lines) {
            if (line.ref != null) entries.add(line);
        }
        assertEquals(3, entries.size());
        assertEquals(entries.get(0).text, entries.get(1).text);
        assertEquals(entries.get(1).text, entries.get(2).text);
        assertEquals("wallpaper-a", entries.get(0).ref.wallpaperId);
        assertEquals("wallpaper-b", entries.get(1).ref.wallpaperId);
        assertEquals("wallpaper-c", entries.get(2).ref.wallpaperId);
    }

    /** 正序按写入顺序取 id；倒序时 id 跟着整行一起反转，仍与它所属那张图一一对应。 */
    @Test
    public void refsFollowPhysicalLinesInBothOrders() {
        assertEquals(List.of("wallpaper-a", "wallpaper-b", "wallpaper-c"),
                wallpaperIds(DUPLICATE_LINES, false));
        assertEquals(List.of("wallpaper-c", "wallpaper-b", "wallpaper-a"),
                wallpaperIds(DUPLICATE_LINES, true));
    }

    /** 配置行与老记录没有隐藏 id，不能借同文字记录里的 id 串过去。 */
    @Test
    public void linesWithoutRefsStayIndependent() {
        List<SwitchLog.DisplayLine> lines = SwitchLog.parseDisplayLines(
                "  10:21  —— 改为顺序切换 ——\n"
                        + "  10:21  桌面「自然」\n"
                        + SAME_VISIBLE_LINE + " [w:wallpaper-a l:lib]\n");
        assertNull(lines.get(0).ref);
        assertNull(lines.get(1).ref);
        assertEquals("wallpaper-a", lines.get(2).ref.wallpaperId);
    }
}
