package io.github.sdnasdas.wallswitch;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 列表 adapter 共用的基类（原 MainActivity 内部类）：图片绑定订阅、可见时重绑、detach 解绑。
 * 抽成顶层类后，各页面的 adapter 搬到各自的控制器里也能继承它；共享图片管线经 {@link #a} 取。
 */
abstract class ImageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    MainActivity a;
    private RecyclerView recycler;
    private boolean shown;
    private final UiMotion.ContentBindings bindings = new UiMotion.ContentBindings();

    ImageAdapter(MainActivity a) {
        this.a = a;
        registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { bindings.changed(); }
            @Override public void onItemRangeChanged(int start, int count) { bindings.changed(); }
            @Override public void onItemRangeInserted(int start, int count) { bindings.changed(); }
            @Override public void onItemRangeRemoved(int start, int count) { bindings.changed(); }
            @Override public void onItemRangeMoved(int from, int to, int count) { bindings.changed(); }
        });
    }

    private boolean hasImageBindings(View view) {
        if (view instanceof ImageView image && view.getTag() instanceof String id
                && !a.images.isBound(image, id)) return false;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (!hasImageBindings(group.getChildAt(i))) return false;
            }
        }
        return true;
    }

    abstract void bindRow(RecyclerView.ViewHolder holder, int position);

    @Override public final void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        bindRow(holder, position);
        bindings.bound(holder.itemView, position);
    }

    boolean prepareVisible() {
        if (recycler == null || !recycler.isLaidOut() || recycler.isComputingLayout()
                || recycler.hasPendingAdapterUpdates() || recycler.isLayoutRequested()) return false;
        for (int i = 0; i < recycler.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = recycler.getChildViewHolder(recycler.getChildAt(i));
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION || position >= getItemCount()) return false;
            if (!bindings.matches(holder.itemView, position) || !hasImageBindings(holder.itemView)) {
                onBindViewHolder(holder, position);
            }
        }
        return !recycler.hasPendingAdapterUpdates() && !recycler.isLayoutRequested();
    }

    private final android.view.ViewTreeObserver.OnGlobalLayoutListener visibility = () -> {
        if (recycler == null || shown == recycler.isShown()) return;
        shown = recycler.isShown();
        if (shown) rebindVisible();
        else a.unbindImageTree(recycler);
    };

    @Override public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
        recycler = rv;
        rv.setItemAnimator(null);
        shown = rv.isShown();
        rv.getViewTreeObserver().addOnGlobalLayoutListener(visibility);
    }

    @Override public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
        rv.getViewTreeObserver().removeOnGlobalLayoutListener(visibility);
        a.unbindImageTree(rv);
        recycler = null;
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        a.unbindImageTree(holder.itemView);
    }

    @Override public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
        a.unbindImageTree(holder.itemView);
    }

    @Override public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        int position = holder.getBindingAdapterPosition();
        if (position != RecyclerView.NO_POSITION && position < getItemCount()) {
            onBindViewHolder(holder, position);
        }
    }

    void rebindVisible() {
        if (recycler == null) return;
        a.motion.whenIdle(this, () -> {
            if (recycler == null || !recycler.isShown()) return;
            for (int i = 0; i < recycler.getChildCount(); i++) {
                onViewAttachedToWindow(recycler.getChildViewHolder(recycler.getChildAt(i)));
            }
        });
    }
}