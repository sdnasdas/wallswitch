package androidx.media.app;

/** 本地类型检查桩：真实类来自 androidx.media 库（MediaStyle 媒体通知样式）。 */
public class NotificationCompat {

    /** 桩：真实类为 androidx.media.app.NotificationCompat.MediaStyle。 */
    public static class MediaStyle extends androidx.core.app.NotificationCompat.Style {

        public MediaStyle setMediaSession(android.support.v4.media.session.MediaSessionCompat.Token token) {
            return this;
        }

        public MediaStyle setShowActionsInCompactView(int... actions) {
            return this;
        }
    }
}
