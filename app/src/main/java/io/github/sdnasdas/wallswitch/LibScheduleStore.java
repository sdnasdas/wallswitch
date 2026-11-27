package io.github.sdnasdas.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;

/**
 * 按时段自动换库（v3.191）的配置存取与时刻推算，每一面（桌面 / 锁屏）各一份。
 *
 * <p>配置是一条「时段 → 库」的列表，存在 prefs 的槽位键下（settings，随备份包一起走）：
 * {@code slot_home_lib_sched / slot_lock_lib_sched} = JSON 数组
 * {@code [{"s":450,"e":1380,"lib":"<库id>"}]}，s/e 为当天的第几分钟（含头不含尾）；
 * start &gt; end 表示跨零点的段（23:00–07:00），各时段互不重叠。
 *
 * <p>语义（v3.191 定稿）：到点只是一次性的「替用户选库」动作 —— 走
 * {@link LibraryStore#setSlotLib} 的既有链路（记日志、重排库内定时），之后用户随时可手动换，
 * 配置不锁；下个时段到点再切一次。调度器按「本时段开始边界」去重，补切不会持续覆盖手动选择。
 * 手动换库不清配置。暂停中的面不换。
 */
public class LibScheduleStore {

    private static final String PREFS_NAME = "settings";

    /** 一条「时段 → 库」规则；start/end 为当天分钟数，start > end = 跨零点。 */
    public static class Rule {
        public int start;
        public int end;
        public String libId;

        public Rule(int start, int end, String libId) {
            this.start = start;
            this.end = end;
            this.libId = libId;
        }
    }

    /** 槽位键前缀与 {@link LibraryStore#slotKey} 一致，配置跟着槽位走。 */
    private static String key(boolean forHome) {
        return LibraryStore.slotKey(forHome) + "_lib_sched";
    }

    /** 当前时刻在当天的第几分钟。 */
    public static int minutesOfDay(long epochMillis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(epochMillis);
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    static boolean overlaps(int startA, int endA, int startB, int endB) {
        if (startA == endA || startB == endB) {
            return false;
        }
        boolean crossesA = startA > endA;
        boolean crossesB = startB > endB;
        if (crossesA && crossesB) {
            return true;
        }
        if (crossesA) {
            return startB >= startA || startB < endA;
        }
        if (crossesB) {
            return startA >= startB || startA < endB;
        }
        return startA < endB && startB < endA;
    }

    /** 读取该面的时段配置；坏条目（起止相同 / 没有库）在解析时跳过。 */
    public static List<Rule> load(Context ctx, boolean forHome) {
        java.util.List<Rule> rules = new java.util.ArrayList<>();
        String raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(key(forHome), null);
        if (raw == null) {
            return rules;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int s = o.optInt("s", -1);
                int e = o.optInt("e", -1);
                String lib = o.optString("lib", "");
                if (s < 0 || e < 0 || s >= 1440 || e >= 1440 || s == e || lib.isEmpty()) {
                    continue;
                }
                rules.add(new Rule(s, e, lib));
            }
        } catch (Exception ignored) {
        }
        return rules;
    }

    /** 写回该面的时段配置（库已删的条目自动剔除；空列表 = 删键）。 */
    public static void save(Context ctx, boolean forHome, List<Rule> rules) {
        JSONArray arr = new JSONArray();
        if (rules != null) {
            for (Rule r : rules) {
                if (r.start == r.end || r.libId == null || r.libId.isEmpty()
                        || LibraryStore.get(ctx, r.libId) == null) {
                    continue;
                }
                try {
                    JSONObject o = new JSONObject();
                    o.put("s", r.start);
                    o.put("e", r.end);
                    o.put("lib", r.libId);
                    arr.put(o);
                } catch (Exception ignored) {
                }
            }
        }
        SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit();
        if (arr.length() == 0) {
            editor.remove(key(forHome));
        } else {
            editor.putString(key(forHome), arr.toString());
        }
        editor.apply();
        // 规则一变，当前时段要重新视为「尚未执行」；否则编辑当前时段后补切会被旧标记挡住。
        LibScheduler.clearApplied(ctx, forHome);
    }

    /** 该面有没有生效的时段配置。 */
    public static boolean hasRules(Context ctx, boolean forHome) {
        return !load(ctx, forHome).isEmpty();
    }

    /**
     * 此刻所属时段的目标库：命中第一条匹配的时段；
     * 跨零点段按「start &gt; end」判断；不在任何时段内返回 null。
     */
    public static String currentLibId(Context ctx, boolean forHome, int minutesOfDay) {
        Rule rule = currentRule(ctx, forHome, minutesOfDay);
        return rule == null ? null : rule.libId;
    }

    /** 当前命中的规则；供调度器同时取目标库和本时段开始边界。 */
    static Rule currentRule(Context ctx, boolean forHome, int minutesOfDay) {
        for (Rule r : load(ctx, forHome)) {
            boolean inside = r.start < r.end
                    ? minutesOfDay >= r.start && minutesOfDay < r.end
                    : minutesOfDay >= r.start || minutesOfDay < r.end;
            if (inside) {
                return r;
            }
        }
        return null;
    }

    /** 当前时段的开始边界（墙钟毫秒）；跨零点段在午夜后属于前一天开始的段。 */
    static long currentStartMillis(Context ctx, boolean forHome, long nowMillis) {
        return segmentStartMillis(currentRule(ctx, forHome, minutesOfDay(nowMillis)), nowMillis);
    }

    /** 给定规则与时刻，算该时段的开始边界；规则不匹配时返回 -1。 */
    static long segmentStartMillis(Rule rule, long nowMillis) {
        if (rule == null) {
            return -1L;
        }
        int minutes = minutesOfDay(nowMillis);
        boolean inside = rule.start < rule.end
                ? minutes >= rule.start && minutes < rule.end
                : minutes >= rule.start || minutes < rule.end;
        if (!inside) {
            return -1L;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMillis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis() + rule.start * 60_000L;
        if (rule.start > rule.end && minutes < rule.end) {
            start -= 24 * 60 * 60_000L;
        }
        return start;
    }

    /**
     * 下一个时段边界（各条规则的 start 与 end）的墙钟时刻（毫秒）。
     * 已过去的边界推到明天；没有生效配置返回 -1。
     */
    public static long nextBoundaryMillis(Context ctx, boolean forHome, long nowMillis) {
        List<Rule> rules = load(ctx, forHome);
        if (rules.isEmpty()) {
            return -1L;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMillis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long midnight = cal.getTimeInMillis();
        long best = -1L;
        for (Rule r : rules) {
            for (int minute : new int[]{r.start, r.end}) {
                long t = midnight + minute * 60_000L;
                if (t <= nowMillis) {
                    t += 24 * 60 * 60_000L;
                }
                if (best < 0 || t < best) {
                    best = t;
                }
            }
        }
        return best;
    }

    /** 删库时把引用它的时段条目从两面配置里剔除（有变更才写盘）。 */
    public static void removeLib(Context ctx, String libId) {
        if (libId == null) {
            return;
        }
        for (boolean forHome : new boolean[]{true, false}) {
            List<Rule> rules = load(ctx, forHome);
            boolean changed = rules.removeIf(r -> libId.equals(r.libId));
            if (changed) {
                save(ctx, forHome, rules);
            }
        }
    }
}
