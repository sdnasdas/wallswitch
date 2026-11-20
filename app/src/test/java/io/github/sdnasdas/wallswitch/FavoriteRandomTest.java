package io.github.sdnasdas.wallswitch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

public class FavoriteRandomTest {

    private static WallpaperStore.Item item(String id, boolean favorite) {
        WallpaperStore.Item item = new WallpaperStore.Item();
        item.id = id;
        item.favorite = favorite;
        return item;
    }

    @Test public void neverPicksTheWallpaperAlreadyOnScreen() {
        List<WallpaperStore.Item> items = List.of(item("a", false), item("b", true), item("c", false));
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            assertNotEquals("b", Switcher.pickRandom(items, "b", random));
        }
    }

    @Test public void singleWallpaperIsStillReturnedEvenIfCurrent() {
        List<WallpaperStore.Item> items = List.of(item("only", false));
        assertEquals("only", Switcher.pickRandom(items, "only", new Random(1)));
    }

    @Test public void emptyLibraryPicksNothing() {
        assertNull(Switcher.pickRandom(new ArrayList<>(), null, new Random(1)));
    }

    @Test public void favoriteIsPickedAboutTwiceAsOften() {
        List<WallpaperStore.Item> items = List.of(
                item("fav", true), item("p1", false), item("p2", false), item("p3", false));
        Map<String, Integer> counts = new HashMap<>();
        Random random = new Random(42);
        int rounds = 100_000;
        for (int i = 0; i < rounds; i++) {
            counts.merge(Switcher.pickRandom(items, null, random), 1, Integer::sum);
        }
        // 权重 2:1:1:1 → 收藏约 40%，每张普通约 20%
        double fav = counts.get("fav") / (double) rounds;
        assertEquals(0.40, fav, 0.01);
        for (String id : new String[]{"p1", "p2", "p3"}) {
            assertEquals(0.20, counts.get(id) / (double) rounds, 0.01);
        }
    }

    @Test public void withoutFavoritesEveryWallpaperIsEquallyLikely() {
        List<WallpaperStore.Item> items = List.of(item("a", false), item("b", false), item("c", false));
        Map<String, Integer> counts = new HashMap<>();
        Random random = new Random(3);
        for (int i = 0; i < 90_000; i++) {
            counts.merge(Switcher.pickRandom(items, "x", random), 1, Integer::sum);
        }
        for (String id : new String[]{"a", "b", "c"}) {
            assertEquals(1 / 3.0, counts.get(id) / 90_000.0, 0.01);
        }
    }

    @Test public void favoriteWeightIsTwo() {
        assertEquals(2, Switcher.weightOf(item("f", true)));
        assertEquals(1, Switcher.weightOf(item("n", false)));
    }
}
