/*
 * Copyright (C) 2025 The Android Open Source Project
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


/** A data class representing the additional options for the device settings config. */
public class DeviceSettingConfigOptions implements Parcelable {
    private static final String OPTIONAL_ITEMS_KEY = "optionalItems";
    private final Bundle mOptions;

    DeviceSettingConfigOptions(Bundle options) {
        mOptions = options;
    }

    /** Read a {@link DeviceSettingConfigOptions} instance from {@link Parcel} */
    public static DeviceSettingConfigOptions readFromParcel(Parcel in) {
        Bundle options = in.readBundle(Bundle.class.getClassLoader());
        return new DeviceSettingConfigOptions(options);
    }

    public static final Creator<DeviceSettingConfigOptions> CREATOR =
            new Creator<>() {
                @Override
                public DeviceSettingConfigOptions createFromParcel(Parcel in) {
                    return readFromParcel(in);
                }

                @Override
                public DeviceSettingConfigOptions[] newArray(int size) {
                    return new DeviceSettingConfigOptions[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBundle(mOptions);
    }

    public boolean isOptionalItemSupported() {
        return mOptions.getBoolean(OPTIONAL_ITEMS_KEY, false);
    }

    /** Builder class for {@link DeviceSettingConfigOptions}. */
    public static final class Builder {
        private final Bundle mOptions = new Bundle();

        /**
         * Sets whether optional items are supported.
         *
         * @return Returns the Builder object.
         */
        public Builder setOptionalItemSupported(boolean isOptionalItemSupported) {
            mOptions.putBoolean(OPTIONAL_ITEMS_KEY, isOptionalItemSupported);
            return this;
        }

        /**
         * Builds the {@link DeviceSettingConfigOptions} object.
         *
         * @return Returns the built {@link DeviceSettingConfigOptions} object.
         */
        public DeviceSettingConfigOptions build() {
            return new DeviceSettingConfigOptions(mOptions);
        }
    }
}
