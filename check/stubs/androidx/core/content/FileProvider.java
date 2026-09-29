package androidx.core.content;

import android.content.Context;
import android.net.Uri;

import java.io.File;

/** 本地类型检查桩：真实类来自 androidx.core（appcompat 传递依赖，无需另加库）。 */
public class FileProvider {

    /** 真实实现按 file_paths.xml 的映射生成 content URI；本地只做类型检查。 */
    public static Uri getUriForFile(Context context, String authority, File file) {
        return null;
    }
}
