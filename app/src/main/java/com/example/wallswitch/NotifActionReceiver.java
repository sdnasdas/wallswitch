package com.example.wallswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 常驻通知上「上一张 / 暂停·继续 / 下一张」三颗键的落点：按 intent 里带的范围（{@link #EXTRA_FOR_HOME}）
 * 对那一面的启用库执行 {@link Switcher#prev}/{@link Switcher#next} 或 {@link TimerScheduler#setPaused}，
 * 与桌面小组件、App 内卡片同一套语义与守卫。
 *
 * <p>范围为什么走 extras：桌面与锁屏各有一条常驻通知（见 StatusNotifier），三颗键的布局完全一样，
 * 只有"作用在哪一面"不同。以前这里硬编码桌面，所以锁屏那条通知的键会把桌面的图换掉。
 * 缺省值仍取桌面，兼容旧 intent（例如升级前已发出去、还没过期 PendingIntent）。
 *
 * <p>切换含大图解码与系统调用，不能放主线程：仿 WidgetProvider 用 goAsync 起后台线程，
 * 结束前必须 pending.finish()；失败时回到主线程弹 Toast 说明原因（通知按钮本身无法给出反馈）。
 * 成功后常驻通知与小组件的刷新由 Switcher.applyById / TimerScheduler 里的钩子统一完成，这里不用管。
 */
public class NotifActionReceiver extends BroadcastReceiver {

    // 常驻通知「上一张」按钮的 action
    public static final String ACTION_PREV = "com.example.wallswitch.NOTIF_PREV";
    // 常驻通知「下一张」按钮的 action
    public static final String ACTION_NEXT = "com.example.wallswitch.NOTIF_NEXT";
    // 常驻通知「暂停 / 继续」按钮的 action（中间那颗，图标随状态在 ic_pause / ic_play 间换）
    public static final String ACTION_PAUSE = "com.example.wallswitch.NOTIF_PAUSE";
    // 这颗键作用在哪一面：true = 桌面，false = 锁屏
    public static final String EXTRA_FOR_HOME = "for_home";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!ACTION_PREV.equals(action) && !ACTION_NEXT.equals(action) && !ACTION_PAUSE.equals(action)) {
            return;
        }
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        final boolean forHome = intent.getBooleanExtra(EXTRA_FOR_HOME, true);
        final boolean pause = ACTION_PAUSE.equals(action);
        final boolean prev = ACTION_PREV.equals(action);
        new Thread(() -> {
            try {
                if (pause) {
                    // 暂停/继续整面：撤任务或重新起算、日志、刷小组件与两条通知都在 setPaused 里收口
                    TimerScheduler.setPaused(app, forHome, !LibraryStore.slotPaused(app, forHome));
                    return;
                }
                // 点击时实时解析该范围的占位库：库可能已被换人/停用，通知上显示的是旧状态也没关系
                LibraryStore.Library lib = LibraryStore.slotLib(app, forHome);
                boolean attempted = false;
                boolean ok = false;
                if (lib != null) {
                    attempted = true;
                    ok = prev ? Switcher.prev(app, lib.id, forHome) : Switcher.next(app, lib.id, forHome);
                    if (ok) {
                        // 通知按钮也算一次手动切换：这一面的定时从此刻重新起算
                        TimerScheduler.restartScope(app, forHome);
                    }
                }
                if (attempted && !ok && Switcher.lastError() != null) {
                    // lastError 为 null 表示无副作用的空操作（如随机模式已无可回退的上一张），不弹提示
                    String reason = Switcher.errorText(app, Switcher.lastError());
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(app, reason, Toast.LENGTH_SHORT).show());
                }
            } catch (Exception ignored) {
            } finally {
                pending.finish();
            }
        }, "notif-action").start();
    }
}
