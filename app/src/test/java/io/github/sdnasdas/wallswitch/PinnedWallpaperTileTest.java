package io.github.sdnasdas.wallswitch;

import android.service.quicksettings.Tile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class PinnedWallpaperTileTest {

    @Test public void unconfiguredTileIsUnavailableRegardlessOfOldState() {
        for (boolean pinned : new boolean[]{false, true}) {
            for (boolean busy : new boolean[]{false, true}) {
                assertEquals(Tile.STATE_UNAVAILABLE,
                        PinnedWallpaperTileService.tileState(false, pinned, busy));
            }
        }
    }

    @Test public void onlyRealPinnedStateHighlightsIdleTile() {
        assertEquals(Tile.STATE_INACTIVE,
                PinnedWallpaperTileService.tileState(true, false, false));
        assertEquals(Tile.STATE_ACTIVE,
                PinnedWallpaperTileService.tileState(true, true, false));
    }

    @Test public void busyTileCannotTriggerAnotherAction() {
        assertEquals(Tile.STATE_UNAVAILABLE,
                PinnedWallpaperTileService.tileState(true, false, true));
        assertEquals(Tile.STATE_UNAVAILABLE,
                PinnedWallpaperTileService.tileState(true, true, true));
    }

    @Test public void duplicateActionIsDroppedAndGateCanBeReused() {
        PinnedWallpaper.ActionGate gate = new PinnedWallpaper.ActionGate();
        assertFalse(gate.isBusy());
        assertTrue(gate.tryStart());
        assertTrue(gate.isBusy());
        assertFalse(gate.tryStart());
        gate.finish();
        assertFalse(gate.isBusy());
        assertTrue(gate.tryStart());
        gate.finish();
    }

    @Test public void delayedWorkerCannotQueueAnAutomaticReverseAction() {
        PinnedWallpaper.ActionGate gate = new PinnedWallpaper.ActionGate();
        List<Runnable> scheduled = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        // 先抢闸门再派发：模拟第一个后台线程尚未获得 CPU 的情况。
        assertTrue(gate.tryStart());
        scheduled.add(() -> {
            try {
                actions.add("apply");
            } finally {
                gate.finish();
            }
        });
        if (gate.tryStart()) {
            scheduled.add(() -> actions.add("undo"));
        }
        assertEquals(1, scheduled.size());
        scheduled.get(0).run();
        assertEquals(List.of("apply"), actions);
        assertFalse(gate.isBusy());
        // 真正完成后，下一次主动点击才可进入。
        assertTrue(gate.tryStart());
        gate.finish();
    }

    @Test public void simultaneousEntrancesShareOneWinner() throws Exception {
        PinnedWallpaper.ActionGate gate = new PinnedWallpaper.ActionGate();
        ExecutorService threads = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                attempts.add(threads.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("启动超时");
                    }
                    return gate.tryStart();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(5, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertEquals(1, accepted);
            assertTrue(gate.isBusy());
        } finally {
            start.countDown();
            gate.finish();
            threads.shutdownNow();
        }
    }
}
