package com.google.android.material.card;

/** 本地类型检查桩：真实类来自 material 库。 */
public class MaterialCardView extends android.widget.FrameLayout {

    public MaterialCardView(android.content.Context context) {
        super(context);
    }

    /** 桩：真实方法在 MaterialCardView 上，改的是卡片自己的底色（不是 View 的 background）。 */
    public void setCardBackgroundColor(int color) {
    }

    /** 桩：描边颜色（配合 app:strokeWidth 生效）。 */
    public void setStrokeColor(int color) {
    }
}
