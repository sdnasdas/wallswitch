package com.example.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.List;

/**
 * 「一键设置」：在抽屉里指定一张已有库里的已有壁纸，常驻通知标题那一行那颗开关按一下就把它同时钉到
 * 桌面和锁屏，并把两面的自动切换停在这一张上；再按一次退回钉住之前的那一整套状态。
 *
 * <h3>三样存储，都在 prefs {@code settings} 里，只有本类读写</h3>
 * <ul>
 *   <li>{@code pin_lib} + {@code pin_wallpaper}：钉住的是哪一张。不算槽位、不进 {@code library.json}；
 *       因为在 prefs.xml 里，备份包那条现成链路自动带上它，还原也一起回来，不新增条目。</li>
 *   <li>{@code pin_undo}：撤回要退回的那一整套快照（两面各一份），一个 JSON 字符串。
 *       <b>每一次"钉"都重写</b> —— 能走到钉这一步说明此刻不是已钉住态（是的话那颗键点下去走撤回），
 *       所以"能退"退的永远是<b>这一次钉之前</b>的状态；撤回后即清空，清 pin 也连带清空。
 *       留着"只在为空时才写"那种写法会漏一处：钉住的那张被删后指针自动推进，快照既不可达也不会被覆盖，
 *       往后每次钉住的撤回都会退到一段不相干的历史上。</li>
 * </ul>
 * 快照的<b>格式</b>只在本类出现，但槽位键名与进度键名各自的主人在
 * {@link LibraryStore#restoreSlot} / {@link Switcher#captureProgress} 那边，两处定义的事不干。
 *
 * <h3>为什么钉住非得动槽位</h3>
 * 桌面这一面不是"给张图就能设"：GL 引擎画的是「桌面槽那个库」的 {@code _current}。锁屏同理，
 * {@link Switcher#setCurrent} 开头就查 {@code ownsScope}，槽没换过去它是直接 false、什么屏都不上。
 * 所以「任意库的任意一张」必然连带把两面的槽都换到那张所在的库 —— 代价如实写在
 * {@code docs/pinned-one-tap-design.md} 第 3 节（库名那行会变、日志多四行、净新增周期唤醒 0）。
 *
 * <h3>钉还是撤，在点击时现读</h3>
 * 那颗键只有<b>一条</b> action（{@link NotifActionReceiver#ACTION_PIN}），做什么由
 * {@link #canUndo} 当场判 —— 与常驻通知那颗作用面角标同一套"显示与动作同源"的纪律
 * （见 {@link StatusNotifier#currentScope} 的理由）。判据不只看有没有快照，还看两面的当前指针
 * 是不是还都停在钉住那张：他在抽屉换了一张、或者定时/手动把图切走了，键就自动落回"钉"，可以幂等重钉。
 *
 * <p>{@link #apply} 与 {@link #undo} 含解码与系统调用（锁屏那一次要几秒），<b>必须在后台线程调用</b>；
 * 返回错误码字符串或 null（成功 / 无事可做），文案由 {@link #errorText} 现取。
 */
public class PinnedWallpaper {

    private static final String PREFS_NAME = "settings";
    private static final String KEY_LIB = "pin_lib";
    private static final String KEY_WALLPAPER = "pin_wallpaper";
    private static final String KEY_UNDO = "pin_undo";

    // 快照 JSON 里两面的键名（与 Switcher/槽位那套 h/l 后缀同一含义，只是这里想让人直接读出是哪一面）
    private static final String FACE_HOME = "home";
    private static final String FACE_LOCK = "lock";

    /** 钉住的那张（或它所在的库）已经不在库里了。 */
    public static final String ERROR_MISSING = "pin_missing";
    /** 桌面切上了、锁屏那一次静态存档写入没成 —— 不回滚，两面照样停住，只如实说一句。 */
    public static final String ERROR_LOCK_NOT_APPLIED = "lock_not_applied";
    /** 撤回时那一面本来没有占位库：槽能退回空，屏上原本那张我们手上没有副本，只能停在这张。 */
    public static final String ERROR_LOCK_LEFT_ON_SCREEN = "lock_left";

