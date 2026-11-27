package io.github.eage9.nicehckbridge.dss;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.settingslib.bluetooth.devicesettings.DeviceInfo;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingConfigOptions;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfigServiceStatus;
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsConfigProviderService;
import com.android.settingslib.bluetooth.devicesettings.IGetDeviceSettingsConfigCallback;
import io.github.eage9.nicehckbridge.EarbudsState;

/** Settings 通过 BluetoothDevice 元数据找到本服务，取「有哪些原生条目」。 */
public class ConfigService extends Service {

    private static final String TAG = "NiceHckDss";

    private final IDeviceSettingsConfigProviderService.Stub binder =
            new IDeviceSettingsConfigProviderService.Stub() {
                @Override public void getDeviceSettingsConfig(DeviceInfo device,
                        IGetDeviceSettingsConfigCallback callback) throws RemoteException {
                    getDeviceSettingsConfigWithOptions(device, callback, null);
                }

                @Override public void getDeviceSettingsConfigWithOptions(DeviceInfo device,
                        IGetDeviceSettingsConfigCallback callback, DeviceSettingConfigOptions options)
                        throws RemoteException {
                    Log.i(TAG, "getDeviceSettingsConfig for "
                            + (device == null ? "?" : device.getBluetoothAddress()));
                    com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig cfg = Provider.buildConfig();
                    try {
                        java.util.List<com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem> items = cfg.getMainContentItems();
                        for (com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem it : items) {
                            Log.i(TAG, "item id=" + it.getSettingId() + " pkg=" + it.getPackageName()
                                    + " cls=" + it.getClassName() + " act=" + it.getIntentAction()
                                    + " optional=" + it.isOptional());
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "dump items failed", t);
                    }
                    callback.onResult(new DeviceSettingsConfigServiceStatus(true, Bundle.EMPTY), cfg);
                }
            };

    @Override public void onCreate() {
        super.onCreate();
        EarbudsState.bind(this);
    }

    @Override public IBinder onBind(Intent intent) {
        Log.i(TAG, "ConfigService bound by " + (intent == null ? "?" : intent.getPackage()));
        EarbudsState.bind(this);
        return binder;
    }
}
