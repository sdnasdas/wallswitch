package android.support.v4.media.session;

import android.support.v4.media.MediaMetadataCompat;

/** 本地类型检查桩：真实类来自 androidx.media 库（MediaSessionCompat，供 MediaStyle 挂 token）。 */
public class MediaSessionCompat {

    public MediaSessionCompat(android.content.Context context, String tag) {
    }

    public Token getSessionToken() {
        return new Token();
    }

    public void setMetadata(MediaMetadataCompat metadata) {
    }

    public void setPlaybackState(PlaybackStateCompat state) {
    }

    /** 桩：真实类为 MediaSessionCompat.Token。 */
    public static class Token {
    }
}