    /** 钉住的那张在哪个库；没配置返回 null。 */
    public static String libId(Context ctx) {
        String id = prefs(ctx).getString(KEY_LIB, null);
        return id == null || id.isEmpty() ? null : id;
    }

    /** 钉住的是哪一张；没配置返回 null。 */
    public static String wallpaperId(Context ctx) {
        String id = prefs(ctx).getString(KEY_WALLPAPER, null);
        return id == null || id.isEmpty() ? null : id;
    }

    /** 抽屉副标题：「库名 · 标题」；没配置或库已删返回 null（由界面画成「未设置」）。 */
    public static String label(Context ctx) {
        String libId = libId(ctx);
        String wpId = wallpaperId(ctx);
        if (libId == null || wpId == null) {
            return null;
        }
        LibraryStore.Library lib = LibraryStore.get(ctx, libId);
        if (lib == null) {
            return null;
        }
        String title = WallpaperStore.getTitle(ctx, wpId);
        return ctx.getString(R.string.pinned_selected,
                lib.name == null ? "" : lib.name,
                title == null || title.isEmpty() ? ctx.getString(R.string.untitled) : title);
    }

    /** 配好了（两张键都在）—— 通知上那颗开关画不画就看它，与"屏上是不是正钉着"是两回事。 */
    public static boolean isConfigured(Context ctx) {
        return libId(ctx) != null && wallpaperId(ctx) != null;
    }

    /** 记下要钉住的那一张。改选只动"待钉"这一对键，快照要等真正按下去钉的时候才重写。 */
    public static void set(Context ctx, String libId, String wallpaperId) {
        prefs(ctx).edit().putString(KEY_LIB, libId).putString(KEY_WALLPAPER, wallpaperId).apply();
    }

