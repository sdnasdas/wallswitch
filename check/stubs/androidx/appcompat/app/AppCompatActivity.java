package androidx.appcompat.app;

import android.os.Bundle;

/** 本地类型检查桩：真实类来自 appcompat 库。 */
public class AppCompatActivity extends android.app.Activity {

    public <I, O> androidx.activity.result.ActivityResultLauncher<I> registerForActivityResult(
            androidx.activity.result.ActivityResultContract<I, O> contract,
            androidx.activity.result.ActivityResultCallback<O> callback) {
        return null;
    }

    public void onActivityResult(int requestCode, int resultCode, Bundle data) {
    }
}