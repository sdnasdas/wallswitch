package androidx.recyclerview.widget;

/** 本地类型检查桩：真实类来自 recyclerview 库。 */
public class ItemTouchHelper {

    public static final int UP = 2;

    public static final int DOWN = 128;

    public static final int START = 4;

    public static final int END = 8;

    // 真实常量：拖动中 / 空闲（onSelectedChanged 的 actionState）
    public static final int ACTION_STATE_IDLE = 0;

    public static final int ACTION_STATE_DRAG = 3;

    public ItemTouchHelper(Callback callback) {
    }

    public void attachToRecyclerView(RecyclerView recyclerView) {
    }

    public void startDrag(RecyclerView.ViewHolder viewHolder) {
    }

    /** 桩：真实类为 androidx.recyclerview.widget.ItemTouchHelper.Callback。 */
    public abstract static class Callback {

        public abstract boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target);

        public abstract void onSwiped(RecyclerView.ViewHolder viewHolder, int direction);

        public abstract int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder);

        public boolean isLongPressDragEnabled() {
            return true;
        }

        public boolean isItemViewSwipeEnabled() {
            return true;
        }

        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
        }

        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
        }
    }

    /** 桩：真实类为 androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback。 */
    public abstract static class SimpleCallback extends Callback {

        public SimpleCallback(int dragDirs, int swipeDirs) {
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            return 0;
        }
    }
}
