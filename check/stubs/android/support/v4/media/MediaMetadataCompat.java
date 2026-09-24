package android.support.v4.media;

/** 本地类型检查桩：真实类来自 androidx.media 库。 */
public class MediaMetadataCompat {

    public static final String METADATA_KEY_DURATION = "android.media.metadata.DURATION";

    public MediaMetadataCompat() {
    }

    /** 桩：真实类为 MediaMetadataCompat.Builder。 */
    public static class Builder {

        public Builder() {
        }

        public Builder putLong(String key, long value) {
            return this;
        }

        public MediaMetadataCompat build() {
            return null;
        }
    }
}
