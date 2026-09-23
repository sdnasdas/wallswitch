package androidx.drawerlayout.widget;

/** 本地类型检查桩：真实类来自 drawerlayout 库（appcompat 传递依赖）。 */
public class DrawerLayout extends android.view.ViewGroup {

    public DrawerLayout(android.content.Context context) {
        super(context);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    }

    public void openDrawer(int gravity) {
    }

    public void closeDrawer(int gravity) {
    }

    public void addDrawerListener(DrawerListener listener) {
    }

    /** 桩：真实接口为 androidx.drawerlayout.widget.DrawerLayout.DrawerListener。 */
    public interface DrawerListener {
        void onDrawerSlide(android.view.View drawerView, float slideOffset);

        void onDrawerOpened(android.view.View drawerView);

        void onDrawerClosed(android.view.View drawerView);

        void onDrawerStateChanged(int newState);
    }

    /** 桩：真实类为 androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener。 */
    public abstract static class SimpleDrawerListener implements DrawerListener {
        @Override
        public void onDrawerSlide(android.view.View drawerView, float slideOffset) {
        }

        @Override
        public void onDrawerOpened(android.view.View drawerView) {
        }

        @Override
        public void onDrawerClosed(android.view.View drawerView) {
        }

        @Override
        public void onDrawerStateChanged(int newState) {
        }
    }
}
