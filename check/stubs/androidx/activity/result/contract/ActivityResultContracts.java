package androidx.activity.result.contract;

import java.util.List;

/** 本地类型检查桩：真实类在 androidx.activity.result.contract 包（来自 androidx.activity 库）。 */
public class ActivityResultContracts {

    public static class PickVisualMedia {

        public static class MediaType {
        }

        /** 对应 Kotlin object ImageOnly，Java 侧通过 INSTANCE 访问。 */
        public static class ImageOnly extends MediaType {

            public static final ImageOnly INSTANCE = new ImageOnly();
        }
    }

    public static class PickMultipleVisualMedia
            extends androidx.activity.result.ActivityResultContract<
            androidx.activity.result.PickVisualMediaRequest, List<android.net.Uri>> {

        public PickMultipleVisualMedia(int maxItems) {
        }
    }
}