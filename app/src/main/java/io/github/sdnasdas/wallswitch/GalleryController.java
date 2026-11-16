package io.github.sdnasdas.wallswitch;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图库页那一簇：库卡片两列网格（LibAdapter）、普通/多选/排序三态、拖拽排序、批量删库、新建库。
 * 导航壳经 {@link #inMode()}/{@link #prepareVisible()}/{@link #refreshBadges()} 与它打交道，
 * 不再直接摸 adapter。图片快照/封面取法沿用 Activity 的图片管线。
 */
final class GalleryController {

    private final MainActivity a;
    private com.google.android.material.appbar.MaterialToolbar galleryToolbar;
    private RecyclerView galleryRecycler;
    private LibAdapter libAdapter;
    private ItemTouchHelper libDragHelper;

    GalleryController(MainActivity a) {
        this.a = a;
    }

    boolean isInitialized() {
        return libAdapter != null;
    }

    /** 图库栏是否停在多选/排序态（导航壳据此收起底部三栏、退出动作模式）。 */
    boolean inMode() {
        return libAdapter != null && libAdapter.mode != LibAdapter.NORMAL;
    }

    boolean prepareVisible() {
        return libAdapter.prepareVisible();
    }

    void refreshBadges() {
        libAdapter.refreshBadges();
    }

    void setBottomInset(int inset) {
        int bottom = a.dp(16) + inset;
        if (galleryRecycler.getPaddingBottom() != bottom) {
            galleryRecycler.setPadding(galleryRecycler.getPaddingLeft(),
                    galleryRecycler.getPaddingTop(), galleryRecycler.getPaddingRight(), bottom);
        }
    }

    void setupGalleryPage() {
        galleryToolbar = a.findViewById(R.id.toolbar_gallery);
        galleryRecycler = a.findViewById(R.id.recycler_gallery);
        libAdapter = new LibAdapter();
        galleryRecycler.setLayoutManager(new GridLayoutManager(a, 2));
        galleryRecycler.setAdapter(libAdapter);
        galleryToolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_new_lib) {
                showNewLibDialog();
            } else if (id == R.id.action_reorder) {
                setGalleryMode(LibAdapter.REORDER);
            } else if (id == R.id.action_select_libs) {
                setGalleryMode(LibAdapter.SELECT);
            } else if (id == R.id.action_delete_libs) {
                confirmDeleteSelectedLibs();
            } else if (id == R.id.action_reorder_done) {
                exitGalleryMode();
            }
            return true;
        });
        libDragHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.START | ItemTouchHelper.END, 0) {
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                return libAdapter.mode == LibAdapter.REORDER
                        && libAdapter.move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder holder) {
                super.clearView(rv, holder);
                if (libAdapter.mode == LibAdapter.REORDER) {
                    LibraryStore.reorder(a, libAdapter.currentIds());
                }
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
            }
        });
        libDragHelper.attachToRecyclerView(galleryRecycler);
        updateGalleryToolbar();
    }

    /** 刷新库卡片（张数、封面、桌面/锁屏标签）。 */
    void refreshLibs() {
        libAdapter.setItems(LibraryStore.load(a));
    }

    void setGalleryMode(int mode) {
        libAdapter.setMode(mode);
        a.updateBottomNav();
        updateGalleryToolbar();
    }

    void exitGalleryMode() {
        if (libAdapter.mode == LibAdapter.NORMAL) {
            return;
        }
        setGalleryMode(LibAdapter.NORMAL);
    }

    /** 顶栏随三态换：普通 = 新建 + ⋯；多选 = ✕ + 已选 N 个 + 删除；排序 = ✕ + 提示 + ✓。 */
    private void updateGalleryToolbar() {
        Menu menu = galleryToolbar.getMenu();
        int mode = libAdapter.mode;
        menu.setGroupVisible(R.id.group_gallery_normal, mode == LibAdapter.NORMAL);
        menu.setGroupVisible(R.id.group_gallery_select, mode == LibAdapter.SELECT);
        menu.setGroupVisible(R.id.group_gallery_reorder, mode == LibAdapter.REORDER);
        a.findViewById(R.id.tv_gallery_hint).setVisibility(mode == LibAdapter.REORDER ? View.VISIBLE : View.GONE);
        if (mode == LibAdapter.NORMAL) {
            galleryToolbar.setNavigationIcon(null);
            galleryToolbar.setTitle(R.string.tab_gallery);
        } else {
            galleryToolbar.setNavigationIcon(R.drawable.ic_close);
            galleryToolbar.setNavigationOnClickListener(v -> exitGalleryMode());
            galleryToolbar.setTitle(mode == LibAdapter.SELECT
                    ? a.getString(R.string.gallery_select_count, libAdapter.selectedIds.size())
                    : a.getString(R.string.gallery_reorder));
        }
    }

    /** 多选删库：先弹二次确认（库与库里的壁纸一并删除），确认后逐库删除。没勾选时只提示。 */
    private void confirmDeleteSelectedLibs() {
        final List<LibraryStore.Library> selected = libAdapter.selectedLibs();
        if (selected.isEmpty()) {
            Toast.makeText(a, R.string.lib_delete_none, Toast.LENGTH_SHORT).show();
            return;
        }
        confirmDeleteLibs(selected);
    }

    void confirmDeleteLibs(final List<LibraryStore.Library> libs) {
        AlertDialog dialog = new AppAlertDialogBuilder(a)
                .setTitle(a.getString(R.string.lib_delete_batch_title, libs.size()))
                .setMessage(R.string.lib_delete_batch_msg)
                .setPositiveButton(R.string.delete, (d, which) -> deleteLibs(libs))
                .setNegativeButton(R.string.cancel, null)
                .show();
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setTextColor(MaterialColors.getColor(positive, androidx.appcompat.R.attr.colorError));
    }

    /** 批量删库：连带清库内壁纸文件、切换进度与定时任务（都在 LibraryStore.delete 内部）。 */
    private void deleteLibs(List<LibraryStore.Library> libs) {
        List<WallpaperStore.Item> before = WallpaperStore.load(a);
        boolean removedCurrent = false;
        for (LibraryStore.Library lib : libs) {
            LibraryStore.delete(a, lib.id);
            if (lib.id.equals(a.currentLibId())) {
                removedCurrent = true;
            }
        }
        Set<String> alive = new HashSet<>();
        for (WallpaperStore.Item item : WallpaperStore.load(a)) {
            alive.add(item.id);
        }
        for (WallpaperStore.Item item : before) {
            if (!alive.contains(item.id)) {
                a.evictImages(item.id);
            }
        }
        if (removedCurrent) {
            a.onCurrentLibraryDeleted();
        }
        exitGalleryMode();
        refreshLibs();
        a.refreshTimerStatus();
        // 删除在用的库会改变「谁在被接管」，同步一次接管状态
        a.syncTakeoverAsync();
    }

    /** 新建库弹窗：建好直接进它的库详情（接着就是「添加」）。 */
    void showNewLibDialog() {
        EditText input = new EditText(a);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        new AppAlertDialogBuilder(a)
                .setTitle(R.string.lib_new)
                .setView(a.wrapInput(input, R.string.lib_name_hint))
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    LibraryStore.Library created = LibraryStore.create(a, name);
                    refreshLibs();
                    if (created != null) {
                        a.openLibrary(created.id);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 库卡片网格：末尾一格「新建库」（排序与多选态下不显示）。 */
    final class LibAdapter extends ImageAdapter {

        LibAdapter() { super(GalleryController.this.a); }

        static final int NORMAL = 0;
        static final int SELECT = 1;
        static final int REORDER = 2;
        private static final int TYPE_LIB = 0;
        private static final int TYPE_NEW = 1;

        private final class Row {
            final LibraryStore.Library lib;
            final int count;
            final String thumbId;
            final MainActivity.DisplayRow snapshot;

            Row(LibraryStore.Library lib, int count, String thumbId) {
                this.lib = lib;
                this.count = count;
                this.thumbId = thumbId;
                snapshot = a.displayRow(lib.id, lib.name, count, thumbId, a.imageRevision(thumbId), a.imageClearEpoch,
                        LibraryStore.ownsScope(a, lib.id, true),
                        LibraryStore.ownsScope(a, lib.id, false));
            }
        }

        private final List<Row> rows = new ArrayList<>();
        int mode = NORMAL;
        final Set<String> selectedIds = new HashSet<>();

        void setItems(List<LibraryStore.Library> libs) {
            List<MainActivity.DisplayRow> before = rowSnapshots();
            List<Row> next = new ArrayList<>();
            Map<String, Integer> counts = WallpaperStore.libMemberCounts(a);
            for (LibraryStore.Library lib : libs) {
                Integer n = counts.get(lib.id);
                next.add(new Row(lib, n == null ? 0 : n, a.libCoverId(lib)));
            }
            rows.clear();
            rows.addAll(next);
            selectedIds.retainAll(currentIds());
            List<MainActivity.DisplayRow> after = rowSnapshots();
            if (!before.equals(after)) a.diffRows(before, after).dispatchUpdatesTo(this);
            if (mode == SELECT) {
                updateGalleryToolbar();
            }
        }

        private List<MainActivity.DisplayRow> rowSnapshots() {
            List<MainActivity.DisplayRow> out = new ArrayList<>();
            for (Row row : rows) out.add(row.snapshot);
            if (mode == NORMAL) out.add(a.displayRow("new-library"));
            return out;
        }

        void setMode(int newMode) {
            mode = newMode;
            selectedIds.clear();
            notifyDataSetChanged();
        }

        List<String> currentIds() {
            List<String> ids = new ArrayList<>();
            for (Row row : rows) {
                ids.add(row.lib.id);
            }
            return ids;
        }

        List<LibraryStore.Library> selectedLibs() {
            List<LibraryStore.Library> out = new ArrayList<>();
            for (Row row : rows) {
                if (selectedIds.contains(row.lib.id)) {
                    out.add(row.lib);
                }
            }
            return out;
        }

        boolean move(int from, int to) {
            if (from < 0 || to < 0 || from >= rows.size() || to >= rows.size()) {
                return false;
            }
            rows.add(to, rows.remove(from));
            notifyItemMoved(from, to);
            return true;
        }

        /** 槽位变更后标签要跟上；库详情的头部（设为桌面/锁屏两颗键）同一处刷。 */
        void refreshBadges() {
            notifyDataSetChanged();
            if (a.wallpaperAdapter != null) {
                a.wallpaperAdapter.refreshHeader();
            }
        }

        private void toggle(String libId) {
            if (!selectedIds.remove(libId)) {
                selectedIds.add(libId);
            }
            notifyDataSetChanged();
            updateGalleryToolbar();
        }

        @Override
        public int getItemViewType(int position) {
            return position < rows.size() ? TYPE_LIB : TYPE_NEW;
        }

        @Override
        public int getItemCount() {
            return rows.size() + (mode == NORMAL ? 1 : 0);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == TYPE_LIB ? R.layout.item_gallery_lib : R.layout.item_gallery_new;
            View view = LayoutInflater.from(a).inflate(layout, parent, false);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        void bindRow(RecyclerView.ViewHolder holder, int position) {
            View item = holder.itemView;
            if (getItemViewType(position) == TYPE_NEW) {
                // 与旁边的库卡片同高：库卡片现在只剩正方形封面（库名与张数压在封面上）
                int cell = (galleryRecycler.getWidth() > 0 ? galleryRecycler.getWidth()
                        : a.getResources().getDisplayMetrics().widthPixels) / 2 - a.dp(22);
                item.findViewById(R.id.new_lib_box).setMinimumHeight(cell);
                item.setOnClickListener(v -> showNewLibDialog());
                return;
            }
            final Row row = rows.get(position);
            final LibraryStore.Library lib = row.lib;
            MaterialCardView card = (MaterialCardView) item;
            ImageView cover = item.findViewById(R.id.img_lib_cover);
            if (row.thumbId != null) {
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                a.images.thumbnail(cover, row.thumbId);
            } else {
                a.images.unbind(cover);
                cover.setTag(null);
                cover.setScaleType(ImageView.ScaleType.CENTER);
                cover.setImageResource(R.drawable.ic_tab_wallpaper);
                cover.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(cover,
                        com.google.android.material.R.attr.colorOnSurfaceVariant)));
            }
            ((TextView) item.findViewById(R.id.tv_lib_name)).setText(lib.name);
            // 封面角上只写数字，不带「张壁纸」；读屏仍念完整说法
            TextView countView = item.findViewById(R.id.tv_lib_count);
            countView.setText(String.valueOf(row.count));
            countView.setContentDescription(a.getString(R.string.lib_count, row.count));
            item.findViewById(R.id.badge_lib_home).setVisibility(
                    LibraryStore.ownsScope(a, lib.id, true) ? View.VISIBLE : View.GONE);
            item.findViewById(R.id.badge_lib_lock).setVisibility(
                    LibraryStore.ownsScope(a, lib.id, false) ? View.VISIBLE : View.GONE);
            item.findViewById(R.id.iv_lib_drag).setVisibility(mode == REORDER ? View.VISIBLE : View.GONE);
            card.setChecked(mode == SELECT && selectedIds.contains(lib.id));
            // 蓝勾自带颜色：XML 里写 checkedIconTint=@null 会被 MaterialCardView 退回成主题色，只能在这里清掉
            card.setCheckedIconTint(null);
            card.setOnClickListener(v -> {
                if (mode == SELECT) {
                    toggle(lib.id);
                } else if (mode == NORMAL) {
                    a.openLibrary(lib.id);
                }
            });
            card.setOnLongClickListener(v -> {
                if (mode == REORDER) {
                    return false;
                }
                if (mode == NORMAL) {
                    setGalleryMode(SELECT);
                }
                toggle(lib.id);
                return true;
            });
            // 排序态：按下就拎起来（不用再等长按），松手落位写回 libraries.json
            card.setOnTouchListener((v, event) -> {
                if (mode == REORDER && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    libDragHelper.startDrag(holder);
                    return true;
                }
                return false;
            });
        }
    }
}