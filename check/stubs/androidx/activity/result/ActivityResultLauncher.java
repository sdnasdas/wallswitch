package androidx.activity.result;

/** 本地类型检查桩：真实类来自 androidx.activity 库。 */
public abstract class ActivityResultLauncher<I> {

    public abstract void launch(I input);
}