package androidx.activity;

/** 本地类型检查桩：真实类来自 androidx.activity 库。 */
public abstract class OnBackPressedCallback {

    public OnBackPressedCallback(boolean enabled) {
    }

    public abstract void handleOnBackPressed();

    public final void setEnabled(boolean enabled) {
    }

    public final boolean isEnabled() {
        return true;
    }
}
