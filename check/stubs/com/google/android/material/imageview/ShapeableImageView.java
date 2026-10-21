package com.google.android.material.imageview;

/** 本地类型检查桩：真实类来自 material 库（实际继承 AppCompatImageView，这里直接挂 ImageView）。 */
public class ShapeableImageView extends android.widget.ImageView {

    public ShapeableImageView(android.content.Context context) {
        super(context);
    }

    /** 桩：圆角形状（真实类在 material 库的 shape 包里）。 */
    public void setShapeAppearanceModel(com.google.android.material.shape.ShapeAppearanceModel model) {
    }
}