    /** 清除设定，连带丢掉撤回快照（键从此不画；屏上那张不动，想恢复轮播是按暂停键而不是清这里）。 */
    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY_LIB).remove(KEY_WALLPAPER).remove(KEY_UNDO).apply();
    }

    /**
     * 这颗键现在点下去是「撤回」还是「钉住」：有快照，且两面的槽都还指着 pin 库、
     * 两面当前那一张都还是 pin 那张。任一条不满足就落回"钉住"（幂等重钉），不做假动作。
     */
    public static boolean canUndo(Context ctx) {
        String libId = libId(ctx);
        String wpId = wallpaperId(ctx);
        if (libId == null || wpId == null) {
            return false;
        }
        if (prefs(ctx).getString(KEY_UNDO, null) == null) {
            return false;
        }
        for (boolean forHome : new boolean[]{true, false}) {
            if (!LibraryStore.ownsScope(ctx, libId, forHome)) {
                return false;
            }
            if (!wpId.equals(Switcher.getCurrent(ctx, libId, forHome))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把指定那张钉到两面，并把两面停住。前置检查任一条不过<b>不动任何状态</b>。
     * 调用方放后台线程。
     *
     * @return 错误码，null 表示成功（成功不弹提示：开关当场拨到右、封面变成这张、「下次」变「已暂停」就是反馈）
     */
    public static String apply(Context ctx) {
        final String libId = libId(ctx);
        final String wpId = wallpaperId(ctx);
        if (libId == null || wpId == null) {
            return null;
        }
        if (!inLibrary(ctx, libId, wpId)) {
            return ERROR_MISSING;
        }
        if (!TakeoverManager.isEnabled(ctx)) {
            return "takeover_off";
        }
        // 桌面这一面只有引擎在画：它没被系统选中就谁也切不动，先挡在动状态之前
        if (!WallSwitchService.isActive(ctx)) {
            return "engine_inactive";
        }
        // 快照每次钉都重写：能走到这里就说明此刻不是"已钉住"态（是的话那颗键点下去走的是撤回）。
        // 不这么办会留一处隐患 —— 「钉住 → 把那张删了 → 指针被自动推进到库里下一张 → canUndo 变 false」
        // 之后旧快照就再也无人覆盖，往后每一次钉住的撤回都会退到一段不相干的历史上。
        writeUndoSnapshot(ctx);
        // 槽位必须先换过去：setCurrent 开头查 ownsScope，没换过去它直接返回 false、什么屏都不上
        for (boolean forHome : new boolean[]{true, false}) {
            if (!LibraryStore.ownsScope(ctx, libId, forHome)) {
                LibraryStore.setSlotLib(ctx, forHome, libId);
            }
        }
        if (!Switcher.setCurrent(ctx, libId, wpId, true)) {
            String error = Switcher.lastError();
            return error == null ? "not_applied" : error;
        }
        String notice = null;
        if (!Switcher.setCurrent(ctx, libId, wpId, false)) {
            // 不回滚：桌面已经在这张上了，退回上一张反而更难解释；两面照旧停住
            notice = ERROR_LOCK_NOT_APPLIED;
        }
        TimerScheduler.setPaused(ctx, true, true);
        TimerScheduler.setPaused(ctx, false, true);
        return notice;
    }

    /**
     * 退回 {@link #apply} 之前那一整套：两面的槽（库/模式/间隔/暂停）、旧库那一面的进度四件套、
     * 屏上那张、以及定时那半截倒计时。调用方放后台线程。
     *
     * @return 需要说给用户听的错误码，null 表示全部退回（成功同样不弹：开关落回左档、封面当场变回旧那张）
     */
    public static String undo(Context ctx) {
        String raw = prefs(ctx).getString(KEY_UNDO, null);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        JSONObject undo;
        try {
            undo = new JSONObject(raw);
        } catch (Exception e) {
            undo = null;
        }
        // 快照坏成读不出东西也得把键清掉，否则那颗键永远停在"可以撤回"却什么都退不动
        if (undo == null) {
            prefs(ctx).edit().remove(KEY_UNDO).apply();
            return null;
        }
        String notice = null;
        for (boolean forHome : new boolean[]{true, false}) {
            JSONObject face = undo.optJSONObject(forHome ? FACE_HOME : FACE_LOCK);
            if (face != null) {
                String one = restoreFace(ctx, face, forHome);
                if (one != null) {
                    notice = one;
                }
            }
        }
        prefs(ctx).edit().remove(KEY_UNDO).apply();
        WidgetProvider.updateWidget(ctx);
        StatusNotifier.update(ctx);
        return notice;
    }

    /** 错误码转文案：钉住这件事独有的三条自己认，其余透给 {@link Switcher#errorText}。 */
    public static String errorText(Context ctx, String code) {
        if (ERROR_MISSING.equals(code)) {
            return ctx.getString(R.string.pinned_missing);
        }
        if (ERROR_LOCK_NOT_APPLIED.equals(code)) {
            return ctx.getString(R.string.pinned_lock_not_applied);
        }
        if (ERROR_LOCK_LEFT_ON_SCREEN.equals(code)) {
            return ctx.getString(R.string.pinned_lock_left);
        }
        return Switcher.errorText(ctx, code);
    }

    // ==================== 内部实现 ====================

    /** 这张确实还在那个库里（删过、换过库都不算），逐条比 id，不靠标题。 */
    private static boolean inLibrary(Context ctx, String libId, String wallpaperId) {
        List<WallpaperStore.Item> items = WallpaperStore.loadByLib(ctx, libId);
        for (WallpaperStore.Item item : items) {
            if (item.id.equals(wallpaperId)) {
                return true;
            }
        }
        return false;
    }

    /** 钉之前把两面现状抄一份。写坏了就等于没有撤回，不该挡住"钉住"这件正事，所以整段吞异常。 */
    private static void writeUndoSnapshot(Context ctx) {
        try {
            JSONObject undo = new JSONObject();
            undo.put(FACE_HOME, snapshotFace(ctx, true));
            undo.put(FACE_LOCK, snapshotFace(ctx, false));
            prefs(ctx).edit().putString(KEY_UNDO, undo.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject snapshotFace(Context ctx, boolean forHome) throws Exception {
        String libId = LibraryStore.slotLibId(ctx, forHome);
        JSONObject face = new JSONObject();
        face.put("lib", libId == null ? JSONObject.NULL : libId);
        face.put("mode", LibraryStore.scopeMode(ctx, forHome));
        face.put("interval", LibraryStore.scopeIntervalSeconds(ctx, forHome));
        face.put("paused", LibraryStore.slotPaused(ctx, forHome));
        face.put("lastRun", TimerScheduler.lastRun(ctx, forHome));
        face.put("trigger", TimerScheduler.scopeTrigger(ctx, forHome));
        // 空槽那一面没有"旧库的进度"可记；非空槽才抄 —— 多数情况旧库≠pin 库，这份抄着也不动它，
        // 但"在同一个库里换一张钉住"那种情形，旧指针就是撤回唯一能靠的东西
        face.put("prog", libId == null ? JSONObject.NULL : Switcher.captureProgress(ctx, libId, forHome));
        return face;
    }

    /**
     * 退回一面的全部状态并把它重新上屏。
     *
     * @return 这一面退不干净时要说一句的错误码，null 表示干净
     */
    private static String restoreFace(Context ctx, JSONObject face, boolean forHome) {
        String oldLib = nullableString(face, "lib");
        // 钉住期间他把那个库删了：当作"本来没库"处理。把指向已删库的槽写回去，
        // 常驻通知会因为"这一面没库可显示"整个消失，那是比退不干净更大的怪事
        if (oldLib != null && LibraryStore.get(ctx, oldLib) == null) {
            oldLib = null;
        }
        boolean paused = face.optBoolean("paused", false);
        long lastRun = face.optLong("lastRun", 0L);
        long trigger = face.optLong("trigger", -1L);
        LibraryStore.restoreSlot(ctx, forHome, oldLib,
                face.optString("mode", LibraryStore.MODE_ORDER),
                face.optInt("interval", 0), paused);
        if (oldLib != null && !face.isNull("prog")) {
            Switcher.restoreProgress(ctx, oldLib, forHome, face.optJSONObject("prog"));
        }
        String current = oldLib == null ? null : Switcher.getCurrent(ctx, oldLib, forHome);
        String notice = null;
        if (forHome) {
            // 桌面这一面由引擎现读「槽位库 + _current」：标脏就够了，槽退回空也正好落回没库的纯色态
            WallSwitchService.notifyWallpaperChanged();
        } else if (current != null) {
            TakeoverManager.setLockFromFile(ctx, WallpaperStore.getFullFile(ctx, current), current);
        } else {
            // 这一面本来没接管，可锁屏已经被我们盖掉了，而系统原来那张我们没留副本 —— 只能停在现状
            notice = ERROR_LOCK_LEFT_ON_SCREEN;
        }
        if (oldLib == null) {
            TimerScheduler.cancelScope(ctx, forHome);
        } else if (paused) {
            TimerScheduler.cancelScope(ctx, forHome);
        } else {
            TimerScheduler.restoreSchedule(ctx, forHome, lastRun, trigger);
        }
        return notice;
    }

    /**
     * 取一个"可能是 NULL"的字符串。不能用 {@code optString}：值为 {@link JSONObject#NULL} 时它给回
     * 字面量 {@code "null"}，那个假库 id 会一路混进槽位里。
     */
    private static String nullableString(JSONObject obj, String key) {
        if (obj.isNull(key)) {
            return null;
        }
        String value = obj.optString(key, null);
        return value == null || value.isEmpty() ? null : value;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
