package androidx.work;

import java.util.Map;

/** 本地类型检查桩：真实类来自 androidx.work 库。 */
public class Data {

    public String getString(String key) {
        return null;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return defaultValue;
    }

    // 2.9.1 没有公开 hasKey()，判键存在只能走这个 Map
    public Map<String, Object> getKeyValueMap() {
        return null;
    }

    public static class Builder {

        public Builder putString(String key, String value) {
            return this;
        }

        public Builder putBoolean(String key, boolean value) {
            return this;
        }

        public Data build() {
            return new Data();
        }
    }
}
