package com.example.wallswitch;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

/**
 * 新机（接收方）那页：扫码（或手动输入地址）→ 显示对方包信息让你确认要收多大 → 收包 → 回主界面还原。
 *
 * <p>三个态共用一个布局：扫（相机）/ 问（信息卡）/ 收（进度）。相机只在「扫」这一态开着，
 * 扫到立刻 {@code stop()}，不等用户点下一步；{@code onPause} 也关。
 *
 * <p>本页**不做还原**：收好的文件路径通过 {@link #EXTRA_ZIP} 回传给 MainActivity，
 * 由那里现成的「回显 → 确认 → 还原 → recreate 落 onResume 自愈」那一条路接手，不写第二套。
 */
public class SyncJoinActivity extends AppCompatActivity {

    /** 回传给 MainActivity 的本地包绝对路径（在 {@code getCacheDir()/lan-receive.zip}）。 */
    public static final String EXTRA_ZIP = "zip";
    static final String CACHE_ZIP = "lan-receive.zip";
    static final int CONNECT_MS = 6000;
    static final int READ_MS = 30_000;

    private static final int STATE_SCAN = 0;
    private static final int STATE_INFO = 1;
    private static final int STATE_RECV = 2;

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private CameraScan camera;
    private ActivityResultLauncher<String> cameraPermission;
    private int state = STATE_SCAN;
    private LanPair.Target target;
    private LanPair.Info info;
    private FrameLayout boxScan;
    private LinearLayout boxInfo;
    private LinearLayout boxRecv;
    private TextView infoText;
    private TextView stateText;
    private TextView scanHint;
    private ProgressBar bar;
    private boolean receiving;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_sync_join);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boxScan = findViewById(R.id.box_scan);
        boxInfo = findViewById(R.id.box_info);
        boxRecv = findViewById(R.id.box_recv);
        infoText = findViewById(R.id.tv_join_info);
        stateText = findViewById(R.id.tv_join_state);
        scanHint = findViewById(R.id.tv_scan_hint);
        bar = findViewById(R.id.pb_join);
        cameraPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), this::onCameraPermission);
        findViewById(R.id.btn_join_manual).setOnClickListener(v -> askAddress());
        findViewById(R.id.btn_join_go).setOnClickListener(v -> startReceive());
        findViewById(R.id.btn_join_again).setOnClickListener(v -> {
            target = null;
            info = null;
            showState(STATE_SCAN);
        });
        refreshNet();
        showState(STATE_SCAN);
    }

    /** 不在 Wi-Fi 上时先把话说明：这时候连相机都不用开，省一次白烧。 */
    private void refreshNet() {
        boolean wifi = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities cap = (cm == null || n == null) ? null : cm.getNetworkCapabilities(n);
            wifi = cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception ignored) {
        }
        TextView tv = findViewById(R.id.tv_join_net);
        if (wifi) {
            tv.setText("");
        } else {
            tv.setText(R.string.sync_no_wifi_warn);
        }
    }

    private void showState(int next) {
        state = next;
        boxScan.setVisibility(next == STATE_SCAN ? android.view.View.VISIBLE
                : android.view.View.GONE);
        boxInfo.setVisibility(next == STATE_INFO ? android.view.View.VISIBLE
                : android.view.View.GONE);
        boxRecv.setVisibility(next == STATE_RECV ? android.view.View.VISIBLE
                : android.view.View.GONE);
        if (next == STATE_SCAN) {
            startCamera();
        } else {
            stopCamera();
        }
    }

    private void startCamera() {
        if (camera != null) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA);
            return;
        }
        camera = new CameraScan(this, findViewById(R.id.tv_scan_view), new CameraScan.Callbacks() {
            @Override
            public void onText(String text) {
                onScanned(text);
            }

            @Override
            public void onFail(String why) {
                Toast.makeText(SyncJoinActivity.this,
                        getString(R.string.sync_camera_fail, why), Toast.LENGTH_LONG).show();
                stopCamera();
                scanHint.setText(R.string.sync_manual_hint);
            }
        });
        camera.start();
    }

    private void onCameraPermission(boolean granted) {
        if (!granted) {
            scanHint.setText(R.string.sync_camera_denied);
            return;
        }
        startCamera();
    }

    private void stopCamera() {
        if (camera != null) {
            camera.stop();
            camera = null;
        }
    }

    /** 手动输入那条退路：扫不动、或系统相机比 App 内好使时，长按复制过来的地址粘进去就能收。 */
    private void askAddress() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.sync_addr_input_hint);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sync_manual_entry)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        onScanned(input.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 扫到 / 手输之后先取 info 给用户看一眼现场，再决定收不收（几百 MB 不是随手点的）。 */
    private void onScanned(String text) {
        stopCamera();
        LanPair.Target t = LanPair.parse(text);
        if (t.error != null) {
            Toast.makeText(this, t.error, Toast.LENGTH_LONG).show();
            showState(STATE_SCAN);
            return;
        }
        target = t;
        info = null;
        infoText.setText(R.string.sync_pack_running);
        showState(STATE_INFO);
        new Thread(() -> {
            LanPair.Info parsed = null;
            String err = null;
            try {
                parsed = LanPair.parseInfo(LanClient.fetchInfo(t, CONNECT_MS));
                if (parsed.error != null) {
                    err = parsed.error;
                }
            } catch (Exception | OutOfMemoryError e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final LanPair.Info show = parsed;
            final String why = err;
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || state != STATE_INFO) {
                    return;
                }
                if (show == null || show.error != null) {
                    infoText.setText(getString(R.string.sync_info_broken,
                            show != null ? show.error : why));
                    return;
                }
                info = show;
                // %7$d 要的是 int：本机条目数直接传，别套 String.valueOf（%d 配 String 会当场抛）
                infoText.setText(getString(R.string.sync_info_card, show.time, show.items,
                        show.images, show.originals, show.thumbs,
                        SyncHostActivity.human(show.bytes),
                        WallpaperStore.load(this).size()));
            });
        }, "lan-info").start();
    }

    /** 收包。先写 .part 验过摘要再改名，失败一律删干净（定稿里不做断点续传）。 */
    private void startReceive() {
        if (target == null || info == null || receiving) {
            return;
        }
        receiving = true;
        showState(STATE_RECV);
        bar.setProgress(0);
        stateText.setText(getString(R.string.sync_downloading, 0, "0 B",
                SyncHostActivity.human(info.bytes)));
        File dst = new File(getCacheDir(), CACHE_ZIP);
        new Thread(() -> {
            final LanClient.Result r = LanClient.download(target, info, dst, CONNECT_MS, READ_MS,
                    (done, total) -> ui.post(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        int pct = total <= 0 ? 0 : (int) (done * 100 / total);
                        bar.setProgress(pct);
                        stateText.setText(getString(R.string.sync_downloading, pct,
                                SyncHostActivity.human(done), SyncHostActivity.human(total)));
                    }));
            ui.post(() -> {
                receiving = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (r.error != null) {
                    stateText.setText(getString(R.string.sync_failed, r.error));
                    boxRecv.setVisibility(android.view.View.VISIBLE);
                    boxInfo.setVisibility(android.view.View.VISIBLE);
                    state = STATE_INFO;
                    return;
                }
                Intent data = new Intent();
                data.putExtra(EXTRA_ZIP, dst.getAbsolutePath());
                setResult(Activity.RESULT_OK, data);
                finish();
            });
        }, "lan-receive").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (state == STATE_SCAN) {
            stopCamera();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (state == STATE_SCAN) {
            startCamera();
        }
    }

    @Override
    protected void onDestroy() {
        stopCamera();
        super.onDestroy();
    }
}
