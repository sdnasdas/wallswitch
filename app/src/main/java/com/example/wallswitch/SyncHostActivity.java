package com.example.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.List;

/**
 * 旧机（共享方）那页：整库打进 cache → 起局域网服务 → 屏上给二维码与可复制的地址 → 报实时进度。
 *
 * <p>生命周期就是这一页的全部纪律：服务只在 Activity 活着的时候存在，{@code onDestroy} 一律
 * {@code close()}；不申请 WakeLock（常亮用 {@link WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON}，
 * 随窗口自动释放），不注册广播、不起 Service、不加定时任务 —— 空闲时的切换链路一行不改。
 *
 * <p>包从哪来：24 小时内打过且文件还在就直接分享（屏上写明是哪一版），否则现场重打。
 * 这条路刻意不进用户的导出目录、也不要求先设导出目录。
 */
public class SyncHostActivity extends AppCompatActivity {

    /** 共享用的包落在 cache。名字与 {@code BackupStore.CACHE_ZIP} 无关，别改成 restore.zip。 */
    static final String CACHE_ZIP = "lan-share.zip";
    static final String K_AT = "lan_share_at";
    static final String K_BYTES = "lan_share_bytes";
    static final String K_SHA = "lan_share_sha256";
    static final String K_ITEMS = "lan_share_items";
    static final String K_IMAGES = "lan_share_images";
    static final String K_ORIG = "lan_share_originals";
    static final String K_THUMBS = "lan_share_thumbs";
    /** 这之内算「现成的包」，可以直接分享不重打。 */
    static final long FRESH_MS = 24 * 60 * 60 * 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LanHttp.Server server;
    private TextView state;
    private TextView packState;
    private TextView addr;
    private ImageView qr;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_sync_host);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE);
        state = findViewById(R.id.tv_sync_state);
        packState = findViewById(R.id.tv_sync_pack_state);
        addr = findViewById(R.id.tv_sync_addr);
        qr = findViewById(R.id.iv_sync_qr);
        TextView hint = findViewById(R.id.tv_sync_install_hint);
        hint.setText(getString(R.string.sync_install_hint, versionName()));
        findViewById(R.id.btn_sync_stop).setOnClickListener(v -> stopShared(true));
        findViewById(R.id.btn_sync_repack).setOnClickListener(v -> prepare(true));
        refreshNet();
        prepare(false);
    }

    /** 网络那一行只说「连没连 Wi-Fi」和 IP；不显示 SSID（那要精确定位权限，不该为这功能要）。 */
    private void refreshNet() {
        List<String> ips = LanPair.filterCandidates(LanPair.localIPv4());
        StringBuilder sb = new StringBuilder();
        for (String s : ips) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(s);
        }
        boolean wifi = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities cap = (cm == null || n == null) ? null : cm.getNetworkCapabilities(n);
            wifi = cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception ignored) {
        }
        TextView tv = findViewById(R.id.tv_sync_net);
        if (!wifi) {
            tv.setText(R.string.sync_net_none);
        } else if (sb.length() == 0) {
            tv.setText(R.string.sync_net_wifi);
        } else {
            tv.setText(getString(R.string.sync_net_wifi_ip, sb.toString()));
        }
    }

    /** force=true 一定重打；否则有 24 小时内的现成包就直接分享。 */
    private void prepare(boolean force) {
        File zip = new File(getCacheDir(), CACHE_ZIP);
        long at = prefs.getLong(K_AT, 0L);
        String sha = prefs.getString(K_SHA, "");
        long bytes = prefs.getLong(K_BYTES, 0L);
        boolean fresh = !force && at > 0L && sha != null && sha.length() == 64 && bytes > 0
                && zip.isFile() && zip.length() == bytes
                && System.currentTimeMillis() - at < FRESH_MS;
        if (fresh) {
            packState.setText(R.string.sync_pack_reused);
            startServer(zip, bytes, sha, prefs.getInt(K_ITEMS, 0), prefs.getInt(K_IMAGES, 0),
                    prefs.getInt(K_ORIG, 0), prefs.getInt(K_THUMBS, 0), at);
            return;
        }
        state.setText(R.string.sync_pack_running);
        long need = bytes > 0 ? bytes : WallpaperStore.load(this).size() * 8L * 1024 * 1024;
        long free = getCacheDir().getUsableSpace();
        if (free < need + need / 2L) {      // 打包本身还要一点点余量，按 1.5 倍报
            state.setText(getString(R.string.sync_no_space, (need * 3L / 2L - free) / 1048576L));
            return;
        }
        new Thread(() -> {
            final BackupStore.BackupResult r = BackupStore.backupToCache(getApplicationContext(), zip);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (r.error != null) {
                    state.setText(getString(R.string.sync_pack_failed, r.error));
                    return;
                }
                long now = System.currentTimeMillis();
                prefs.edit().putLong(K_AT, now).putLong(K_BYTES, r.zipBytes)
                        .putString(K_SHA, r.sha256).putInt(K_ITEMS, r.entries)
                        .putInt(K_IMAGES, r.images).putInt(K_ORIG, r.originals)
                        .putInt(K_THUMBS, r.thumbs).apply();
                packState.setText(getString(R.string.sync_pack_done, r.entries, r.images,
                        r.originals, r.thumbs, human(r.zipBytes)));
                startServer(zip, r.zipBytes, r.sha256, r.entries, r.images, r.originals,
                        r.thumbs, now);
            });
        }, "lan-pack").start();
    }

    /**
     * info 里的备份时刻。格式与包内 manifest.json 的 backup_time 完全一致
     * （yyyy-MM-dd HH:mm:ss）：收端那句「对方备份于 …」和还原确认框里那句是同一个字符串，
     * 两台机对不上数的时候不该再多一个变量。
     * 不用 TimerScheduler.clockText —— 它只有时分，昨天打的包会被念成今天。
     */
    static String stamp(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(millis));
    }

    private void startServer(File zip, long bytes, String sha, int items, int images,
                             int originals, int thumbs, long packedAt) {
        stopShared(false);
        String info = LanPair.encodeInfo(LanPair.PROTOCOL_V, zip.getName(), stamp(packedAt),
                items, images, originals, thumbs, bytes, sha, versionName());
        server = new LanHttp.Server(LanPair.randomToken(), info, new LanHttp.FilePayload(zip));
        server.setListener(new LanHttp.Listener() {
            @Override
            public void onConnected(String peer) {
                ui.post(() -> state.setText(getString(R.string.sync_connected, peer)));
            }

            @Override
            public void onProgress(long done, long total) {
                ui.post(() -> state.setText(getString(R.string.sync_progress,
                        total <= 0 ? 0 : (int) (done * 100 / total), human(done), human(total))));
            }

            @Override
            public void onFileSent(long sent) {
                ui.post(() -> state.setText(getString(R.string.sync_sent, human(sent))));
            }

            @Override
            public void onError(String message) {
                ui.post(() -> state.setText(getString(R.string.sync_error, message)));
            }
        });
        try {
            server.start();
        } catch (Exception | OutOfMemoryError e) {
            state.setText(getString(R.string.sync_error, e.getClass().getSimpleName()));
            return;
        }
        showAddress();
    }

    /** 地址 = 挑出来的本机 IPv4 + 系统分配的端口 + 随机口令；码和可复制的地址同时给。 */
    private void showAddress() {
        String ip = LanPair.prefer(LanPair.localIPv4());
        if (ip.isEmpty()) {
            qr.setImageDrawable(null);
            addr.setText(R.string.sync_no_addr);
            return;
        }
        String url = LanPair.buildUrl(ip, server.getPort(), server.getToken());
        addr.setText(url);
        Bitmap code = QrBitmap.encode(url);
        if (code == null) {
            qr.setImageDrawable(null);     // 码画不出来不能让功能死：地址照样能长按复制
        } else {
            qr.setImageBitmap(code);
        }
    }

    /** 字节数带上单位：光给数字会被当成 KB 或 GB 念错（他要求数字要带标签）。 */
    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void stopShared(boolean tellUser) {
        if (server != null) {
            server.close();
            server = null;
        }
        qr.setImageDrawable(null);
        addr.setText("");
        if (tellUser) {
            state.setText(R.string.sync_stopped);
        }
    }

    @Override
    protected void onDestroy() {
        if (server != null) {
            server.close();
            server = null;
        }
        super.onDestroy();
    }
}
