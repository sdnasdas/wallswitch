package com.example.wallswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 常驻通知上四颗键的落点：「上一张 / 暂停·继续 / 下一张」按 {@link StatusNotifier#currentScope}
 * 当时指的那一面执行 {@link Switcher#prev}/{@link Switcher#next} 或 {@link TimerScheduler#setPaused}，
 * 「桌面/锁屏」那颗角标执行 {@link StatusNotifier#toggleScope} 并立刻重画通知。
 * 语义与守卫跟桌面小组件、App 内卡片保持一致。
 *
 * <p>范围为什么在点击时现读、不从 intent 里带：只剩一条通知，"卡片显示哪一面"和"键打在哪一面"必须同源，
 * 否则升级前发出去、还没过期的 PendingIntent 会带着旧 extras 把键打到别的面去（v3.80 那两条通知靠
 * extras 分目标，正是为了让两条互不串台；现在只有一条，读一个来源更简单）。
 * 现读多两次 SharedPreferences 取库，代价在后台线程上可以忽略。
 *
 * <p>「切换中」的账：干活前挂一个 400ms 的一次性延迟（进程内 Handler，见 {@link #BUSY_DELAY_MS}），到点还没跑完
 * 才把通知画成转圈态；finally 里一定 removeCallbacks，并且只在真画过 loading 时补一次
 * {@link StatusNotifier#update} 落回正常态——成功路径 Switcher 自己会刷、失败路径不会，这一步堵死"转个不停"。
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
    // 常驻通知「桌面 / 锁屏」角标的 action：只翻作用面，不切图
    public static final String ACTION_SCOPE = "com.example.wallswitch.NOTIF_SCOPE";
    // 动作跑过这么久还没完，才把通知切成「切换中…」。桌面那面 200ms 内就完事，门槛挡住的是无谓的闪一下；
    // 锁屏那面走 setBitmap 全图解码，几秒才落回正常态，一定越过这个门槛
    private static final long BUSY_DELAY_MS = 400;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!ACTION_PREV.equals(action) && !ACTION_NEXT.equals(action)
                && !ACTION_PAUSE.equals(action) && !ACTION_SCOPE.equals(action)) {
            return;
        }
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        final boolean scope = ACTION_SCOPE.equals(action);
        final boolean pause = ACTION_PAUSE.equals(action);
        final boolean prev = ACTION_PREV.equals(action);
        new Thread(() -> {
            // 慢动作才配 loading：400ms 内跑完（桌面那面通常 200ms）就一次都不画，免得闪一下。
            // 用的是进程内 Handler，不新增 WorkManager 任务/唤醒锁，跑完立刻 removeCallbacks
            final Handler main = new Handler(Looper.getMainLooper());
            final AtomicBoolean done = new AtomicBoolean(false);
            final AtomicBoolean shown = new AtomicBoolean(false);
            final Runnable busy = () -> {
                if (done.get()) {
                    return;
                }
                shown.set(true);
                StatusNotifier.showBusy(app);
            };
            main.postDelayed(busy, BUSY_DELAY_MS);
            try {
                if (scope) {
                    // 另一面没设库时 toggleScope 自己就不动，通知也就原样重发一遍
                    StatusNotifier.toggleScope(app);
                    StatusNotifier.update(app);
                    return;
                }
                final boolean forHome = StatusNotifier.currentScope(app);
                if (pause) {
                    // 暂停/继续整面：撤任务或重新起算、日志、刷小组件与通知都在 setPaused 里收口
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
                done.set(true);
                main.removeCallbacks(busy);
                if (shown.get()) {
                    // 真画过 loading 才补这一次收尾：成功路径 Switcher 自己会刷，失败路径不会，
                    // 而"转个不停"必须堵死。没画过就一次都不多刷，省掉一次封面解码
                    StatusNotifier.update(app);
                }
                pending.finish();
            }
        }, "notif-action").start();
    }
}
