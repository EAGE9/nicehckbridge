package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** Java 版 DeviceSettingItem（与 Kotlin 版 parcel 布局一致）。 */
public class DeviceSettingItem implements Parcelable {
    private static final String GROUP_INDEX_KEY = "groupIndex";
    private static final String IS_OPTIONAL_KEY = "isOptional";

    private final int mSettingId;
    private final String mPackageName;
    private final String mClassName;
    private final String mIntentAction;
    private final boolean mHighlighted;
    private final String mPreferenceKey;
    private final Bundle mExtras;

    DeviceSettingItem(int settingId, String packageName, String className, String intentAction,
                      boolean highlighted, String preferenceKey, Integer groupIndex,
                      boolean isOptional, Bundle extras) {
        mSettingId = settingId;
        mPackageName = packageName;
        mClassName = className;
        mIntentAction = intentAction;
        mHighlighted = highlighted;
        mPreferenceKey = preferenceKey;
        Bundle b = new Bundle(extras);
        if (groupIndex != null) b.putInt(GROUP_INDEX_KEY, groupIndex);
        b.putBoolean(IS_OPTIONAL_KEY, isOptional);
        mExtras = b;
    }

    public static DeviceSettingItem readFromParcel(Parcel in) {
        int settingId = in.readInt();
        String pkg = in.readString();
        String cls = in.readString();
        String action = in.readString();
        boolean highlighted = in.readBoolean();
        String prefKey = in.readString();
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        if (extras == null) extras = Bundle.EMPTY;
        int gi = extras.getInt(GROUP_INDEX_KEY, -1);
        boolean optional = extras.getBoolean(IS_OPTIONAL_KEY, false);
        return new DeviceSettingItem(settingId, pkg, cls, action, highlighted, prefKey,
                gi == -1 ? null : gi, optional, extras);
    }

    public static final Creator<DeviceSettingItem> CREATOR = new Creator<>() {
        @Override public DeviceSettingItem createFromParcel(Parcel in) { return readFromParcel(in); }
        @Override public DeviceSettingItem[] newArray(int size) { return new DeviceSettingItem[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSettingId);
        dest.writeString(mPackageName);
        dest.writeString(mClassName);
        dest.writeString(mIntentAction);
        dest.writeBoolean(mHighlighted);
        dest.writeString(mPreferenceKey);
        dest.writeBundle(mExtras);
    }

    public int getSettingId() { return mSettingId; }
    public String getPackageName() { return mPackageName; }
    public String getClassName() { return mClassName; }
    public String getIntentAction() { return mIntentAction; }
    public boolean isOptional() { return mExtras.getBoolean(IS_OPTIONAL_KEY, false); }

    public static final class Builder {
        private int mSettingId;
        private String mPackageName;
        private String mClassName;
        private String mIntentAction;
        private boolean mHighlighted;
        private String mPreferenceKey;
        private Integer mGroupIndex;
        private boolean mIsOptional;
        private Bundle mExtras = Bundle.EMPTY;

        public Builder setSettingId(int id) { mSettingId = id; return this; }
        public Builder setPackageName(String v) { mPackageName = v; return this; }
        public Builder setClassName(String v) { mClassName = v; return this; }
        public Builder setIntentAction(String v) { mIntentAction = v; return this; }
        public Builder setHighlighted(boolean v) { mHighlighted = v; return this; }
        public Builder setPreferenceKey(String v) { mPreferenceKey = v; return this; }
        public Builder setGroupIndex(Integer v) { mGroupIndex = v; return this; }
        public Builder setIsOptional(boolean v) { mIsOptional = v; return this; }
        public Builder setExtras(Bundle v) { mExtras = v; return this; }

        public DeviceSettingItem build() {
            return new DeviceSettingItem(mSettingId, mPackageName, mClassName, mIntentAction,
                    mHighlighted, mPreferenceKey, mGroupIndex, mIsOptional, mExtras);
        }
    }
}
