package io.github.sdnasdas.wallswitch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 锁屏「灭屏时切换」的触发源（v3.170，v3.169 探针转正）：灭屏那一刻给锁屏换下一张，
 * 下次亮屏看到的就是新图。是否该切、节流、记日志都收口在 {@link Switcher#switchOnScreenOff}。
 *
 * <p>为什么挂在引擎服务上：灭屏广播只能动态注册，得有进程在灭屏那一刻活着；
 * 桌面引擎被系统壁纸服务绑定常驻，加上用户开着「允许后台运行」，这条监听就一直在。
 * 所以这一档要求桌面正在用本 App 的动态壁纸（设置面板里写明）。
 *
 * <p>真机探针（v3.169，MagicOS）：灭屏后 1~3ms 起写，setStream 70~330ms 写完，
 * 亮屏前全部写完、亮屏时系统锁屏 id 都是刚写进去那张。
 */
final class ScreenOffTrigger {

    private static boolean registered;
    /** 写锁屏专用线程：setStream 是几百毫秒级的阻塞调用，不能占主线程。 */
    private static final ExecutorService WRITE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lock-screen-off");
        t.setDaemon(true);
        return t;
    });
    /** 上一张还没写完时再来的灭屏直接丢掉，不排队叠写。 */
    private static final AtomicBoolean writing = new AtomicBoolean(false);

    private ScreenOffTrigger() {
    }

    /** 引擎服务 onCreate 时调用；进程内只挂一次，随进程生命周期。 */
    static synchronized void register(Context ctx) {
        if (registered) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        try {
            // 灭屏是受保护的系统广播，只能动态注册；NOT_EXPORTED 照样收得到（同电池温度那条）
            ContextCompat.registerReceiver(app, new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        onScreenOff(app);
                    }
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED);
            registered = true;
        } catch (Throwable ignored) {
        }
    }

    private static void onScreenOff(Context ctx) {
        // 只有锁屏选了「灭屏时切换」才动；其余档位这条监听什么都不做（零开销）
        if (!LibraryStore.TRIGGER_SCREEN_OFF.equals(LibraryStore.scopeTriggerMode(ctx, false))) {
            return;
        }
        // 通话中的熄屏是贴脸（距离传感器），挂断亮屏也不会经过锁屏，换了白写
        if (inCall(ctx)) {
            return;
        }
        if (!writing.compareAndSet(false, true)) {
            return;
        }
        WRITE.execute(() -> {
            try {
                Switcher.switchOnScreenOff(ctx);
            } catch (Throwable ignored) {
            } finally {
                writing.set(false);
            }
        });
    }

    /** 电话或网络通话（微信等）进行中。AudioManager 的模式不需要任何权限。 */
    private static boolean inCall(Context ctx) {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am == null) {
                return false;
            }
            int mode = am.getMode();
            return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
        } catch (Throwable e) {
            return false;
        }
    }
}
