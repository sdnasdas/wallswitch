package androidx.recyclerview.widget;

/** 本地类型检查桩：真实类来自 recyclerview 库。 */
public class RecyclerView extends android.view.ViewGroup {

    public RecyclerView(android.content.Context context) {
        super(context);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    }

    public void setLayoutManager(LayoutManager layout) {
    }

    public void setAdapter(Adapter adapter) {
    }

    public void addOnScrollListener(OnScrollListener listener) {
    }

    public void addOnItemTouchListener(OnItemTouchListener listener) {
    }

    public static final int SCROLL_STATE_DRAGGING = 1;

    /** 桩：真实类为 androidx.recyclerview.widget.RecyclerView.OnItemTouchListener。 */
    public interface OnItemTouchListener {
        boolean onInterceptTouchEvent(RecyclerView recyclerView, android.view.MotionEvent e);

        void onTouchEvent(RecyclerView recyclerView, android.view.MotionEvent e);

        void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept);
    }

    /** 桩：真实类为 androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener。 */
    public static class SimpleOnItemTouchListener implements OnItemTouchListener {

        @Override
        public boolean onInterceptTouchEvent(RecyclerView recyclerView, android.view.MotionEvent e) {
            return false;
        }

        @Override
        public void onTouchEvent(RecyclerView recyclerView, android.view.MotionEvent e) {
        }

        @Override
        public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        }
    }

    /** 桩：真实类为 androidx.recyclerview.widget.RecyclerView.OnScrollListener。 */
    public abstract static class OnScrollListener {
        public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
        }

        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
        }
    }

    public abstract static class LayoutManager {
    }

    public abstract static class Adapter<VH extends ViewHolder> {

        public abstract VH onCreateViewHolder(android.view.ViewGroup parent, int viewType);

        public abstract void onBindViewHolder(VH holder, int position);

        public abstract int getItemCount();

        public final void notifyDataSetChanged() {
        }

        public final void notifyItemChanged(int position) {
        }
    }

    public abstract static class ViewHolder {

        public final android.view.View itemView;

        public ViewHolder(android.view.View itemView) {
            this.itemView = itemView;
        }
    }
}