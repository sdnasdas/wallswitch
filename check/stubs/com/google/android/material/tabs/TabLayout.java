package com.google.android.material.tabs;

/** 本地类型检查桩：真实类来自 material 库。 */
public class TabLayout extends android.view.ViewGroup {

    public TabLayout(android.content.Context context) {
        super(context);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    }

    public Tab newTab() {
        return null;
    }

    public void addTab(Tab tab) {
    }

    public void addOnTabSelectedListener(OnTabSelectedListener listener) {
    }

    /** 桩：真实类为 com.google.android.material.tabs.TabLayout.Tab。 */
    public static final class Tab {
        public Tab setText(int resId) {
            return this;
        }

        public Tab setText(CharSequence text) {
            return this;
        }

        public int getPosition() {
            return 0;
        }
    }

    /** 桩：真实接口为 com.google.android.material.tabs.TabLayout.OnTabSelectedListener。 */
    public interface OnTabSelectedListener {
        void onTabSelected(Tab tab);

        void onTabUnselected(Tab tab);

        void onTabReselected(Tab tab);
    }
}
