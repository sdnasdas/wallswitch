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

        public Builder setOnDismissListener(DialogInterface.OnDismissListener listener) {
            return this;
        }

        public Builder setOnCancelListener(DialogInterface.OnCancelListener listener) {
            return this;
        }

        public AlertDialog show() {
            return new AlertDialog();
        }
    }
}