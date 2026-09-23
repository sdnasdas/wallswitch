package com.example.wallswitch;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

/**
 * 桌面小组件：1x1 显示切换图标与「下次定时切换倒计时」，点击直接切换（无确认弹窗）。
 * 点击会切换桌面与锁屏各自的启用库（每个范围至多一个启用库）。
 */
public class WidgetProvider extends AppWidgetProvider {

    // 点击小组件触发的切换 action
    public static final String ACTION_SWITCH = "com.example.wallswitch.WIDGET_SWITCH";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // 所有小组件共用同一份视图内容，构建一次逐个渲染
        RemoteViews views = buildViews(context);
        for (int appWidgetId : appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_SWITCH.equals(action)) {
            // 切换桌面与锁屏各自的启用库（没有则跳过）
            boolean attempted = false;
            boolean ok = false;
            LibraryStore.Library home = LibraryStore.enabledLibForScope(context, true);
            if (home != null) {
                attempted = true;
                ok |= Switcher.next(context, home.id, true);
            }
            LibraryStore.Library lock = LibraryStore.enabledLibForScope(context, false);
            if (lock != null) {
                attempted = true;
                ok |= Switcher.next(context, lock.id, false);
            }
            // 全部失败时提示具体原因（此前静默失败，用户会误以为已切换）
            if (attempted && !ok) {
                Toast.makeText(context, Switcher.errorText(context, Switcher.lastError()),
                        Toast.LENGTH_LONG).show();
            }
            updateWidget(context);
            return;
        }
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
            // 桌面每 ≥30 分钟刷新一次小组件（updatePeriodMillis，只在设备活跃时送达、不唤醒设备）：
            // 借这个“设备活跃”时机补上被 Doze/ROM 冻结而漏跑的定时切换，避免必须手动打开应用才能切
            final PendingResult pending = goAsync();
            catchUpAsync(context.getApplicationContext(), pending);
        }
        super.onReceive(context, intent);
    }

    /** 后台执行补切（解码大图 + 系统调用不能放主线程），执行完结束广播。 */
    private static void catchUpAsync(final Context app, final PendingResult pending) {
        new Thread(() -> {
            try {
                TimerScheduler.catchUp(app);
            } catch (Exception ignored) {
            } finally {
                if (pending != null) {
                    pending.finish();
                }
            }
        }, "widget-catchup").start();
    }

    /** 渲染所有已放置的小组件（切换完成后由 Switcher 调用刷新缩略图）。 */
    public static void updateWidget(Context ctx) {
        AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
        ComponentName component = new ComponentName(ctx, WidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids == null || ids.length == 0) {
            return;
        }
        RemoteViews views = buildViews(ctx);
        manager.updateAppWidget(component, views);
    }

    /** 构建小组件视图：1x1 切换图标卡片 + 下次切换倒计时，点按切换（未启用桌面库时点按打开应用）。 */
    private static RemoteViews buildViews(Context ctx) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_layout);
        // 倒计时：取 WorkManager 给出的「最早可运行时间」（定时关闭或未排定则隐藏）；
        // 该时间已过但任务仍在排队（Doze/省电延后）时，倒计时会变负数，改显示「待切换」
        Long trigger = TimerScheduler.nextTrigger(ctx);
        long now = System.currentTimeMillis();
        if (trigger == null) {
            views.setViewVisibility(R.id.widget_timer, View.GONE);
            views.setViewVisibility(R.id.widget_waiting, View.GONE);
        } else if (trigger > now) {
            // Chronometer 的 base 用开机计时（elapsedRealtime），这里把墙钟时间换算过去
            long base = SystemClock.elapsedRealtime() + (trigger - now);
            views.setViewVisibility(R.id.widget_waiting, View.GONE);
            views.setViewVisibility(R.id.widget_timer, View.VISIBLE);
            views.setChronometer(R.id.widget_timer, base, null, true);
            views.setChronometerCountDown(R.id.widget_timer, true);
        } else {
            views.setViewVisibility(R.id.widget_timer, View.GONE);
            views.setViewVisibility(R.id.widget_waiting, View.VISIBLE);
        }
        LibraryStore.Library home = LibraryStore.enabledLibForScope(ctx, true);
        if (home == null) {
            // 没有启用中的桌面库：点按打开应用去配置
            Intent openApp = new Intent(ctx, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, openApp,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_root, pi);
        } else {
            // 点按图标直接切换（桌面/锁屏各自的启用库）
            Intent intent = new Intent(ctx, WidgetProvider.class);
            intent.setAction(ACTION_SWITCH);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_root, pi);
        }
        return views;
    }
}
