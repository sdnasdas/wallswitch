package io.github.sdnasdas.wallswitch;

import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 下拉快捷设置中的「一键壁纸」：只发内部动作广播，不打开界面，也不主动请求解锁。
 * 使用普通（非 active）磁贴，只在快捷面板可见时刷新，不新增定时任务或常驻监听。
 */
public class PinnedWallpaperTileService extends TileService {

    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pinned-tile");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile PinnedWallpaperTileService listening;

    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;

    @Override
    public void onStartListening() {
        super.onStartListening();
        listening = this;
        refresh();
    }

    @Override
    public void onStopListening() {
        if (listening == this) {
            listening = null;
        }
        generation++;
        super.onStopListening();
    }

    @Override
    public void onDestroy() {
        if (listening == this) {
            listening = null;
        }
        generation++;
        super.onDestroy();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (!PinnedWallpaper.isConfigured(this)) {
            Toast.makeText(this, R.string.pinned_tile_unset, Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        if (!PinnedWallpaper.tryStartAction()) {
            return;
        }
        try {
            // 发广播前就占用闸门；只传这次预占标记，钉/撤仍在执行时现读。
            sendBroadcast(new Intent(this, NotifActionReceiver.class)
                    .setAction(NotifActionReceiver.ACTION_PIN)
                    .putExtra(NotifActionReceiver.EXTRA_PIN_RESERVED, true));
        } catch (RuntimeException e) {
            PinnedWallpaper.finishAction();
            Toast.makeText(this, R.string.status_failed_unknown, Toast.LENGTH_LONG).show();
        }
    }

    /** 只通知正在显示的磁贴；面板收起时不唤起 TileService，下次展开再现读。 */
    static void refresh() {
        PinnedWallpaperTileService service = listening;
        if (service != null) {
            service.queueRefresh();
        }
    }

    private void queueRefresh() {
        main.post(() -> {
            if (listening != this) {
                return;
            }
            final int request = ++generation;
            EXECUTOR.execute(() -> {
                // canUndo 需要读库及切换进度，不能在系统回调的主线程上做文件读取。
                final boolean configured = PinnedWallpaper.isConfigured(this);
                final boolean pinned = configured && PinnedWallpaper.canUndo(this);
                main.post(() -> {
                    if (listening != this || request != generation) {
                        return;
                    }
                    Tile tile = getQsTile();
                    if (tile == null) {
                        return;
                    }
                    boolean busy = PinnedWallpaper.isBusy();
                    int status = !configured ? R.string.pinned_tile_unset
                            : busy ? R.string.pinned_tile_busy
                            : pinned ? R.string.pinned_tile_on : R.string.pinned_tile_ready;
                    tile.setLabel(getString(R.string.pinned_tile_label));
                    tile.setState(tileState(configured, pinned, busy));
                    tile.setContentDescription(getString(R.string.pinned_tile_label)
                            + "，" + getString(status));
                    if (Build.VERSION.SDK_INT >= 29) {
                        tile.setSubtitle(getString(status));
                    }
                    tile.updateTile();
                });
            });
        });
    }

    /** 不用界面先行翻态：仅真实钉住态高亮，未配置或执行中暂不可点。 */
    static int tileState(boolean configured, boolean pinned, boolean busy) {
        if (!configured || busy) {
            return Tile.STATE_UNAVAILABLE;
        }
        return pinned ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }
}
