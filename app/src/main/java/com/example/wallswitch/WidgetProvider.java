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
            LibraryStore.Library home = LibraryStore.enabledLibForScope(context, true);
            if (home != null) {
                Switcher.next(context, home.id, true);
            }
            LibraryStore.Library lock = LibraryStore.enabledLibForScope(context, false);
            if (lock != null) {
                Switcher.next(context, lock.id, false);
            }
            updateWidget(context);
        } else {
            super.onReceive(context, intent);
        }
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
        // 倒计时：显示最近的下次定时切换时间（定时关闭或未排定则隐藏）
        Long trigger = TimerScheduler.nextTrigger(ctx);
        if (trigger != null) {
            // Chronometer 的 base 用开机计时（elapsedRealtime），这里把墙钟时间换算过去
            long base = SystemClock.elapsedRealtime() + (trigger - System.currentTimeMillis());
            views.setViewVisibility(R.id.widget_timer, View.VISIBLE);
            views.setChronometer(R.id.widget_timer, base, null, true);
            views.setChronometerCountDown(R.id.widget_timer, true);
        } else {
            views.setViewVisibility(R.id.widget_timer, View.GONE);
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
