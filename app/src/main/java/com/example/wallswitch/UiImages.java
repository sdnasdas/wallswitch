package com.example.wallswitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 实例级图片绑定；公开方法在主线程调用，磁盘读取只使用 application Context。 */
public final class UiImages implements AutoCloseable {
    private static final int THUMB = 1, FULL = 2, META = 3;
    private final Context app;
    private final ExecutorService executor;
    private final LruCache<String, Bitmap> thumbs, previews;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakReference<UiImages> self = new WeakReference<>(this);
    private final WeakHashMap<View, Slots> bindings = new WeakHashMap<>();
    private final WeakHashMap<ImageView, DisplayStamp> displayed = new WeakHashMap<>();
    private final Revisions revisions = new Revisions();
    private final Map<String, Info> infos = new HashMap<>();
    private final Map<String, Preview> previewMarks = new HashMap<>();
    private final Map<Key, Flight> flights = new HashMap<>();
    private final Set<Key> negatives = new HashSet<>();
    private BiConsumer<Object, Runnable> publishWhenIdle;
    private Consumer<Object> discard;
    private long generation;
    private boolean closed;

    static final class Revisions {
        private final Map<String, Long> values = new HashMap<>();
        private long epoch;

        long revision(String id) {
            return values.getOrDefault(id, 0L);
        }

        long epoch() {
            return epoch;
        }

        void evict(String id) {
            values.put(id, revision(id) + 1);
        }

        void clear() {
            epoch++;
            values.clear();
        }

        boolean matches(String id, long revision, long epoch) {
            return this.epoch == epoch && revision(id) == revision;
        }
    }

    public static final class Info {
        public final Boolean jpeg;
        public final boolean live;

        public Info(Boolean jpeg, boolean live) {
            this.jpeg = jpeg;
            this.live = live;
        }
    }

    /** 已实际显示的内容身份；退订保留，不持有 View 或 Bitmap。 */
    record DisplayStamp(String id, long revision, long epoch) {
        boolean matches(Revisions revisions, Object tag, String targetId) {
            return id.equals(targetId) && id.equals(tag) && revisions.matches(id, revision, epoch);
        }
    }

    private record Key(String id, int kind, String prefix, int side, long revision, long epoch) {}
    private record Result(Bitmap bitmap, Info info, int quality) {}
    private record Preview(Key key, WeakReference<Bitmap> bitmap, int quality) {}
    private record Waiter(WeakReference<View> view, Key key, long generation) {}

    private static final class Flight {
        final List<Waiter> waiting = new ArrayList<>();
        FutureTask<Void> task;
        volatile boolean cancelled;

        void cancel() {
            cancelled = true;
            if (task != null) task.cancel(false);
        }
    }

    private static final class Binding {
        final Waiter waiter;
        int quality;
        Result pending;
        BiConsumer<View, Info> apply;

        Binding(Waiter waiter) { this.waiter = waiter; }
    }

    private static final class Slots {
        final String id;
        final Object imageKey = new Object();
        final Object metadataKey = new Object();
        Binding image, metadata;

        Slots(String id) { this.id = id; }
    }

    public UiImages(Context context, ExecutorService executor, LruCache<String, Bitmap> thumbs,
                    LruCache<String, Bitmap> previews, BiConsumer<Object, Runnable> publishWhenIdle,
                    Consumer<Object> discard) {
        this.app = Objects.requireNonNull(context.getApplicationContext());
        this.executor = Objects.requireNonNull(executor);
        this.thumbs = Objects.requireNonNull(thumbs);
        this.previews = Objects.requireNonNull(previews);
        this.publishWhenIdle = Objects.requireNonNull(publishWhenIdle);
        this.discard = Objects.requireNonNull(discard);
    }

    public void thumbnail(ImageView view, String id) {
        Waiter waiter = bind(view, id, THUMB, "", 0, null);
        if (waiter == null) return;
        view.setImageTintList(null);
        thumbnail(waiter, true);
    }

