package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/** 配置查询结果状态：success + extras。 */
public class DeviceSettingsConfigServiceStatus implements Parcelable {
    private final boolean mSuccess;
    private final Bundle mExtras;

    public DeviceSettingsConfigServiceStatus(boolean success, Bundle extras) {
        mSuccess = success;
        mExtras = extras == null ? Bundle.EMPTY : extras;
    }

    public static DeviceSettingsConfigServiceStatus readFromParcel(Parcel in) {
        boolean success = in.readBoolean();
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new DeviceSettingsConfigServiceStatus(success, extras);
    }

    public static final Creator<DeviceSettingsConfigServiceStatus> CREATOR = new Creator<>() {
        @Override public DeviceSettingsConfigServiceStatus createFromParcel(Parcel in) { return readFromParcel(in); }
        @Override public DeviceSettingsConfigServiceStatus[] newArray(int size) { return new DeviceSettingsConfigServiceStatus[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mSuccess);
        dest.writeBundle(mExtras);
    }

    public boolean isSuccess() { return mSuccess; }
}
