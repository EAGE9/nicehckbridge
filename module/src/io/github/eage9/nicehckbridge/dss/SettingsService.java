package io.github.eage9.nicehckbridge.dss;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.android.settingslib.bluetooth.devicesettings.DeviceInfo;
import com.android.settingslib.bluetooth.devicesettings.DeviceSetting;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingState;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsProviderServiceStatus;
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsListener;
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsProviderService;
import com.android.settingslib.bluetooth.devicesettings.MultiTogglePreferenceState;
import io.github.eage9.nicehckbridge.EarbudsState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 提供条目内容与状态，并接收用户在原生 UI 上的切换操作。 */
public class SettingsService extends Service {

    private static final String TAG = "NiceHckDss";
    private static final List<IDeviceSettingsListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static Context sApp;
    private static volatile EarbudsState.Listener sStateListener;

    private final IDeviceSettingsProviderService.Stub binder =
            new IDeviceSettingsProviderService.Stub() {
                @Override public DeviceSettingsProviderServiceStatus getServiceStatus() {
                    return new DeviceSettingsProviderServiceStatus(true, Bundle.EMPTY);
                }

                @Override public void registerDeviceSettingsListener(DeviceInfo device,
                        IDeviceSettingsListener callback) {
                    Log.i(TAG, "register listener");
                    LISTENERS.add(callback);
                    push();
                }

                @Override public void unregisterDeviceSettingsListener(DeviceInfo device,
                        IDeviceSettingsListener callback) {
                    LISTENERS.remove(callback);
                }

                @Override public void updateDeviceSettings(DeviceInfo device, DeviceSettingState params) {
                    if (params == null) return;
                    int id = params.getSettingId();
                    if (id != Provider.ID_ANC && id != Provider.ID_ANC2) return;
                    int local = -1;
                    try {
                        Object st = params.getPreferenceState();
                        if (st instanceof MultiTogglePreferenceState) {
                            local = ((MultiTogglePreferenceState) st).getState();
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "parse state failed", t);
                    }
                    Log.i(TAG, "updateDeviceSettings id=" + id + " index=" + local);
                    Context c = sApp;
                    if (c != null) Provider.applyIndex(c, id, local);
                    push();
                }
            };

    @Override public void onCreate() {
        super.onCreate();
        sApp = getApplicationContext();
        EarbudsState.bind(this);
        if (sStateListener == null) {
            sStateListener = SettingsService::push;
            EarbudsState.addListener(sStateListener);
        }
    }

    @Override public IBinder onBind(Intent intent) {
        Log.i(TAG, "SettingsService bound by " + (intent == null ? "?" : intent.getPackage()));
        EarbudsState.bind(this);
        return binder;
    }

    public static void push() {
        Context c = sApp;
        if (c == null || LISTENERS.isEmpty()) return;
        List<DeviceSetting> list = Provider.buildSettings(c);
        Log.i(TAG, "push settings: items=" + list.size());
        for (IDeviceSettingsListener l : LISTENERS) {
            try {
                l.onDeviceSettingsChanged(list);
            } catch (Throwable t) {
                LISTENERS.remove(l);
            }
        }
    }
}
