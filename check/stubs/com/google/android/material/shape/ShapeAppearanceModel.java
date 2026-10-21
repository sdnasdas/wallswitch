package com.google.android.material.shape;

/** 本地类型检查桩：真实类来自 material 库。 */
public class ShapeAppearanceModel {

    public static Builder builder() {
        return new Builder();
    }

    /** 桩：真实 Builder 是同包的 ShapeAppearanceModel.Builder。 */
    public static final class Builder {

        public Builder setAllCornerSizes(float size) {
            return this;
        }

        public ShapeAppearanceModel build() {
            return new ShapeAppearanceModel();
        }
    }
}
