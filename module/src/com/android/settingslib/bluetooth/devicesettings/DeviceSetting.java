/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.bluetooth.devicesettings;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;


import java.util.Objects;

/** A data class representing a device setting item in bluetooth device details page. */
public final class DeviceSetting implements Parcelable {
    private final int mSettingId;
    private final DeviceSettingPreference mPreference;
    private final Bundle mExtras;

    DeviceSetting(
            int settingId, DeviceSettingPreference preference, Bundle extras) {
        validate(preference);
        mSettingId = settingId;
        mPreference = preference;
        mExtras = extras;
    }

    private static void validate(DeviceSettingPreference preference) {
        if (Objects.isNull(preference)) {
            throw new IllegalArgumentException("Preference must be set");
        }
    }

    /** Read a {@link DeviceSetting} instance from {@link Parcel} */
    public static DeviceSetting readFromParcel(Parcel in) {
        int settingId = in.readInt();
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        DeviceSettingPreference settingPreference = DeviceSettingPreference.readFromParcel(in);
        return new DeviceSetting(settingId, settingPreference, extras);
    }

    public static final Creator<DeviceSetting> CREATOR =
            new Creator<>() {
                @Override
                public DeviceSetting createFromParcel(Parcel in) {
                    return readFromParcel(in);
                }

                @Override
                public DeviceSetting[] newArray(int size) {
                    return new DeviceSetting[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSettingId);
        dest.writeBundle(mExtras);
        mPreference.writeToParcel(dest, flags);
    }

    /** Builder class for {@link DeviceSetting}. */
    public static final class Builder {
        private int mSettingId;
        private DeviceSettingPreference mPreference;
        private Bundle mExtras = Bundle.EMPTY;

        public Builder() {}

        /**
         * Sets the setting ID, as defined by IntDef {@link DeviceSettingId}.
         *
         * @return Returns the Builder object.
         */
        public Builder setSettingId(int settingId) {
            mSettingId = settingId;
            return this;
        }

        /**
         * Sets the setting preference.
         *
         * @return Returns the Builder object.
         */
        public Builder setPreference(DeviceSettingPreference settingPreference) {
            mPreference = settingPreference;
            return this;
        }

        /**
         * Sets the extras bundle.
         *
         * @return Returns the Builder object.
         */
        public Builder setExtras(Bundle extras) {
            mExtras = extras;
            return this;
        }

        /** Build the object. */
        public DeviceSetting build() {
            return new DeviceSetting(mSettingId, mPreference, mExtras);
        }
    }

    /**
     * Gets the setting ID as defined by IntDef {@link DeviceSettingId}.
     *
     * @return Returns the setting ID.
     */
    public int getSettingId() {
        return mSettingId;
    }

    /**
     * Gets the setting preference.
     *
     * @return Returns the setting preference.
     */
    public DeviceSettingPreference getPreference() {
        return mPreference;
    }

    /**
     * Gets the extras Bundle.
     *
     * @return Returns a Bundle object.
     */
    public Bundle getExtras() {
        return mExtras;
    }
}
