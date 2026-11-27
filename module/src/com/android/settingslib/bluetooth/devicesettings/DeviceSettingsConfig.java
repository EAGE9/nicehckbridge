package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/** Java 版 DeviceSettingsConfig（与 SettingsLib 的 Kotlin 版 parcel 布局逐字节一致）。 */
public class DeviceSettingsConfig implements Parcelable {
    private final List<DeviceSettingItem> mMainContentItems;
    private final List<DeviceSettingItem> mMoreSettingsItems;
    private final DeviceSettingItem mMoreSettingsHelpItem;
    private final Bundle mExtras;

    DeviceSettingsConfig(List<DeviceSettingItem> main, List<DeviceSettingItem> more,
                        DeviceSettingItem help, Bundle extras) {
        mMainContentItems = main;
        mMoreSettingsItems = more;
        mMoreSettingsHelpItem = help;
        mExtras = extras;
    }

    public static DeviceSettingsConfig readFromParcel(Parcel in) {
        ArrayList<DeviceSettingItem> main = new ArrayList<>();
        in.readTypedList(main, DeviceSettingItem.CREATOR);
        ArrayList<DeviceSettingItem> more = new ArrayList<>();
        in.readTypedList(more, DeviceSettingItem.CREATOR);
        DeviceSettingItem help = in.readParcelable(DeviceSettingItem.class.getClassLoader());
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new DeviceSettingsConfig(main, more, help, extras == null ? Bundle.EMPTY : extras);
    }

    public static final Creator<DeviceSettingsConfig> CREATOR = new Creator<>() {
        @Override public DeviceSettingsConfig createFromParcel(Parcel in) { return readFromParcel(in); }
        @Override public DeviceSettingsConfig[] newArray(int size) { return new DeviceSettingsConfig[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedList(mMainContentItems);
        dest.writeTypedList(mMoreSettingsItems);
        dest.writeParcelable(mMoreSettingsHelpItem, flags);
        dest.writeBundle(mExtras);
    }

    public List<DeviceSettingItem> getMainContentItems() { return mMainContentItems; }
    public List<DeviceSettingItem> getMoreSettingsItems() { return mMoreSettingsItems; }
    public DeviceSettingItem getMoreSettingsHelpItem() { return mMoreSettingsHelpItem; }
    public Bundle getExtras() { return mExtras; }

    /** Builder */
    public static final class Builder {
        private List<DeviceSettingItem> mMain = new ArrayList<>();
        private List<DeviceSettingItem> mMore = new ArrayList<>();
        private DeviceSettingItem mHelp;
        private Bundle mExtras = Bundle.EMPTY;

        public Builder addMainContentItem(DeviceSettingItem item) { mMain.add(item); return this; }
        public Builder setMainContentItems(List<DeviceSettingItem> items) { mMain = items; return this; }
        public Builder setMoreSettingsItems(List<DeviceSettingItem> items) { mMore = items; return this; }
        public Builder setMoreSettingsHelpItem(DeviceSettingItem item) { mHelp = item; return this; }
        public Builder setExtras(Bundle extras) { mExtras = extras; return this; }

        public DeviceSettingsConfig build() {
            return new DeviceSettingsConfig(mMain, mMore, mHelp, mExtras);
        }
    }
}
