package com.example.wallswitch;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 桌面 2×2 控制台「缩略图那格」拉起来的选库页。
 *
 * <p>为什么要有这个 Activity：小组件自己弹不出列表 —— RemoteViews 只认点击，没有下拉控件、
 * 也拿不到触摸流，能做的只有"点一下发生一件事"。所以借一个没有内容视图、只弹一个单选框的
 * Activity：主题透明（见 {@code LibPickerTheme}），背景还是桌面，点一行即选定、点外面或返回即关掉，
 * 不进 App 主界面（manifest 里 excludeFromRecents + 空 taskAffinity，也不占最近任务、不把 MainActivity 顶上来）。
 *
 * <p>与 App 内选库（MainActivity#confirmSlotLib）的两处有意差别：
 * <ul>
 *   <li>不弹二次确认 —— 小组件的前提就是"一次点按办一件事"；</li>
 *   <li>只改桌面范围，锁屏那面不动，因此也不去重设锁屏位图（省一次大图解码）。</li>
 * </ul>
 * 刻意保持一致的一点：换完都主动 {@code notifyWallpaperChanged()} 让桌面立刻换图（App 内那条在
 * MainActivity#applySlotLib 里）。原先两条路径都只写槽位，要等下次亮屏引擎重放才换成新库那张，
 * 刚选完看不出动，像没生效。
 */
public class LibPickerActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 「不切换」占第 0 行（与 App 内选库列表同一档序），其余依次是各个库（顺序 = 首页拖拽排序）。
        // 这一档的文案用本页专用的一条：App 内那条写的是「清空本范围」，而这里只有桌面一面可选，
        // 照抄会让人以为按下去锁屏也一起停了
        final List<LibraryStore.Library> libs = LibraryStore.load(this);
        final String liveId = LibraryStore.slotLibId(this, true);
        CharSequence[] items = new CharSequence[libs.size() + 1];
        items[0] = getString(R.string.widget_console_lib_none);
        int checked = 0;
        for (int i = 0; i < libs.size(); i++) {
            items[i + 1] = libs.get(i).name;
            if (libs.get(i).id.equals(liveId)) {
                checked = i + 1;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.slot_pick_lib_title)
                // 单选框本身就显示"当前是哪一个"，比一列光秃秃的库名好认
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    String newId = which == 0 ? null : libs.get(which - 1).id;
                    dialog.dismiss();
                    if (isSameSlot(newId, liveId)) {
                        // 点的就是当前那一档：什么都不做（dismiss 已经把页面收掉了）
                        return;
                    }
                    applyToHome(newId);
                })
                // 行点选、点外面、返回键三种收场都走这里：页面本身没有存在的价值，关掉就完
                .setOnDismissListener(dialog -> finish())
                .show();
    }

    /** 与槽位现值比对（null = 「不切换」那一档）。 */
    private static boolean isSameSlot(String newId, String liveId) {
        return newId == null ? liveId == null : newId.equals(liveId);
    }

    /**
     * 把桌面槽指向新库并当场见效。文件读写（libraries.json）+ WorkManager 往返都在 setSlotLib 里，
     * 不能放主线程；引擎标脏只是排队等一帧，代价极小。
     * 刷小组件与常驻通知不用在这里做：setSlotLib → restartScope/cancelScope 内部已经刷过。
     */
    private void applyToHome(final String newId) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean needActivate = false;
            try {
                LibraryStore.setSlotLib(app, true, newId);
                // 桌面这一面由引擎在画：槽位换了要主动标脏，否则要等下次亮屏才换成新库那张。
                // 选「不切换」（newId=null）时同样标脏，让它立刻落回"没有启用库"的纯色态。
                WallSwitchService.notifyWallpaperChanged();
                // 引擎没被系统选中时上面那声标脏没人接得住：如实说一句，别让人以为已经换上了
                needActivate = newId != null && !WallSwitchService.isActive(app);
            } catch (Exception ignored) {
            }
            if (needActivate) {
                final String text = app.getString(R.string.widget_console_no_engine);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(app, text, Toast.LENGTH_LONG).show());
            }
        }, "lib-picker").start();
    }
}
