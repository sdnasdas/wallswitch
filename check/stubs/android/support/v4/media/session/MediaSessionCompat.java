package android.support.v4.media.session;

/** 本地类型检查桩：真实类来自 androidx.media 库（MediaSessionCompat，供 MediaStyle 挂 token）。 */
public class MediaSessionCompat {

    public MediaSessionCompat(android.content.Context context, String tag) {
    }

    public Token getSessionToken() {
        return new Token();
    }

    /** 桩：真实类为 MediaSessionCompat.Token。 */
    public static class Token {
    }
}
