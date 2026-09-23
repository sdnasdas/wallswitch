package com.google.android.material.bottomnavigation;

import android.view.MenuItem;

/** 本地类型检查桩：真实类来自 material 库。 */
public class BottomNavigationView extends android.view.View {

    public BottomNavigationView(android.content.Context context) {
        super(context);
    }

    public void setOnItemSelectedListener(OnItemSelectedListener listener) {
    }

    /** 桩：真实接口为 com.google.android.material.navigation.NavigationBarView.OnItemSelectedListener。 */
    public interface OnItemSelectedListener {
        boolean onNavigationItemSelected(MenuItem item);
    }
}
