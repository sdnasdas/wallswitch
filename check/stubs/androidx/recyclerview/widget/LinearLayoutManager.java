package androidx.recyclerview.widget;

import android.content.Context;

/** 本地类型检查桩：真实类来自 recyclerview 库。 */
public class LinearLayoutManager extends RecyclerView.LayoutManager {

    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    public LinearLayoutManager(Context context) {
    }

    public LinearLayoutManager(Context context, int orientation, boolean reverseLayout) {
    }

    public int findFirstVisibleItemPosition() {
        return -1;
    }

    public android.view.View findViewByPosition(int position) {
        return null;
    }
}
