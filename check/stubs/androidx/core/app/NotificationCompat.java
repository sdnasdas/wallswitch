package androidx.core.app;

/** 本地类型检查桩：真实类来自 androidx.core 库（经 appcompat 传递）。 */
public class NotificationCompat {

    public static final String CATEGORY_STATUS = "status";
    public static final int VISIBILITY_PUBLIC = 1;

    public static class Builder {

        public Builder(android.content.Context context, String channelId) {
        }

        public Builder setSmallIcon(int icon) {
            return this;
        }

        public Builder setContentTitle(CharSequence title) {
            return this;
        }

        public Builder setContentText(CharSequence text) {
            return this;
        }

        public Builder setLargeIcon(android.graphics.Bitmap bitmap) {
            return this;
        }

        public Builder setOngoing(boolean ongoing) {
            return this;
        }

        public Builder setOnlyAlertOnce(boolean onlyAlertOnce) {
            return this;
        }

        public Builder setCategory(String category) {
            return this;
        }

        public Builder setVisibility(int visibility) {
            return this;
        }

        public Builder setContentIntent(android.app.PendingIntent intent) {
            return this;
        }

        public Builder addAction(Action action) {
            return this;
        }

        public Builder setUsesChronometer(boolean usesChronometer) {
            return this;
        }

        public Builder setChronometerCountDown(boolean countDown) {
            return this;
        }

        public Builder setWhen(long when) {
            return this;
        }

        public Builder setShowWhen(boolean show) {
            return this;
        }

        public Builder setSubText(CharSequence text) {
            return this;
        }

        public Builder setStyle(Style style) {
            return this;
        }

        public android.app.Notification build() {
            return null;
        }
    }

    /** 桩：真实类为 NotificationCompat.Style（各种样式类的基类）。 */
    public abstract static class Style {
    }

    /** 桩：真实类为 NotificationCompat.Action。 */
    public static class Action {

        public static class Builder {

            public Builder(int icon, CharSequence title, android.app.PendingIntent intent) {
            }

            public Action build() {
                return null;
            }
        }
    }
}
