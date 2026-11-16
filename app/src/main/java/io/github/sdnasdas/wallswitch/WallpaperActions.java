package io.github.sdnasdas.wallswitch;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设为壁纸 / 删除 / 一键设置（pin）那一簇，含此刻页一键设置卡的两态与两级选图弹窗。
 * 上屏与当值核对走 Switcher/PinnedWallpaper；缩略图绑定沿用 Activity 的图片管线。
 */
final class WallpaperActions {

    private final MainActivity a;
    /** 程序化拨一键设置开关时置真，别把刷新当成用户拨动又去钉/退一遍。 */
    private boolean pinnedCardRefresh;

    WallpaperActions(MainActivity a) {
        this.a = a;
    }

    void showSetWallpaperDialog(WallpaperStore.Item item) {
        String libId = a.itemActionLibId();
        LibraryStore.Library lib = libId == null ? null : LibraryStore.get(a, libId);
        if (lib == null) {
            return;
        }
        CharSequence[] options = a.getResources().getTextArray(R.array.design_apply_targets);
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.design_set_wallpaper)
                .setItems(options, (dialog, which) -> {
                    boolean home = which != 1;
                    boolean lock = which != 0;
                    if (!a.ensureTakeoverForScope(home)) {
                        return;
                    }
                    if ((home && !LibraryStore.ownsScope(a, lib.id, true))
                            || (lock && !LibraryStore.ownsScope(a, lib.id, false))) {
                        CharSequence scope = home && lock
                                ? a.getString(R.string.design_scope_both) : options[which];
                        new AppAlertDialogBuilder(a)
                                .setTitle(R.string.design_change_library)
                                .setMessage(a.getString(R.string.design_change_library_message,
                                        scope, lib.name))
                                .setPositiveButton(R.string.confirm,
                                        (d, w) -> applyWallpaper(item, lib.id, home, lock))
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    } else {
                        applyWallpaper(item, lib.id, home, lock);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    void applyWallpaper(WallpaperStore.Item item, String libId, boolean home, boolean lock) {
        if (a.settingWallpaper) {
            return;
        }
        a.settingWallpaper = true;
        a.findViewById(R.id.btn_detail_home).setEnabled(false);
        Context app = a.getApplicationContext();
        new Thread(() -> {
            StringBuilder result = new StringBuilder();
            for (boolean forHome : new boolean[]{true, false}) {
                if (forHome ? !home : !lock) {
                    continue;
                }
                String scope = a.getString(forHome ? R.string.now_scope_home : R.string.now_scope_lock);
                if (LibraryStore.get(app, libId) == null || WallpaperStore.get(app, item.id) == null) {
                    result.append(scope).append(a.getString(R.string.design_apply_missing)).append('\n');
                    continue;
                }
                if (!LibraryStore.ownsScope(app, libId, forHome)) {
                    LibraryStore.setSlotLib(app, forHome, libId);
                }
                boolean ok = Switcher.setCurrent(app, libId, item.id, forHome);
                if (ok) {
                    TimerScheduler.restartScope(app, forHome);
                }
                result.append(scope).append("：").append(ok ? a.getString(R.string.design_apply_done)
                        : Switcher.errorText(app, Switcher.lastError())).append('\n');
            }
            a.runOnUiThread(() -> {
                a.settingWallpaper = false;
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                a.findViewById(R.id.btn_detail_home).setEnabled(true);
                Toast.makeText(a, result.toString().trim(), Toast.LENGTH_LONG).show();
                a.refreshTakeoverStatus();
                a.refreshDetail();
            });
        }, "set-wallpaper").start();
    }

    /**
     * 长按浮出的红垃圾桶。多归属之后这一按的破坏面比过去大：删的是那几库共用的那一份文件，
     * 别的库里那张也一起没，所以只在「还挂在别的库」时拦一道，把两个出口摆明给他选；
     * 只挂在本库的照旧一键直接删（v3.115 之前的手感不动）。
     *
     * <p>「解除本库关联」走的是整理态那一条移出链路（那张还挂在别的库，所以一张图都不会被删），
     * 图钉跟随与逐归属的当前指针核对都在里面，不在这里立第二份收尾。
     */
    void handleTrashClick(final WallpaperStore.Item item) {
        // 彻底删除会连成品图文件一起没，当值那张不许动（下面的「解除本库关联」不碰文件，照旧放行）
        if (a.blockWhileInUse(item)) {
            return;
        }
        final String src = a.itemActionLibId();
        if (src == null) {
            return;
        }
        StringBuilder others = new StringBuilder();
        int otherCount = 0;
        for (String libId : item.libIds) {
            if (libId.equals(src)) {
                continue;
            }
            LibraryStore.Library lib = LibraryStore.get(a, libId);
            if (lib == null) {
                // 归属指向一个已经不在了的库：不该数进「还挂在哪儿」里，那种条目还原时会兜底
                continue;
            }
            if (others.length() > 0) {
                others.append("、");
            }
            others.append("「").append(lib.name).append("」");
            otherCount++;
        }
        if (others.length() == 0) {
            deleteWallpaperEverywhere(item);
            return;
        }
        AlertDialog dialog = new AppAlertDialogBuilder(a)
                .setTitle(R.string.delete_confirm_title)
                // 个数只数点得出名字的那些：连同本库，文案里的数字与列出的库必须对得上
                .setMessage(a.getString(R.string.delete_confirm_msg,
                        others.toString(), otherCount + 1))
                .setPositiveButton(R.string.delete_purge, (d, which) -> deleteWallpaperEverywhere(item))
                .setNegativeButton(R.string.delete_unlink,
                        (d, which) -> a.applyOrganizeChange(MainActivity.ORGANIZE_REMOVE,
                                java.util.Collections.singletonList(item.id), src, null, null))
                .setNeutralButton(R.string.cancel, null)
                .show();
        // 破坏性那一条不该长成主按钮的强调色：照批量删库确认钮的压法，红字 + 红垃圾桶
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete, 0, 0, 0);
        positive.setCompoundDrawableTintList(ColorStateList.valueOf(a.getColor(R.color.danger)));
        positive.setTextColor(a.getColor(R.color.danger));
    }

    /**
     * 彻底删除一张：所有库的记录连同三份文件一起没（成品图 / 缩略图 / 原图）。
     * 它挂在几个库里，那几个库的「屏上这张」指针就得各自核对一遍 —— 一张同时占着桌面和锁屏两面时，
     * 只按主归属清会漏掉另一面。锁屏那一路是阻塞的系统调用，放后台线程、用 Application 上下文。
     */
    void deleteWallpaperEverywhere(WallpaperStore.Item item) {
        final List<String> owners = new ArrayList<>(item.libIds);
        WallpaperStore.delete(a, item.id);
        a.evictImages(item.id);
        a.refreshList();
        new Thread(() -> {
            for (String owner : owners) {
                Switcher.reapplyIfCurrent(a.getApplicationContext(), owner, item.id);
            }
        }, "reapply-wallpaper").start();
    }

    /**
     * 清除一键设置的设定：只让通知上那颗开关不再画，<b>不还原壁纸</b>；撤回快照也一并丢掉
     * （入口没了就退不回去了，所以这句要说清）。想让轮播回来是按那颗暂停键，不是清这里。
     */
    void clearPinned() {
        PinnedWallpaper.clear(a);
        a.refreshSettings();
        refreshPinnedCard();
        StatusNotifier.update(a);
        Toast.makeText(a, R.string.pinned_cleared, Toast.LENGTH_SHORT).show();
    }

    /** 两级选图的第一级：这张在哪个库里。选完直接进第二级。 */
    void showPinnedPicker() {
        final List<LibraryStore.Library> libs = LibraryStore.load(a);
        if (libs.isEmpty()) {
            Toast.makeText(a, R.string.pinned_no_lib, Toast.LENGTH_SHORT).show();
            return;
        }
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.pinned_lib_title)
                .setAdapter(new PinLibPickerAdapter(libs, PinnedWallpaper.libId(a)),
                        (dialog, which) -> showPinnedWallpaperPicker(libs.get(which).id))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /**
     * 两级选图的第二级：这个库里的哪一张。点一行就落 prefs，<b>不在这里上屏</b> ——
     * 上屏归通知上那颗开关，一次点按办一件事；选完顺手切一张等于替他做了一个他没按的动作。
     */
    void showPinnedWallpaperPicker(final String libId) {
        final List<WallpaperStore.Item> items = WallpaperStore.loadByLib(a, libId);
        if (items.isEmpty()) {
            Toast.makeText(a, R.string.pinned_lib_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        // 勾只画在「pin 正指向这个库」时的那一张上；指向别的库时整列都不打勾
        String pinnedLibId = PinnedWallpaper.libId(a);
        final String liveId = libId.equals(pinnedLibId) ? PinnedWallpaper.wallpaperId(a) : null;
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.pinned_pic_title)
                .setAdapter(new PinWallpaperAdapter(items, liveId), (dialog, which) -> {
                    PinnedWallpaper.set(a, libId, items.get(which).id);
                    a.refreshSettings();
                    refreshPinnedCard();
                    // 那颗开关要当场跟上（没配→画出来、已钉↔没钉都靠这次重发），别等下次切图才画
                    StatusNotifier.update(a);
                    Toast.makeText(a, R.string.pinned_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /** 一键设置两级列表共用的一行（现成的 {@code item_slot_lib}：缩略图 + 主标题 + 副标题 + 选中勾）。 */
    private View newPinRow(View convertView, ViewGroup parent) {
        return convertView != null ? convertView
                : LayoutInflater.from(a).inflate(R.layout.item_slot_lib, parent, false);
    }

    /** 此刻页一键设置卡的状态刷新：开关是否钉着、副标题三态（未设置 / 已配未钉 / 已钉住）。 */
    void refreshPinnedCard() {
        final Context app = a.getApplicationContext();
        new Thread(() -> {
            final boolean configured = PinnedWallpaper.isConfigured(app);
            final boolean pinned = configured && PinnedWallpaper.canUndo(app);
            final String lib = configured ? PinnedWallpaper.label(app) : null;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                pinnedCardRefresh = true;
                MaterialSwitch sw = a.findViewById(R.id.switch_pinned);
                sw.setChecked(pinned);
                sw.setEnabled(configured);
                pinnedCardRefresh = false;
                TextView tv = a.findViewById(R.id.tv_pinned);
                if (!configured || lib == null) {
                    tv.setText(R.string.pinned_unset);
                } else if (pinned) {
                    tv.setText(R.string.now_pinned_on);
                } else {
                    tv.setText(a.getString(R.string.now_pinned_ready, lib));
                }
            });
        }, "pinned-card").start();
    }

    /** 卡身点按 = 配置入口：没配直接选图；配过了给「挑一张 / 清除」，与设置页那行同款。 */
    void onPinnedCardTapped() {
        if (!PinnedWallpaper.isConfigured(a)) {
            showPinnedPicker();
            return;
        }
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.pinned_title)
                .setItems(new CharSequence[]{a.getString(R.string.pinned_pick), a.getString(R.string.pinned_clear)},
                        (d, which) -> {
                            if (which == 0) {
                                showPinnedPicker();
                            } else {
                                clearPinned();
                            }
                        })
                .show();
    }

    /** 右侧开关 = 执行键，与通知栏那颗同一件事：拨上去钉（apply），拨回去退（undo）。 */
    void onPinnedSwitchToggled(boolean on) {
        if (pinnedCardRefresh) {
            return;
        }
        if (!PinnedWallpaper.isConfigured(a)) {
            showPinnedPicker();
            refreshPinnedCard();
            return;
        }
        if (on == PinnedWallpaper.canUndo(a)) {
            refreshPinnedCard();
            return;
        }
        // 钉/退都会写锁屏静态存档，是阻塞系统调用；与通知那颗一样放后台跑，落定后再刷两态
        final Context app = a.getApplicationContext();
        new Thread(() -> {
            String error = on ? PinnedWallpaper.apply(app) : PinnedWallpaper.undo(app);
            if (error == null) {
                StatusNotifier.update(app);
            }
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                if (error != null) {
                    Toast.makeText(a, PinnedWallpaper.errorText(a, error), Toast.LENGTH_LONG).show();
                }
                refreshPinnedCard();
            });
        }, "pinned-card").start();
    }

    /**
     * 第一级：一行一个库，pin 现在指向的那个打勾。
     * 张数在构造时一次遍历算完（{@code loadByLib} 内部是「整份 library.json 读一遍再筛」，
     * 按行调用就是库数 × 整份解析），与 {@code LibPickerActivity} 同一笔账。
     */
    private class PinLibPickerAdapter extends BaseAdapter {

        private final List<LibraryStore.Library> libs;
        private final String liveLibId;
        private final Map<String, Integer> counts = new HashMap<>();

        PinLibPickerAdapter(List<LibraryStore.Library> libs, String liveLibId) {
            this.libs = libs;
            this.liveLibId = liveLibId;
            counts.putAll(WallpaperStore.libMemberCounts(a));
        }

        @Override
        public int getCount() {
            return libs.size();
        }

        @Override
        public Object getItem(int position) {
            return libs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View row = newPinRow(convertView, parent);
            LibraryStore.Library lib = libs.get(position);
            Integer count = counts.get(lib.id);
            ((TextView) row.findViewById(R.id.tv_pick_name)).setText(lib.name);
            ((TextView) row.findViewById(R.id.tv_pick_sub))
                    .setText(a.getString(R.string.lib_count, count == null ? 0 : count));
            // 代表图沿用库卡与选库弹窗那套（这一面在屏的那张优先、其次另一面、再次库内第一张），
            // 不再立第三种取法
            a.bindThumb((ImageView) row.findViewById(R.id.img_pick_thumb),
                    a.libCoverId(lib), R.drawable.ic_tab_wallpaper, 10);
            row.findViewById(R.id.iv_pick_check).setVisibility(
                    lib.id.equals(liveLibId) ? View.VISIBLE : View.INVISIBLE);
            return row;
        }
    }

    /**
     * 第二级：这一库里的每一张。副标题是<b>原图</b>尺寸（{@code library.json} 里现成的字段，
     * 不必再读一次文件头）；没记过参数的留空。
     * 缩略图<b>不预解全部</b> —— 一个库可能上千张，开弹窗时一次解完等于自己把自己卡住；
     * 沿用首页网格那套 LruCache 现解（同一个缓存，多半滚动时已经热了）。
     */
    private class PinWallpaperAdapter extends BaseAdapter {

        private final List<WallpaperStore.Item> items;
        private final String liveId;

        PinWallpaperAdapter(List<WallpaperStore.Item> items, String liveId) {
            this.items = items;
            this.liveId = liveId;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View row = newPinRow(convertView, parent);
            WallpaperStore.Item item = items.get(position);
            // 标题已下线：这行只剩缩略图 + 原图尺寸，名字那格藏掉
            row.findViewById(R.id.tv_pick_name).setVisibility(View.GONE);
            ((TextView) row.findViewById(R.id.tv_pick_sub)).setText(item.srcWidth > 0 && item.srcHeight > 0
                    ? a.getString(R.string.pinned_pic_sub, item.srcWidth, item.srcHeight)
                    : "");
            a.bindThumb((ImageView) row.findViewById(R.id.img_pick_thumb),
                    item.id, R.drawable.ic_tab_wallpaper, 10);
            row.findViewById(R.id.iv_pick_check).setVisibility(
                    item.id.equals(liveId) ? View.VISIBLE : View.INVISIBLE);
            return row;
        }
    }
}