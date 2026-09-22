package androidx.activity.result;

import androidx.activity.result.contract.ActivityResultContracts;

/** 本地类型检查桩：真实类来自 androidx.activity 库。 */
public class PickVisualMediaRequest {

    public static class Builder {

        public Builder() {
        }

        public Builder setMediaType(ActivityResultContracts.PickVisualMedia.MediaType mediaType) {
            return this;
        }

        public PickVisualMediaRequest build() {
            return new PickVisualMediaRequest();
        }
    }
}