    public void full(ImageView view, String id, String prefix, int maxSide) {
        Waiter waiter = bind(view, id, FULL, Objects.requireNonNull(prefix), Math.max(1, maxSide), null);
        if (waiter == null) return;
        view.setImageTintList(null);
        Key key = waiter.key;
        Bitmap cached = previews.get(prefix + id);
        Preview mark = previewMark(prefix + id, cached);
        if (cached != null) {
            dispatch(waiter, new Result(cached, null, mark == null ? FULL : mark.quality), true);
            if (mark == null || (mark.quality == FULL && mark.key.side >= key.side)) return;
        }
        if (view.getDrawable() == null) {
            thumbnail(waiter, true);
        }
        if (!negatives.contains(key)) request(key, waiter);
    }

    public void metadata(View owner, String id, BiConsumer<View, Info> apply) {
        Waiter waiter = bind(owner, id, META, "", 0, Objects.requireNonNull(apply));
        if (waiter == null) return;
        Info cached = infos.get(id);
        if (cached != null) dispatch(waiter, new Result(null, cached, 0), true);
        else request(waiter.key, waiter);
    }

    public Boolean cachedLive(String id) {
        checkThread();
        Info info = infos.get(id);
        return closed || info == null ? null : info.live;
    }

    /** 当前目标已绑定（允许待解码的占位）；空目标必须没有旧图。 */
    boolean isBound(ImageView view, String id) {
        checkThread();
        if (closed) return false;
        if (id == null || id.isEmpty()) return view.getTag() == null && view.getDrawable() == null;
        Slots slots = bindings.get(view);
        Binding image = slots == null ? null : slots.image;
        return image != null && image.waiter.key.kind != META
                && Objects.equals(id, image.waiter.key.id) && binding(image.waiter) != null
                && (id != null || (view.getTag() == null && view.getDrawable() == null));
    }

    private Waiter bind(View view, String id, int kind, String prefix, int side, BiConsumer<View, Info> apply) {
        checkThread();
        if (closed) return null;
        if (id != null && id.isEmpty()) id = null;
        if (id == null && kind == META) {
            unbind(view);
            view.setTag(null);
            return null;
        }
        Key key = new Key(id, kind, prefix, side, revisions.revision(id), revisions.epoch());
        Slots slots = bindings.get(view);
        boolean sameId = Objects.equals(id, view.getTag()) && (slots == null || Objects.equals(slots.id, id));
        DisplayStamp stamp = view instanceof ImageView image ? displayed.get(image) : null;
        boolean sameImage = stamp != null && stamp.matches(revisions, view.getTag(), id);
        if (slots == null || !sameId) {
            unbind(view);
            slots = new Slots(id);
            bindings.put(view, slots);
        }
        Binding previous = kind == META ? slots.metadata : slots.image;
        Waiter waiter = new Waiter(new WeakReference<>(view), key, ++generation);
        Binding binding = new Binding(waiter);
        if (previous != null && (kind == META ? sameId : sameImage) && current(previous.waiter.key)) {
            binding.quality = previous.quality;
        }
        binding.apply = apply;
        if (kind == META) slots.metadata = binding;
        else slots.image = binding;
        view.setTag(id);
        if (kind != META && !sameImage && view instanceof ImageView image) {
            displayed.remove(image);
            image.setImageDrawable(null);
        }
        return id == null ? null : waiter;
    }

    private void thumbnail(Waiter waiter, boolean synchronous) {
        if (binding(waiter) == null) return;
        Key source = waiter.key;
        Key key = new Key(source.id, THUMB, "", 0, source.revision, source.epoch);
        Bitmap cached = thumbs.get(key.id);
        if (cached != null) {
            Result result = new Result(cached, null, THUMB);
            if (source.kind == FULL) cache(source, result);
            dispatch(waiter, result, synchronous);
        } else request(key, waiter);
    }

