package com.example.wallswitch;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/**
 * 控制台小组件（横版）：内部六格 = 3 列 × 2 行、格位向桌面要 3×2，内容与其他那一版完全同一套 ——
 * 第一行 = 选库 / 当前范围缩略图（点按打开 App）/ 范围翻面（桌面 ⇄ 锁屏），
 * 第二行 = 上一张 / 暂停·继续 / 下一张。除翻面外五格都作用在当前范围那一面。
 *
 * <h3>为什么这个类只是个壳</h3>
 * 桌面列表里要多出一条，就必须多一个 component（一个 {@link AppWidgetProvider} 类对应 manifest 里
 * 一个 receiver 加一份 widget_info）。但渲染、点按动作、范围态、选库入口全收在
 * {@link WidgetConsoleProvider}：两份布局的 view id 逐个对齐，那边一份 {@code buildViews}
 * 按 spec（布局资源 + 列数/行数/两向开销）喂两版。这里只把"我的实例要重画"转成一次
 * {@link WidgetConsoleProvider#scheduleUpdate}。
 *
 * <h3>为什么不再配一条队列、一套闸门</h3>
 * 那边每跑一次 {@code render} 就把<b>两份</b>视图都递交，所以共用一个 {@code QUEUED} 闸门不会让
 * 横版停在旧画面。真给两类各配一条队列一个闸门，代价是：两个都摆上桌面上时并行解码；
 * 更要紧的是动作广播的 Intent 写死了目标类，跟着分家就会长出第二套切换语义。
 * 所以这里<b>不覆写 onReceive</b>：按横版的格子 → 广播仍送到 {@link WidgetConsoleProvider} →
 * 改的是同一份范围态与槽位库 → 排一次渲染 → 两版一起更新。
 *
 * <h3>发热账</h3>
 * 与竖版同一个触发点，只多递一份 RemoteViews；这个组件没摆上桌面时 {@code getAppWidgetIds}
 * 返回空，那一版连缩略图都不解。横版在 widget_info 里开了 {@code resizeMode}（竖版仍 none），
 * 拖动改尺寸每次会走一次 {@link #onAppWidgetOptionsChanged}，拖动过程中的连发由同一个闸门并掉，
 * 落定才是那一次真正的解码。
 */
public class WidgetConsoleWideProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // 不在这里自己建视图：收口那边一次把横竖两份都刷掉
        WidgetConsoleProvider.scheduleUpdate(context);
    }

    /** 放置、拖动改尺寸、换桌面、改网格密度、旋转都会走这里；本类不参与补切，所以只需排一次渲染。 */
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        WidgetConsoleProvider.scheduleUpdate(context);
    }
}
