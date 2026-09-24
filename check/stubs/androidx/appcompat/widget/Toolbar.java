package androidx.appcompat.widget;

import android.view.MenuItem;

/** 本地类型检查桩：真实类来自 appcompat 库。 */
public class Toolbar extends android.view.View {

    public Toolbar(android.content.Context context) {
        super(context);
    }

    public void setTitle(CharSequence title) {
    }

    public void setTitle(int resId) {
    }

    public void setNavigationIcon(int resId) {
    }

    public void setNavigationOnClickListener(android.view.View.OnClickListener listener) {
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
    }

    /** 桩：真实接口为 androidx.appcompat.widget.Toolbar.OnMenuItemClickListener。 */
    public interface OnMenuItemClickListener {
        boolean onMenuItemSelected(MenuItem item);
    }
}
