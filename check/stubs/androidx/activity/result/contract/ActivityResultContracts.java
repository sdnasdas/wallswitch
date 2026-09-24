package androidx.activity.result.contract;

import java.util.List;

/** 本地类型检查桩：真实类在 androidx.activity.result.contract 包（来自 androidx.activity 库）。 */
public class ActivityResultContracts {

    public static class PickVisualMedia
            extends androidx.activity.result.ActivityResultContract<
            androidx.activity.result.PickVisualMediaRequest, android.net.Uri> {

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

    /** SAF 目录选择（ACTION_OPEN_DOCUMENT_TREE）。 */
    public static class OpenDocumentTree
            extends androidx.activity.result.ActivityResultContract<android.net.Uri, android.net.Uri> {
    }

    /** Android 13+ 通知权限（POST_NOTIFICATIONS）等运行时权限请求契约。 */
    public static class RequestPermission
            extends androidx.activity.result.ActivityResultContract<String, Boolean> {
    }

    /** 启动任意外部 Activity（跳系统设置等）并接收返回。 */
    public static class StartActivityForResult
            extends androidx.activity.result.ActivityResultContract<
            android.content.Intent, androidx.activity.result.ActivityResult> {
    }
}