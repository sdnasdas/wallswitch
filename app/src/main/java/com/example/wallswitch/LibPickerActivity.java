package com.example.wallswitch;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面 控制台小组件「缩略图那格」拉起来的选库页。
 *
 * <p>为什么要有这个 Activity：小组件自己弹不出列表 —— RemoteViews 只认点击，没有下拉控件、
 * 也拿不到触摸流，能做的只有"点一下发生一件事"。所以借一个没有内容视图、只弹一个列表的
 * Activity：主题透明（见 {@code LibPickerTheme}），背景还是桌面，点一行即选定、点外面或返回即关掉，
 * 不进 App 主界面（manifest 里 excludeFromRecents + 空 taskAffinity，也不占最近任务、不把 MainActivity 顶上来）。
 *
 * <p>列表形状与 App 内选库（{@code MainActivity#showLibPickerDialog}）一致：共用行布局
 * {@code item_slot_lib}（缩略图 + 库名 + 张数 + 当前档打勾），原先那列光秃秃的库名换掉了。
 * 刻意没把行绑定抽成公共 adapter：App 内那条已真机验收，且它取图要用 MainActivity 实例里的
 * 缩略图 LruCache，共用就得连缓存一起搬出来 —— 为省几十行去动已验收的路径不值得。
 * 会漂的只有"取哪张图、副标题文案"这几行，行布局 XML 两边共用，外观漂不了。
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

    /**
     * 行内缩略图边长（像素）。行布局里那个位子是 44dp，折成物理像素约 116，144 已够铺满。
     * 用 {@link WallpaperStore#getWidgetThumb} 这一档而不是 App 内列表用的 {@code getThumb}
     * （384~768px，那是为两列大方格准备的）：本页一次要解 N 张（N=库数），档小一倍开页快一倍，
     * 而显示尺寸就这么大，看不出糊。
     */
    private static final int THUMB_PX = 144;

    /**
     * 预计算好的一行：库名、副标题、库 id（第 0 行「不切换」为 null）、缩略图。
     * 张数与图都在 {@code onCreate} 一次算完（含解码），{@code getView} 不再碰文件 ——
     * 列表回收复用时每次重绑都解一遍是白干的活（首页库列表 {@code LibAdapter} 同一做法）。
     * 也刻意不做跨次缓存：按 id 缓存会在「覆盖当前这张」后停在旧图上（id 没变、文件变了，
     * 缓存永命中），与小组件那一格同一个理由；每次打开重解，页短命、内存不攒。
     */
    private static final class Row {
        final String name;
        final String sub;
        final String libId;
        final Bitmap thumb;

        Row(String name, String sub, String libId, Bitmap thumb) {
            this.name = name;
            this.sub = sub;
            this.libId = libId;
            this.thumb = thumb;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final List<LibraryStore.Library> libs = LibraryStore.load(this);
        final String liveId = LibraryStore.slotLibId(this, true);
        // 张数与库内第一张一次算完：WallpaperStore.loadByLib 内部是「整份 library.json 读一遍再筛」，
        // 按库循环调用就是 N 个库 × 整份解析。真机样本（check/work/real/library.json）是 49 张 / 5.6KB、
        // 7 个库，眼下没感觉，库涨起来这笔是 N×M —— 一次遍历就够，没必要摊成 N 次
        List<WallpaperStore.Item> all = WallpaperStore.load(this);
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> firstId = new HashMap<>();
        for (WallpaperStore.Item item : all) {
            if (item.libId == null) {
                continue;
            }
            Integer seen = counts.get(item.libId);
            counts.put(item.libId, seen == null ? 1 : seen + 1);
            if (!firstId.containsKey(item.libId)) {
                firstId.put(item.libId, item.id);
            }
        }
        // 「不切换」占第 0 行（与 App 内选库列表同一档序），其余依次是各个库（顺序 = 首页拖拽排序）。
        // 这一档的文案用本页专用的一条：App 内那条写的是「清空本范围」，而这里只有桌面一面可选，
        // 照抄会让人以为按下去锁屏也一起停了
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(getString(R.string.widget_console_lib_none),
                getString(R.string.slot_none_sub), null, null));
        for (LibraryStore.Library lib : libs) {
            Integer count = counts.get(lib.id);
            rows.add(new Row(lib.name,
                    getString(R.string.lib_count, count == null ? 0 : count), lib.id,
                    libThumb(lib.id, firstId.get(lib.id))));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.slot_pick_lib_title)
                .setAdapter(new PickerAdapter(rows, liveId), (dialog, which) -> {
                    String newId = rows.get(which).libId;
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

    /**
     * 该库的代表图：桌面这一面在屏的那张优先，其次锁屏那面（同一张图两边都在用时最准），
     * 再次库内第一张（{@code fallbackId}，由调用方一次遍历时备好）；空库返回 null，
     * 由 {@link PickerAdapter} 画成占位图标。取档顺序与 App 内 {@code MainActivity#slotThumbId} 一致。
     */
    private Bitmap libThumb(String libId, String fallbackId) {
        String id = Switcher.getCurrent(this, libId, true);
        if (id == null) {
            id = Switcher.getCurrent(this, libId, false);
        }
        if (id == null) {
            id = fallbackId;
        }
        return id == null ? null : WallpaperStore.getWidgetThumb(this, id, THUMB_PX);
    }

    /** 与槽位现值比对（null = 「不切换」那一档）。 */
    private static boolean isSameSlot(String newId, String liveId) {
        return newId == null ? liveId == null : newId.equals(liveId);
    }

    /** 选库列表：第 0 行「不切换」，其余一行一个库，当前占位库打勾。 */
    private class PickerAdapter extends BaseAdapter {

        private final List<Row> rows;
        private final String liveId;

        PickerAdapter(List<Row> rows, String liveId) {
            this.rows = rows;
            this.liveId = liveId;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(LibPickerActivity.this)
                            .inflate(R.layout.item_slot_lib, parent, false);
            TextView name = row.findViewById(R.id.tv_pick_name);
            TextView sub = row.findViewById(R.id.tv_pick_sub);
            ImageView thumb = row.findViewById(R.id.img_pick_thumb);
            View check = row.findViewById(R.id.iv_pick_check);
            Row data = rows.get(position);
            name.setText(data.name);
            sub.setText(data.sub);
            bindThumb(thumb, data.thumb);
            // 勾 = 当前桌面槽占位库。直接用 isSameSlot：第 0 行（libId 为 null）在「槽里本来就没库」时
            // 也要打勾，照 App 内那样只比库 id 的话，空槽状态下整列会一个勾都没有
            check.setVisibility(isSameSlot(data.libId, liveId) ? View.VISIBLE : View.INVISIBLE);
            return row;
        }
    }

    /**
     * 缩略图位绑定：有图要清掉占位用的内边距与着色（不清会把真图按 SRC_IN 染成剪影），
     * 没图退回线性图标。跟 {@code MainActivity#bindThumb} 一样两条路都显式设置，
     * 回收复用时状态才不串。
     */
    private void bindThumb(ImageView view, Bitmap bmp) {
        if (bmp != null) {
            view.setPadding(0, 0, 0, 0);
            view.setImageTintList(null);
            view.setImageBitmap(bmp);
        } else {
            int pad = (int) (10 * getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad, pad, pad);
            view.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_secondary)));
            view.setImageResource(R.drawable.ic_tab_wallpaper);
        }
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