    private void request(Key key, Waiter waiter) {
        if (!current(key) || negatives.contains(key) || binding(waiter) == null) return;
        Flight flight = flights.get(key);
        if (flight != null) {
            flight.waiting.removeIf(old -> binding(old) == null);
            flight.waiting.add(waiter);
            return;
        }
        Flight created = new Flight();
        created.waiting.add(waiter);
        flights.put(key, created);
        WeakReference<UiImages> target = self;
        Context context = app;
        created.task = new FutureTask<>(() -> {
            if (!created.cancelled && target.get() != null) {
                Result result = load(context, key);
                UiImages images = target.get();
                if (images != null && !created.cancelled) images.enqueue(key, created, result);
            }
            return null;
        });
        try {
            executor.execute(created.task);
        } catch (RejectedExecutionException ignored) {
            complete(key, created, new Result(null, key.kind == META ? new Info(null, false) : null, 0));
        }
    }

    private synchronized void enqueue(Key key, Flight flight, Result result) {
        if (closed || flight.cancelled) return;
        WeakReference<UiImages> target = self;
        main.post(() -> {
            UiImages images = target.get();
            if (images != null) images.complete(key, flight, result);
        });
    }

    private void complete(Key key, Flight flight, Result result) {
        if (!current(key) || flight.cancelled || flights.get(key) != flight) return;
        flights.remove(key);
        List<Waiter> waiting = flight.waiting;
        if (key.kind == META) infos.put(key.id, result.info);
        else if (result.bitmap == null) negatives.add(key);
        else cache(key, result);
        for (Waiter waiter : waiting) {
            if (binding(waiter) == null) continue;
            if (key.kind == FULL && result.bitmap == null) thumbnail(waiter, false);
            else {
                if (key.kind == THUMB && waiter.key.kind == FULL && result.bitmap != null) cache(waiter.key, result);
                dispatch(waiter, result, false);
            }
        }
    }

    private Preview previewMark(String name, Bitmap bitmap) {
        Preview mark = previewMarks.get(name);
        return mark != null && mark.bitmap.get() == bitmap && current(mark.key) ? mark : null;
    }

    private void cache(Key key, Result result) {
        if (!current(key) || result.bitmap == null) return;
        if (key.kind == THUMB) {
            thumbs.put(key.id, result.bitmap);
            return;
        }
        String name = key.prefix + key.id;
        Preview old = previewMark(name, previews.get(name));
        if (old != null && (old.quality > result.quality
                || (old.quality == result.quality && old.key.side > key.side))) return;
        previews.put(name, result.bitmap);
        previewMarks.put(name, new Preview(key, new WeakReference<>(result.bitmap), result.quality));
    }

    private Binding binding(Waiter waiter) {
        View view = waiter.view.get();
        if (view == null || !current(waiter.key) || !Objects.equals(waiter.key.id, view.getTag())) return null;
        Slots slots = bindings.get(view);
        Binding binding = slots == null ? null : waiter.key.kind == META ? slots.metadata : slots.image;
        return binding != null && binding.waiter.generation == waiter.generation ? binding : null;
    }

    private void dispatch(Waiter waiter, Result result, boolean synchronous) {
        Binding binding = binding(waiter);
        if (binding == null || (result.bitmap == null && result.info == null)
                || result.quality < binding.quality
                || (binding.pending != null && result.quality < binding.pending.quality)) return;
        binding.pending = result;
        if (synchronous) {
            deliver(waiter, false);
            return;
        }
        View view = waiter.view.get();
        Slots slots = bindings.get(view);
        WeakReference<UiImages> target = self;
        publishWhenIdle.accept(waiter.key.kind == META ? slots.metadataKey : slots.imageKey, () -> {
            UiImages images = target.get();
            if (images != null) images.deliver(waiter, true);
        });
    }

    private void deliver(Waiter waiter, boolean asynchronous) {
        Binding binding = binding(waiter);
        if (binding == null) return;
        View view = waiter.view.get();
        Result result = binding.pending;
        if (result == null || (asynchronous && (!view.isAttachedToWindow() || !view.isShown()
                || view.getWindowVisibility() != View.VISIBLE))) return;
        binding.pending = null;
        BiConsumer<View, Info> apply = binding.apply;
        binding.apply = null;
        if (waiter.key.kind == META) {
            if (apply != null) apply.accept(view, result.info);
        } else if (result.bitmap != null && result.quality >= binding.quality) {
            ImageView image = (ImageView) view;
            image.setImageBitmap(result.bitmap);
            displayed.put(image, new DisplayStamp(waiter.key.id, waiter.key.revision, waiter.key.epoch));
            binding.quality = result.quality;
        }
    }

