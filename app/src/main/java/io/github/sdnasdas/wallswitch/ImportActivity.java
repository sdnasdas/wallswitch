package io.github.sdnasdas.wallswitch;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.IntentCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 外部应用「分享 / 更多打开方式」的透明入口。
 *
 * <p>微信那条链路是：点开图片 → 长按 → 更多打开方式 → 本页。聊天里的缩略图长按没有系统分享，
 * 所以只挂 ACTION_SEND 接不到它，必须同时挂 ACTION_VIEW。
 *
 * <p>只做两件事：把 URI 立刻复制进收件箱（临时读权限只在接收方活着时有效），然后把这一批 id
 * 交给 MainActivity。单张直接进裁剪页；多张由主界面问「逐张取景 / 全部默认导入」。
 * 本页不碰壁纸库，也不解码图片。
 */
public class ImportActivity extends Activity {

    /** 这一批收件箱 id 列表（ArrayList&lt;String&gt;）。 */
    public static final String EXTRA_IMPORTED_IDS = "imported_ids";

    // 复制很快就完成时不要闪一下进度卡（与通知「切换中」同一个 400ms 门槛）
    private static final long BUSY_DELAY_MS = 400;
    private static final int MAX_ITEMS = 50;
    // 与直链导入的 64MB 上限同一口径：外部应用可以塞任意流，不能无上限写盘
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean finished;
    private volatile int done;
    private int total;
    private View progressCard;
    private TextView progressText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_proxy);
        progressCard = findViewById(R.id.import_progress_card);
        progressText = findViewById(R.id.import_progress_text);
        progressCard.setVisibility(View.GONE);

        List<Uri> uris = collectUris(getIntent());
        if (uris.isEmpty()) {
            toast(getString(R.string.import_no_content));
            finish();
            return;
        }
        if (uris.size() > MAX_ITEMS) {
            uris = new ArrayList<>(uris.subList(0, MAX_ITEMS));
            toast(getString(R.string.import_too_many, MAX_ITEMS));
        }
        total = uris.size();
        main.postDelayed(() -> {
            if (!finished) {
                progressCard.setVisibility(View.VISIBLE);
                updateProgressText();
            }
        }, BUSY_DELAY_MS);

        final List<Uri> batch = uris;
        new Thread(() -> copyToInbox(batch), "import-proxy").start();
    }

    private void copyToInbox(List<Uri> uris) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        ArrayList<String> imported = new ArrayList<>();
        int failed = 0;
        for (Uri uri : uris) {
            if (finished) {
                break;
            }
            try {
                if (knownTooLarge(uri)) {
                    failed++;
                } else {
                    WallpaperStore.Item item = WallpaperStore.importToInbox(
                            getApplicationContext(), uri);
                    File inbox = WallpaperStore.getInboxFile(getApplicationContext(), item.id);
                    if (inbox.length() > MAX_BYTES) {
                        WallpaperStore.cancelImport(getApplicationContext(), item.id);
                        failed++;
                    } else {
                        imported.add(item.id);
                    }
                }
            } catch (Exception e) {
                failed++;
            }
            done++;
            updateProgressTextAsync();
        }
        final ArrayList<String> ids = imported;
        final int failCount = failed;
        main.post(() -> {
            finished = true;
            progressCard.setVisibility(View.GONE);
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (ids.isEmpty()) {
                toast(getString(failCount > 0
                        ? R.string.import_all_failed : R.string.import_no_content));
                finish();
                return;
            }
            if (failCount > 0) {
                toast(getString(R.string.import_partial, ids.size(), failCount));
            }
            Intent next = new Intent(ImportActivity.this, MainActivity.class);
            next.putStringArrayListExtra(EXTRA_IMPORTED_IDS, ids);
            next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(next);
            } finally {
                // 拉不起主界面也要把透明页收掉，文件已经在收件箱里，下次进 App 会接着处理
                finish();
            }
        });
    }

    /** 能问到长度就先挡；问不到（-1）按未知处理，复制完再按实际大小兜一次。 */
    private boolean knownTooLarge(Uri uri) {
        try (AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return afd != null && afd.getLength() > MAX_BYTES;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateProgressTextAsync() {
        final int current = done;
        main.post(() -> {
            if (!finished && progressCard.getVisibility() == View.VISIBLE) {
                progressText.setText(getString(R.string.import_progress_line, current, total));
            }
        });
    }

    private void updateProgressText() {
        progressText.setText(getString(R.string.import_progress_line, done, total));
    }

    private void toast(String text) {
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show();
    }

    @Override
    public void finish() {
        finished = true;
        super.finish();
    }

    @Override
    protected void onDestroy() {
        finished = true;
        super.onDestroy();
    }

    /** SEND / SEND_MULTIPLE / VIEW 三种入口的 URI 都归到这里；微信多张可能走 ClipData。 */
    private static List<Uri> collectUris(Intent intent) {
        Set<Uri> out = new LinkedHashSet<>();
        if (intent == null) {
            return new ArrayList<>();
        }
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    out.add(uri);
                }
            }
        }
        if (intent.getData() != null) {
            out.add(intent.getData());
        }
        Uri stream = streamUri(intent);
        if (stream != null) {
            out.add(stream);
        }
        // 少数发送方把多张图塞在 EXTRA_STREAM 的 ArrayList 里，而不是 ClipData
        ArrayList<Uri> list = IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri.class);
        if (list != null) {
            out.addAll(list);
        }
        return new ArrayList<>(out);
    }

    private static Uri streamUri(Intent intent) {
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
    }
}
