package io.github.sdnasdas.wallswitch;

import android.content.Context;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class AppAlertDialogBuilder extends MaterialAlertDialogBuilder {

    public AppAlertDialogBuilder(Context context) {
        super(context);
    }

    @Override
    public AlertDialog create() {
        AlertDialog dialog = super.create();
        // Material 自带的消息区与列表显式启用了滚动条，主题属性无法覆盖布局属性。
        View decor = dialog.getWindow().getDecorView();
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                InsetsHelper.hideScrollbars(view);
                view.removeOnAttachStateChangeListener(this);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
            }
        });
        return dialog;
    }
}
