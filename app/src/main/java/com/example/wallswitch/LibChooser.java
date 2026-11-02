package com.example.wallswitch;

import android.content.Context;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 导入时的选库弹窗：只负责列库与回报选中的 id，不改任何归属。
 * 取景之后的保存（{@link EditActivity}）和批量默认导入（{@code MainActivity}）共用这一份，
 * 两边都不再拿「最近操作的库」当缺省。
 */
final class LibChooser {

    private LibChooser() {
    }

    static void show(Context context, String title, Consumer<String> onPick) {
        show(context, title, onPick, null);
    }

    /**
     * @param onPick 选中库后回调；弹窗选中即关，取消/点外面不回调
     * @param onCancel 取消/点外面时回调（批量导入那一侧用它退回「逐张 / 全部默认」选择）；
     *                 选中库时不会触发
     */
    static void show(Context context, String title, Consumer<String> onPick, Runnable onCancel) {
        List<LibraryStore.Library> loaded = LibraryStore.load(context);
        final List<LibraryStore.Library> libs = loaded.isEmpty()
                ? Collections.singletonList(LibraryStore.create(context, null))
                : loaded;
        Map<String, Integer> counts = new HashMap<>();
        for (WallpaperStore.Item item : WallpaperStore.load(context)) {
            for (String owner : item.libIds) {
                Integer seen = counts.get(owner);
                counts.put(owner, seen == null ? 1 : seen + 1);
            }
        }
        String[] items = new String[libs.size()];
        for (int i = 0; i < libs.size(); i++) {
            LibraryStore.Library lib = libs.get(i);
            Integer count = counts.get(lib.id);
            items[i] = context.getString(R.string.lib_pick_item,
                    lib.name, count == null ? 0 : count);
        }
        AppAlertDialogBuilder builder = new AppAlertDialogBuilder(context);
        builder.setTitle(title);
        builder.setItems(items, (d, which) -> onPick.accept(libs.get(which).id));
        builder.setCancelable(true);
        if (onCancel != null) {
            builder.setOnCancelListener(d -> onCancel.run());
        }
        builder.show();
    }
}
