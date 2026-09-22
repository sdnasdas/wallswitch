package com.example.wallswitch;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

/**
 * 桌面小组件：1x1 显示「已启用的桌面库」当前壁纸缩略图，点击直接切换（无确认弹窗）。
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

    /** 构建小组件视图：卡片显示「启用中的桌面库」当前壁纸缩略图、库名，并绑定点击切换。 */
    private static RemoteViews buildViews(Context ctx) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_layout);
        LibraryStore.Library home = LibraryStore.enabledLibForScope(ctx, true);
        if (home == null) {
            // 没有启用中的桌面库：显示提示，点按打开应用去配置
            views.setTextViewText(R.id.widget_label, ctx.getString(R.string.widget_no_lib));
            Intent openApp = new Intent(ctx, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, openApp,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_root, pi);
            return views;
        }
        // 点击整块卡片直接切换桌面/锁屏各自的启用库
        Intent intent = new Intent(ctx, WidgetProvider.class);
        intent.setAction(ACTION_SWITCH);
        PendingIntent switchPi = PendingIntent.getBroadcast(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, switchPi);
        // 库名标签 + 当前壁纸缩略图（缩略图缺失时保留占位背景）
        views.setTextViewText(R.id.widget_label, home.name);
        String currentId = Switcher.getCurrent(ctx, home.id, true);
        if (currentId != null) {
            Bitmap thumb = WallpaperStore.getThumb(ctx, currentId);
            if (thumb != null) {
                views.setImageViewBitmap(R.id.widget_thumb, thumb);
            }
        }
        return views;
    }
}
