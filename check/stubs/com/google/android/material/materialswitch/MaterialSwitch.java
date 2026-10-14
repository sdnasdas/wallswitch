package com.google.android.material.materialswitch;

/** 本地类型检查桩：真实类来自 material 库（继承链 SwitchCompat → CompoundButton，这里直接挂 CompoundButton）。 */
public class MaterialSwitch extends android.widget.CompoundButton {

    public MaterialSwitch(android.content.Context context) {
        super(context);
    }

    @Override
    public void setChecked(boolean checked) {
    }
}
