package io.github.sdnasdas.wallswitch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 在线壁纸库（v3.184）：大都会艺术博物馆 / 芝加哥艺术博物馆的公有领域绘画，后台定时下载到本地。
 *
 * <h3>模型</h3>
 * <ul>
 *   <li>每个来源对应一个普通的壁纸库（{@link LibraryStore.Library#online} 标着来源）：图库里照常显示、
 *       能绑桌面/锁屏、也进「全部壁纸」。<b>订阅开关就是这个库在不在</b> —— 设置页关掉 = 删库，
 *       在图库里删掉这个库也就等于关了订阅，不存第二份「开没开」的状态。</li>
 *   <li>下载来的条目带 {@code online_src}（藏品键，去重用）和署名，见 {@link WallpaperStore.Item#onlineSrc}。
 *       上屏、取景、轮播全走原有链路：下下来的字节就是原图，按默认取景（居中 fit-cover）出成品图。</li>
 *   <li>节奏：每个来源每天最多 {@link #PER_DAY} 张，库里下载来的超过 {@link #KEEP} 张就从最旧的淘汰；
 *       正在当值（桌面/锁屏/一键设置）的那张这一轮不淘汰。被复制到别的库的那张只摘掉在线库的归属，文件留着。</li>
 *   <li>只在不计流量的网络（WiFi）下跑，「立即更新」也一样 —— 大都会的原图一张 3~10MB。</li>
 * </ul>
 *
 * <h3>两家接口的实测要点（2026-11）</h3>
 * <ul>
 *   <li>大都会：老的 {@code /v1/search} 已于 2026-10-01 下线，改用分页的 {@code /v1.1/search}；
 *       没有中间尺寸，{@code primaryImage} 是真原图（常见 4000px、3~10MB），服务器支持 Range，
 *       所以下载做成断点续传：一轮跑不完下轮接着下。</li>
 *   <li>芝加哥：前面有 Cloudflare，按 User-Agent 放行 —— 安卓默认的 {@code Dalvik/...} 被拦 403，
 *       {@code okhttp/...} 放行；另按官方要求带 {@code AIC-User-Agent} 标明应用。
 *       IIIF 图片服务有尺寸白名单，只认 843 / 1686 / 3000 这几档，且不能超过原图；
 *       所以横幅按高度取 {@code ,3000}（只搜原图高 ≥ 3000 的），竖幅按宽度取。</li>
 * </ul>
 */
final class OnlineLibraries {

    static final String SRC_MET = "met";
    static final String SRC_AIC = "aic";
    static final String[] SOURCES = {SRC_MET, SRC_AIC};

    /** 每个来源每天最多新增几张。 */
    static final int PER_DAY = 3;
    /** 每个在线库最多保留几张下载来的图（用户自己放进去的不算）。 */
    static final int KEEP = 30;

    private static final String PREFS = "settings";
    private static final String WORK_DAILY = "online_libs_daily";
    private static final String WORK_NOW = "online_libs_now";
    static final String EXTRA_FORCE = "force";

    private static final String UA = "okhttp/4.12.0";
    private static final String AIC_UA = "wallswitch (https://github.com/sdnasdas/wallswitch)";
    /** 单个文件上限：再大就不是正常的藏品图了，也防坏链接把流量拖穿。 */
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    /** 一次 Worker 的工作时长预算（系统上限 10 分钟，留余量收尾）。 */
    private static final long RUN_BUDGET_MS = 8 * 60_000L;
    /** 一轮最多看几件候选（不合格的跳过），免得接口返回一堆不合格时一直翻。 */
    private static final int MAX_TRIES = 12;

    private static final String MET_SEARCH = "https://collectionapi.metmuseum.org/public/collection/v1.1/search"
            + "?hasImages=true&isPublicDomain=true&departmentId=11&medium=Paintings";
    private static final String MET_OBJECT = "https://collectionapi.metmuseum.org/public/collection/v1/objects/";
    private static final String AIC_SEARCH = "https://api.artic.edu/api/v1/artworks/search"
            + "?query%5Bbool%5D%5Bmust%5D%5B0%5D%5Bterm%5D%5Bis_public_domain%5D=true"
            + "&query%5Bbool%5D%5Bmust%5D%5B1%5D%5Bterm%5D%5Bartwork_type_id%5D=1"
            + "&query%5Bbool%5D%5Bmust%5D%5B2%5D%5Bexists%5D%5Bfield%5D=image_id"
            + "&query%5Bbool%5D%5Bmust%5D%5B3%5D%5Brange%5D%5Bthumbnail.height%5D%5Bgte%5D=3000"
            + "&fields=id,title,image_id,artist_title,date_display,thumbnail.width,thumbnail.height";
    private static final String AIC_IIIF = "https://www.artic.edu/iiif/2/";
    private static final String AIC_PAGE = "https://www.artic.edu/artworks/";

    private static final Random RANDOM = new Random();

    private OnlineLibraries() {
    }

    /** 一件待下载的藏品。 */
    private static final class Candidate {
        String key;
        String url;
        String credit;
        String link;

        JSONObject toJson() throws Exception {
            return new JSONObject().put("key", key).put("url", url)
                    .put("credit", credit == null ? "" : credit).put("link", link == null ? "" : link);
        }

        static Candidate fromJson(String raw) {
            try {
                JSONObject o = new JSONObject(raw);
                Candidate c = new Candidate();
                c.key = o.getString("key");
                c.url = o.getString("url");
                c.credit = emptyToNull(o.optString("credit", ""));
                c.link = emptyToNull(o.optString("link", ""));
                return c;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ==================== 设置页入口 ====================

    /** 来源的显示名（也是新建库的默认库名）。 */
    static int nameRes(String source) {
        return SRC_AIC.equals(source) ? R.string.online_src_aic : R.string.online_src_met;
    }

    static boolean isEnabled(Context ctx, String source) {
        return LibraryStore.findOnline(ctx, source) != null;
    }

    /** 打开订阅：建库、排上每日任务，并立刻排一次（连上 WiFi 就开始下）。 */
    static void enable(Context ctx, String source) {
        LibraryStore.createOnline(ctx, source, ctx.getString(nameRes(source)));
        ensureScheduled(ctx);
        runNow(ctx);
    }

    /** 关掉订阅 = 删掉这个库（与在图库里删库同一条链路：只在本库的图连文件一起删，复制到别处的留着）。 */
    static void disable(Context ctx, String source) {
        LibraryStore.Library lib = LibraryStore.findOnline(ctx, source);
        if (lib != null) {
            LibraryStore.delete(ctx, lib.id);
            // 「全部壁纸」正放着在线库独占的那张时，它随库一起没了：推进到下一张
            Switcher.repairAllPointers(ctx);
        }
        clearPending(ctx, source);
        ensureScheduled(ctx);
    }

    /** 每日任务：有在线库就排（KEEP，不打乱已有节奏），一个都没有就撤。App 启动与开机时各调一次自愈。 */
    static void ensureScheduled(Context ctx) {
        try {
            WorkManager wm = WorkManager.getInstance(ctx);
            boolean any = false;
            for (String source : SOURCES) {
                any |= isEnabled(ctx, source);
            }
            if (!any) {
                wm.cancelUniqueWork(WORK_DAILY);
                return;
            }
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    OnlineWorker.class, 24, TimeUnit.HOURS)
                    .setConstraints(constraints())
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                    .build();
            wm.enqueueUniquePeriodicWork(WORK_DAILY, ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (Exception ignored) {
        }
    }

    /** 「立即更新」：不受每日配额限制再拉一批（仍然只在 WiFi 下跑）。 */
    static void runNow(Context ctx) {
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OnlineWorker.class)
                    .setConstraints(constraints())
                    .setInputData(new Data.Builder().putBoolean(EXTRA_FORCE, true).build())
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                    .build();
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request);
        } catch (Exception ignored) {
        }
    }

    private static Constraints constraints() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresStorageNotLow(true)
                .build();
    }

    /** 设置页那一行的状态：上次成功拉到图的时刻（没有过返回 0）。 */
    static long lastSuccess(Context ctx, String source) {
        return prefs(ctx).getLong("online_" + source + "_last_ok", 0L);
    }

    /** 这个在线库里现在有几张下载来的图。 */
    static int downloadedCount(Context ctx, String source) {
        LibraryStore.Library lib = LibraryStore.findOnline(ctx, source);
        if (lib == null) {
            return 0;
        }
        int n = 0;
        for (WallpaperStore.Item item : WallpaperStore.loadByLib(ctx, lib.id)) {
            if (item.onlineSrc != null) {
                n++;
            }
        }
        return n;
    }

    // ==================== 备份 / 还原 ====================

    /**
     * 备份时不进包的壁纸 id：在线下载来的、且只挂在在线库里的那些（随时能重新下载）。
     * 被复制到用户自己库里的那张不在此列 —— 那已经是他自己挑过的图了。
     */
    static Set<String> backupExcludedIds(Context ctx) {
        Set<String> onlineLibs = new HashSet<>();
        for (LibraryStore.Library lib : LibraryStore.load(ctx)) {
            if (lib.online != null) {
                onlineLibs.add(lib.id);
            }
        }
        Set<String> out = new HashSet<>();
        if (onlineLibs.isEmpty()) {
            return out;
        }
        for (WallpaperStore.Item item : WallpaperStore.load(ctx)) {
            if (item.onlineSrc != null && !item.libIds.isEmpty() && onlineLibs.containsAll(item.libIds)) {
                out.add(item.id);
            }
        }
        return out;
    }

    /**
     * 还原之后（后台线程）：包里的 settings 会把「今天已下几张」「下了一半那件」也带回来，
     * 而对应的图并不在包里 —— 不清掉的话今天就不会再补，在线库会空到明天。
     * 顺带把指着缺图的当前指针推进掉，再排一次立即下载。
     */
    static void resetAfterRestore(Context ctx) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        for (String source : SOURCES) {
            e.remove("online_" + source + "_day")
                    .remove("online_" + source + "_added")
                    .remove("online_" + source + "_pending")
                    .remove("online_" + source + "_pending_tries");
        }
        e.apply();
        boolean any = false;
        for (String source : SOURCES) {
            LibraryStore.Library lib = LibraryStore.findOnline(ctx, source);
            if (lib == null) {
                continue;
            }
            any = true;
            for (boolean forHome : new boolean[]{true, false}) {
                String current = Switcher.getCurrent(ctx, lib.id, forHome);
                if (current != null && !WallpaperStore.getFullFile(ctx, current).exists()) {
                    Switcher.reapplyIfCurrent(ctx, lib.id, current);
                }
            }
        }
        Switcher.repairAllPointers(ctx);
        if (any) {
            ensureScheduled(ctx);
            runNow(ctx);
        }
    }

    // ==================== Worker 主体 ====================

    /**
     * 跑一轮：每个开着的来源补到今天的配额（force 时直接再拉一批），再淘汰超出上限的旧图。
     *
     * @return false = 时间预算用完还有没下完的（Worker 据此 retry，下次接着断点续传）
     */
    static boolean work(Context ctx, boolean force) {
        // 每日任务与「立即更新」可能同时醒：同一进程里串行，免得两边抢着写同一个半截文件
        synchronized (WORK_LOCK) {
            return workLocked(ctx, force);
        }
    }

    private static final Object WORK_LOCK = new Object();

    private static boolean workLocked(Context ctx, boolean force) {
        long deadline = SystemClock.elapsedRealtime() + RUN_BUDGET_MS;
        boolean finished = true;
        int[] screen = screenSize(ctx);
        for (String source : SOURCES) {
            LibraryStore.Library lib = LibraryStore.findOnline(ctx, source);
            if (lib == null) {
                clearPending(ctx, source);
                continue;
            }
            int want = force ? PER_DAY : PER_DAY - addedToday(ctx, source);
            if (want > 0) {
                finished &= fetchInto(ctx, source, lib.id, want, screen, deadline);
            }
            prune(ctx, source, lib.id);
        }
        return finished;
    }

    /**
     * 给一个来源下 want 张。先接上一轮没下完的那件（断点续传），再从接口随机翻候选。
     *
     * @return false = 预算用完、留了一件下了一半的（已记成 pending）
     */
    private static boolean fetchInto(Context ctx, String source, String libId, int want,
                                     int[] screen, long deadline) {
        Set<String> known = knownKeys(ctx);
        int added = 0;
        int tries = 0;
        Candidate pending = loadPending(ctx, source);
        List<Candidate> queue = new ArrayList<>();
        if (pending != null && !known.contains(pending.key) && bumpPendingTries(ctx, source) <= 5) {
            queue.add(pending);
        } else if (pending != null) {
            // 已经有了 / 接了五轮还没下完：不再死磕这一件
            clearPending(ctx, source);
        }
        boolean searched = false;
        while (added < want && tries < MAX_TRIES) {
            if (SystemClock.elapsedRealtime() > deadline) {
                return false;
            }
            if (queue.isEmpty()) {
                if (searched) {
                    break;
                }
                searched = true;
                try {
                    queue.addAll(SRC_AIC.equals(source) ? searchAic(ctx, known) : searchMet(ctx, known));
                } catch (Exception e) {
                    recordError(ctx, source);
                    break;
                }
                if (queue.isEmpty()) {
                    break;
                }
            }
            Candidate c = queue.remove(0);
            tries++;
            try {
                if (SRC_MET.equals(source) && c.url == null && !resolveMet(c)) {
                    continue;
                }
                File part = partFile(ctx, c.key);
                savePending(ctx, source, c);
                boolean done;
                try {
                    done = download(c.url, part, SRC_AIC.equals(source), deadline);
                } catch (IOException e) {
                    if (part.exists() && part.length() > 0) {
                        // 下到一半断了（超时 / 掉线）：半截留着，这轮先收手，下次接着下
                        recordError(ctx, source);
                        return false;
                    }
                    throw e;
                }
                if (!done) {
                    // 预算用完：半截文件和 pending 都留着，下一轮接着下
                    return false;
                }
                if (!bigEnough(part, screen)) {
                    // 太小上屏会糊：丢掉，换下一件（记进 known 免得这一轮又翻到它）
                    Files.deleteIfExists(part.toPath());
                    clearPending(ctx, source);
                    known.add(c.key);
                    continue;
                }
                WallpaperStore.importOnline(ctx, part, libId, screen[0], screen[1],
                        c.key, c.credit, c.link);
                clearPending(ctx, source);
                known.add(c.key);
                added++;
                markAdded(ctx, source);
            } catch (Exception | OutOfMemoryError e) {
                // 这一件出错（404 / 解码失败 / 磁盘写不下）：丢掉换下一件
                try {
                    Files.deleteIfExists(partFile(ctx, c.key).toPath());
                } catch (Exception ignored) {
                }
                clearPending(ctx, source);
                known.add(c.key);
                recordError(ctx, source);
            }
        }
        return true;
    }

    /** 淘汰：下载来的超过 KEEP 张就从最旧的摘掉；当值的那张这一轮跳过。 */
    private static void prune(Context ctx, String source, String libId) {
        try {
            List<String> owned = new ArrayList<>();
            for (WallpaperStore.Item item : WallpaperStore.loadByLib(ctx, libId)) {
                if (item.onlineSrc != null) {
                    owned.add(item.id);
                }
            }
            int excess = owned.size() - KEEP;
            if (excess <= 0) {
                return;
            }
            List<String> drop = new ArrayList<>();
            for (String id : owned) {
                if (drop.size() >= excess) {
                    break;
                }
                if (Switcher.inUseWhere(ctx, id) == null) {
                    drop.add(id);
                }
            }
            if (drop.isEmpty()) {
                return;
            }
            // 只挂在在线库的连文件一起删；被复制到别的库的只摘归属。
            // 当值的那几张上面已经跳过，所以各面的指针都还有效；全局那套保险起见再核一遍
            WallpaperStore.removeFromLib(ctx, drop, libId);
            Switcher.repairAllPointers(ctx);
        } catch (Exception ignored) {
        }
    }

    // ==================== 两家接口 ====================

    /** 大都会：随机翻一页对象 id（只有 id，详情下载前再逐件查）。 */
    private static List<Candidate> searchMet(Context ctx, Set<String> known) throws Exception {
        SharedPreferences p = prefs(ctx);
        int total = p.getInt("online_met_total", 2600);
        int limit = 30;
        int offset = RANDOM.nextInt(Math.max(1, total - limit));
        JSONObject res = new JSONObject(getText(MET_SEARCH + "&limit=" + limit + "&offset=" + offset, false));
        int realTotal = res.optInt("total", 0);
        if (realTotal > 0) {
            p.edit().putInt("online_met_total", realTotal).apply();
        }
        JSONArray ids = res.optJSONArray("objectIDs");
        if ((ids == null || ids.length() == 0) && offset > 0) {
            res = new JSONObject(getText(MET_SEARCH + "&limit=" + limit + "&offset=0", false));
            ids = res.optJSONArray("objectIDs");
        }
        List<Candidate> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (int i = 0; i < ids.length(); i++) {
            String key = SRC_MET + ":" + ids.optLong(i);
            if (!known.contains(key)) {
                Candidate c = new Candidate();
                c.key = key;
                out.add(c);
            }
        }
        Collections.shuffle(out, RANDOM);
        return out;
    }

    /** 大都会：查单件详情，补上原图地址与署名；不合格（非绘画 / 非公有领域 / 没图）返回 false。 */
    private static boolean resolveMet(Candidate c) throws Exception {
        String objectId = c.key.substring(SRC_MET.length() + 1);
        JSONObject o = new JSONObject(getText(MET_OBJECT + objectId, false));
        String image = o.optString("primaryImage", "");
        // medium=Paintings 还会混进细密画（Miniatures）：尺幅小、构图特殊，不当壁纸
        if (!o.optBoolean("isPublicDomain", false) || image.isEmpty()
                || !"Paintings".equals(o.optString("classification", ""))) {
            return false;
        }
        c.url = image;
        c.credit = joinCredit(o.optString("title", ""), o.optString("artistDisplayName", ""),
                o.optString("objectDate", ""));
        c.link = emptyToNull(o.optString("objectURL", ""));
        return true;
    }

    /** 芝加哥：随机翻一页（搜索条件已限定公有领域绘画、原图高 ≥ 3000），直接拼出 IIIF 地址。 */
    private static List<Candidate> searchAic(Context ctx, Set<String> known) throws Exception {
        SharedPreferences p = prefs(ctx);
        int limit = 20;
        int pages = Math.max(1, p.getInt("online_aic_pages", 40));
        int page = 1 + RANDOM.nextInt(pages);
        JSONObject res;
        try {
            res = new JSONObject(getText(AIC_SEARCH + "&limit=" + limit + "&page=" + page, true));
        } catch (Exception e) {
            res = null;
        }
        JSONArray data = res == null ? null : res.optJSONArray("data");
        if (data == null || data.length() == 0) {
            res = new JSONObject(getText(AIC_SEARCH + "&limit=" + limit + "&page=1", true));
            data = res.optJSONArray("data");
        }
        JSONObject pagination = res.optJSONObject("pagination");
        if (pagination != null) {
            int total = pagination.optInt("total", 0);
            // 接口翻页上限是 10000 条结果
            int maxPages = Math.min(total / limit, 10000 / limit);
            if (maxPages > 0) {
                p.edit().putInt("online_aic_pages", maxPages).apply();
            }
        }
        List<Candidate> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String key = SRC_AIC + ":" + o.optLong("id");
            String imageId = o.optString("image_id", "");
            JSONObject thumb = o.optJSONObject("thumbnail");
            if (known.contains(key) || imageId.isEmpty() || thumb == null) {
                continue;
            }
            String size = aicSize(thumb.optInt("width", 0), thumb.optInt("height", 0));
            if (size == null) {
                continue;
            }
            Candidate c = new Candidate();
            c.key = key;
            c.url = AIC_IIIF + imageId + "/full/" + size + "/0/default.jpg";
            c.credit = joinCredit(o.optString("title", ""), o.optString("artist_title", ""),
                    o.optString("date_display", ""));
            c.link = AIC_PAGE + o.optLong("id");
            out.add(c);
        }
        Collections.shuffle(out, RANDOM);
        return out;
    }

    /**
     * 芝加哥 IIIF 只认 843 / 1686 / 3000 这几档且不能超过原图：比屏幕宽的（几乎所有画）
     * 要的是高度，取 {@code ,3000}；比屏幕还瘦长的要的是宽度，取 3000 或 1686 宽（屏宽才 1000 多）。
     */
    private static String aicSize(int w, int h) {
        if (w <= 0 || h <= 0) {
            return null;
        }
        // 以 9:20 的竖屏为界：更宽 = 上屏要左右裁，决定清晰度的是高度
        if (w * 20L > h * 9L) {
            return h >= 3000 ? ",3000" : null;
        }
        if (w >= 3000) {
            return "3000,";
        }
        return w >= 1686 ? "1686," : null;
    }

    // ==================== 网络 ====================

    private static HttpURLConnection open(String url, boolean aic) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        if (aic) {
            conn.setRequestProperty("AIC-User-Agent", AIC_UA);
        }
        return conn;
    }

    private static String getText(String url, boolean aic) throws IOException {
        HttpURLConnection conn = open(url, aic);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 4 * 1024 * 1024) {
                        throw new IOException("响应过大");
                    }
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 断点续传下载到 part：已有半截就带 Range 接着下；服务器不认 Range（回 200）就从头来。
     *
     * @return true = 下完；false = 预算用完，半截留着下次接
     */
    private static boolean download(String url, File part, boolean aic, long deadline) throws IOException {
        part.getParentFile().mkdirs();
        long have = part.exists() ? part.length() : 0L;
        HttpURLConnection conn = open(url, aic);
        if (have > 0) {
            conn.setRequestProperty("Range", "bytes=" + have + "-");
        }
        try {
            int code = conn.getResponseCode();
            boolean append;
            if (code == 206) {
                append = true;
            } else if (code == 416 && have > 0) {
                // 请求的起点已经是文件末尾 = 上一轮其实下完了
                return true;
            } else if (code >= 200 && code < 300) {
                append = false;
                have = 0;
            } else {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[64 * 1024];
                long total = have;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > MAX_BYTES) {
                        // 超限的不是可续传的半截，是这件本身不要：删干净再报错（调用方据此换下一件）
                        out.close();
                        Files.deleteIfExists(part.toPath());
                        throw new IOException("超过上限");
                    }
                    if (SystemClock.elapsedRealtime() > deadline) {
                        out.flush();
                        return false;
                    }
                }
                out.flush();
                if (total == 0) {
                    throw new IOException("响应为空");
                }
            }
            return true;
        } finally {
            conn.disconnect();
        }
    }

    // ==================== 小工具 ====================

    /** 默认取景下裁出来那块的高度够不够：不到屏幕长边的六成，上屏放大太多会糊。 */
    private static boolean bigEnough(File file, int[] screen) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        Rect region = WallpaperStore.centerCoverRect(bounds.outWidth, bounds.outHeight, screen[0], screen[1]);
        return region != null && region.height() >= screen[1] * 0.6f;
    }

    /** 竖屏的真实分辨率（宽 &lt; 高）；Worker 里没有 Activity，从 DisplayManager 取。 */
    @SuppressWarnings("deprecation")
    static int[] screenSize(Context ctx) {
        DisplayMetrics m = new DisplayMetrics();
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d != null) {
                d.getRealMetrics(m);
            }
        } catch (Exception ignored) {
        }
        if (m.widthPixels <= 0 || m.heightPixels <= 0) {
            m = ctx.getResources().getDisplayMetrics();
        }
        return new int[]{Math.min(m.widthPixels, m.heightPixels), Math.max(m.widthPixels, m.heightPixels)};
    }

    /** 手机上已有的全部藏品键（任何库里的都算：复制到别处的那张也不再重下）。 */
    private static Set<String> knownKeys(Context ctx) {
        Set<String> keys = new HashSet<>();
        for (WallpaperStore.Item item : WallpaperStore.load(ctx)) {
            if (item.onlineSrc != null) {
                keys.add(item.onlineSrc);
            }
        }
        return keys;
    }

    private static String joinCredit(String title, String artist, String date) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{title, artist, date}) {
            String t = part == null ? "" : part.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(t);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static File partFile(Context ctx, String key) {
        return new File(new File(ctx.getCacheDir(), "online"), key.replace(':', '_') + ".part");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private static int addedToday(Context ctx, String source) {
        SharedPreferences p = prefs(ctx);
        if (!today().equals(p.getString("online_" + source + "_day", ""))) {
            return 0;
        }
        return p.getInt("online_" + source + "_added", 0);
    }

    private static void markAdded(Context ctx, String source) {
        int n = addedToday(ctx, source) + 1;
        prefs(ctx).edit()
                .putString("online_" + source + "_day", today())
                .putInt("online_" + source + "_added", n)
                .putLong("online_" + source + "_last_ok", System.currentTimeMillis())
                .apply();
    }

    private static void recordError(Context ctx, String source) {
        prefs(ctx).edit().putLong("online_" + source + "_last_err", System.currentTimeMillis()).apply();
    }

    private static Candidate loadPending(Context ctx, String source) {
        String raw = prefs(ctx).getString("online_" + source + "_pending", null);
        return raw == null ? null : Candidate.fromJson(raw);
    }

    private static void savePending(Context ctx, String source, Candidate c) {
        try {
            Candidate old = loadPending(ctx, source);
            SharedPreferences.Editor e = prefs(ctx).edit()
                    .putString("online_" + source + "_pending", c.toJson().toString());
            if (old == null || !old.key.equals(c.key)) {
                e.remove("online_" + source + "_pending_tries");
            }
            e.apply();
        } catch (Exception ignored) {
        }
    }

    /** 接续下了一半那件的次数 +1 并返回新值。 */
    private static int bumpPendingTries(Context ctx, String source) {
        SharedPreferences p = prefs(ctx);
        int n = p.getInt("online_" + source + "_pending_tries", 0) + 1;
        p.edit().putInt("online_" + source + "_pending_tries", n).apply();
        return n;
    }

    /** 清掉这个来源下了一半的那件（连同半截文件）。 */
    private static void clearPending(Context ctx, String source) {
        Candidate c = loadPending(ctx, source);
        if (c != null) {
            try {
                Files.deleteIfExists(partFile(ctx, c.key).toPath());
            } catch (Exception ignored) {
            }
        }
        prefs(ctx).edit().remove("online_" + source + "_pending")
                .remove("online_" + source + "_pending_tries").apply();
    }
}
