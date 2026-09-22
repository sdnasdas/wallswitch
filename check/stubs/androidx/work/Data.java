package androidx.work;

/** 本地类型检查桩：真实类来自 androidx.work 库。 */
public class Data {

    public String getString(String key) {
        return null;
    }

    public static class Builder {

        public Builder putString(String key, String value) {
            return this;
        }

        public Data build() {
            return new Data();
        }
    }
}