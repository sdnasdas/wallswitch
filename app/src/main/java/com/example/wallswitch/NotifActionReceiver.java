package com.example.wallswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 常驻通知上的「上一张 / 下一张」按钮：点击发广播到这里，对当前桌面启用库
 * 执行 {@link Switcher#prev} / {@link Switcher#next}（与桌面小组件点按同一套语义与守卫）。
 *
 * 切换含大图解码与系统调用，不能放主线程：仿 WidgetProvider 用 goAsync 起后台线程，
 * 结束前必须 pending.finish()；失败时回到主线程弹 Toast 说明原因（通知按钮本身
 * 无法给出反馈）。成功后常驻通知的刷新由 Switcher.applyById 里的钩子统一完成，这里不用管。
 */
public class NotifActionReceiver extends BroadcastReceiver {

    // 常驻通知「上一张」按钮的 action
    public static final String ACTION_PREV = "com.example.wallswitch.NOTIF_PREV";
    // 常驻通知「下一张」按钮的 action
    public static final String ACTION_NEXT = "com.example.wallswitch.NOTIF_NEXT";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!ACTION_PREV.equals(action) && !ACTION_NEXT.equals(action)) {
            return;
        }
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        final boolean prev = ACTION_PREV.equals(action);
        new Thread(() -> {
            try {
                // 点击时实时解析桌面启用库：库可能已被换人/停用，通知上显示的是旧状态也没关系
                LibraryStore.Library lib = LibraryStore.enabledLibForScope(app, true);
                boolean attempted = false;
                boolean ok = false;
                if (lib != null) {
                    attempted = true;
                    ok = prev ? Switcher.prev(app, lib.id, true) : Switcher.next(app, lib.id, true);
                }
                if (attempted && !ok) {
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
