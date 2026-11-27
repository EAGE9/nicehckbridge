package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** 服务状态：enabled + extras。 */
public class DeviceSettingsProviderServiceStatus implements Parcelable {
    private final boolean mEnabled;
    private final Bundle mExtras;

    public DeviceSettingsProviderServiceStatus(boolean enabled, Bundle extras) {
        mEnabled = enabled;
        mExtras = extras == null ? Bundle.EMPTY : extras;
    }

    public static DeviceSettingsProviderServiceStatus readFromParcel(Parcel in) {
        boolean enabled = in.readBoolean();
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new DeviceSettingsProviderServiceStatus(enabled, extras);
    }

    public static final Creator<DeviceSettingsProviderServiceStatus> CREATOR = new Creator<>() {
        @Override public DeviceSettingsProviderServiceStatus createFromParcel(Parcel in) { return readFromParcel(in); }
        @Override public DeviceSettingsProviderServiceStatus[] newArray(int size) { return new DeviceSettingsProviderServiceStatus[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mEnabled);
        dest.writeBundle(mExtras);
    }

    public boolean isEnabled() { return mEnabled; }
}
