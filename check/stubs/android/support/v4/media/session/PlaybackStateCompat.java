package android.support.v4.media.session;

/** 本地类型检查桩：真实类来自 androidx.media 库。 */
public class PlaybackStateCompat {

    public static final int STATE_NONE = 0;
    public static final int STATE_PLAYING = 3;
    public static final long ACTION_PLAY = 1L << 2;
    public static final long ACTION_PAUSE = 1L << 3;
    public static final long ACTION_SEEK_TO = 1L << 8;

    public PlaybackStateCompat() {
    }

    /** 桩：真实类为 PlaybackStateCompat.Builder。 */
    public static class Builder {

        public Builder() {
        }

        public Builder setActions(long actions) {
            return this;
        }

        public Builder setState(int state, long position, float playbackSpeed) {
            return this;
        }

        public PlaybackStateCompat build() {
            return null;
        }
    }
}