    private boolean current(Key key) {
        return !closed && revisions.matches(key.id, key.revision, key.epoch);
    }

    public void evict(String id) {
        checkThread();
        if (closed || id == null) return;
        revisions.evict(id);
        clearDisplayed(id);
        WallpaperStore.invalidateImageMarks(id);
        thumbs.remove(id);
        infos.remove(id);
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, Preview> entry : previewMarks.entrySet()) {
            if (entry.getValue().key.id.equals(id)) names.add(entry.getKey());
        }
        names.add("p:" + id);
        names.add("d:" + id);
        names.add("log_" + id);
        for (String name : names) {
            previews.remove(name);
            previewMarks.remove(name);
        }
        negatives.removeIf(key -> key.id.equals(id));
        flights.entrySet().removeIf(entry -> {
            if (!entry.getKey().id.equals(id)) return false;
            entry.getValue().cancel();
            return true;
        });
        bindings.values().removeIf(slots -> {
            if (!Objects.equals(slots.id, id)) return false;
            discardSlots(slots);
            return true;
        });
    }

    public void clear() {
        checkThread();
        revisions.clear();
        clearDisplayed(null);
        infos.clear();
        previewMarks.clear();
        negatives.clear();
        for (Flight flight : flights.values()) flight.cancel();
        flights.clear();
        for (Slots slots : bindings.values()) discardSlots(slots);
        bindings.clear();
        thumbs.evictAll();
        previews.evictAll();
        main.removeCallbacksAndMessages(null);
    }

    private void clearDisplayed(String id) {
        displayed.entrySet().removeIf(entry -> {
            DisplayStamp stamp = entry.getValue();
            if (id != null && !stamp.id.equals(id)) return false;
            ImageView image = entry.getKey();
            // Holder 已复用到别的 id 时，只移除旧身份，不碰新内容。
            if (image != null && stamp.id.equals(image.getTag())) image.setImageDrawable(null);
            return true;
        });
    }

    private void discardSlots(Slots slots) {
        if (slots != null && discard != null) {
            discard.accept(slots.imageKey);
            discard.accept(slots.metadataKey);
        }
    }

    public void unbind(View view) {
        checkThread();
        discardSlots(bindings.remove(view));
        flights.entrySet().removeIf(entry -> {
            Flight flight = entry.getValue();
            flight.waiting.removeIf(waiter -> waiter.view.get() == null
                    || waiter.view.get() == view || binding(waiter) == null);
            if (!flight.waiting.isEmpty()) return false;
            flight.cancel();
            return true;
        });
    }

    @Override
    public synchronized void close() {
        checkThread();
        if (closed) return;
        closed = true;
        clear();
        publishWhenIdle = null;
        discard = null;
    }

    private void checkThread() {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("UiImages requires main thread");
    }

    private static Result load(Context app, Key key) {
        if (key.kind == META) {
            Boolean jpeg = null;
            boolean live = false;
            try { jpeg = WallpaperStore.fullIsJpeg(app, key.id); } catch (Exception ignored) {}
            try { live = WallpaperStore.isLive(app, key.id); } catch (OutOfMemoryError | Exception ignored) {}
            return new Result(null, new Info(jpeg, live), 0);
        }
        Bitmap bitmap = null;
        try {
            bitmap = key.kind == THUMB ? WallpaperStore.getThumb(app, key.id)
                    : decodeBounded(WallpaperStore.getFullFile(app, key.id), key.side);
        } catch (OutOfMemoryError | Exception ignored) {}
        return new Result(bitmap, null, key.kind);
    }

    private static Bitmap decodeBounded(File file, int maxSide) {
        if (file == null || !file.isFile()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int longSide = Math.max(bounds.outWidth, bounds.outHeight);
            if (longSide <= 0) return null;
            int sample = 1;
            while (longSide / (sample * 2) >= maxSide) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (OutOfMemoryError | Exception ignored) {
            return null;
        }
    }
}
