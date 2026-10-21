package com.google.android.material.dialog;

/** 本地类型检查桩：真实类来自 material 库（M3 圆角弹窗）。 */
public class MaterialAlertDialogBuilder extends androidx.appcompat.app.AlertDialog.Builder {

    public MaterialAlertDialogBuilder(android.content.Context context) {
        super(context);
    }

    /** 桩：真实方法换弹窗背景（缩略图小窗靠透明背景把窗压到图本身大小）。 */
    public MaterialAlertDialogBuilder setBackground(android.graphics.drawable.Drawable background) {
        return this;
    }

    /** 桩：真实方法改背景内缩（四边都要，弹窗默认左右 24dp）。 */
    public MaterialAlertDialogBuilder setBackgroundInsetStart(int inset) {
        return this;
    }

    public MaterialAlertDialogBuilder setBackgroundInsetTop(int inset) {
        return this;
    }

    public MaterialAlertDialogBuilder setBackgroundInsetEnd(int inset) {
        return this;
    }

    public MaterialAlertDialogBuilder setBackgroundInsetBottom(int inset) {
        return this;
    }
}
