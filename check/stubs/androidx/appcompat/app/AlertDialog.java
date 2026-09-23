package androidx.appcompat.app;

import android.content.DialogInterface;
import android.view.View;

/** 本地类型检查桩：真实类来自 appcompat 库。 */
public class AlertDialog {

    public static class Builder {

        public Builder(android.content.Context context) {
        }

        public Builder setTitle(int titleId) {
            return this;
        }

        public Builder setTitle(CharSequence title) {
            return this;
        }

        public Builder setMessage(int messageId) {
            return this;
        }

        public Builder setMessage(CharSequence message) {
            return this;
        }

        public Builder setView(View view) {
            return this;
        }

        public Builder setPositiveButton(int textId, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setNegativeButton(int textId, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setNeutralButton(int textId, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setOnDismissListener(DialogInterface.OnDismissListener listener) {
            return this;
        }

        public Builder setOnCancelListener(DialogInterface.OnCancelListener listener) {
            return this;
        }

        public AlertDialog show() {
            return new AlertDialog();
        }

        public AlertDialog create() {
            return new AlertDialog();
        }
    }

    public void show() {
    }

    /** 真实 AlertDialog 继承自 Dialog，具备该方法（弹窗回填前用于判断是否已关闭）。 */
    public boolean isShowing() {
        return false;
    }

    public void dismiss() {
    }
}
