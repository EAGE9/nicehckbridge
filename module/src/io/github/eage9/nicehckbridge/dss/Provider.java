package io.github.eage9.nicehckbridge.dss;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;

import com.android.settingslib.bluetooth.devicesettings.DeviceSetting;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem;
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig;
import com.android.settingslib.bluetooth.devicesettings.MultiTogglePreference;
import com.android.settingslib.bluetooth.devicesettings.MultiTogglePreferenceState;
import com.android.settingslib.bluetooth.devicesettings.ToggleInfo;
import io.github.eage9.nicehckbridge.CmdSender;
import io.github.eage9.nicehckbridge.EarbudsState;

import java.util.ArrayList;
import java.util.List;

/** 按 AOSP「Device Settings Provider」契约组装原生条目。 */
public final class Provider {

    private static final String TAG = "NiceHckDss";

    /** DeviceSettingId.DEVICE_SETTING_ID_ANC */
    public static final int ID_ANC = 1001;
    public static final int ID_ANC2 = 1002;

    public static final String PKG = "io.github.eage9.nicehckbridge";
    public static final String CONFIG_CLASS = "io.github.eage9.nicehckbridge.dss.ConfigService";
    public static final String PROVIDER_CLASS = "io.github.eage9.nicehckbridge.dss.SettingsService";
    public static final String PROVIDER_ACTION = "io.github.eage9.nicehckbridge.dss.SETTINGS";

    /** 5 档：关 / 通透 / 降噪 / 深度降噪 / 抗风噪 */
    public static final int[] MODES = {0, 1, 2, 3, 16, 17};
    public static final String[] LABELS = {"关闭", "通透", "降噪", "深度降噪", "实验降噪", "抗风噪"};
    private static final int[] ICONS = {
            io.github.eage9.nicehckbridge.R.drawable.anc_off,
            io.github.eage9.nicehckbridge.R.drawable.anc_transparency,
            io.github.eage9.nicehckbridge.R.drawable.anc_on,
            io.github.eage9.nicehckbridge.R.drawable.anc_deep,
            io.github.eage9.nicehckbridge.R.drawable.anc_experimental,
            io.github.eage9.nicehckbridge.R.drawable.anc_wind};

    private Provider() {}

    public static DeviceSettingsConfig buildConfig() {
        DeviceSettingItem a = item(ID_ANC);
        DeviceSettingItem b = item(ID_ANC2);
        return new DeviceSettingsConfig.Builder()
                .addMainContentItem(a)
                .addMainContentItem(b)
                .build();
    }

    private static DeviceSettingItem item(int id) {
        return new DeviceSettingItem.Builder()
                .setSettingId(id)
                .setPackageName(PKG)
                .setClassName(PROVIDER_CLASS)
                .setIntentAction(PROVIDER_ACTION)
                .setHighlighted(true)
                .setIsOptional(false)
                .setExtras(Bundle.EMPTY)
                .build();
    }

    public static List<DeviceSetting> buildSettings(Context ctx) {
        int mode = EarbudsState.noiseMode();
        int idx = indexOf(mode);

        // 单条目 5 档在真机上会丢最后一个，所以固定拆成 3+2 两个条目，保证 5 档都在
        Log.i(TAG, "buildSettings: mode=" + mode + " idx=" + idx);
        List<DeviceSetting> out = new ArrayList<>();
        out.add(one(ctx, ID_ANC, "降噪模式", 0, 3, idx));                      // 关闭/通透/降噪
        out.add(one(ctx, ID_ANC2, "降噪模式（进阶）", 3, MODES.length, idx));   // 深度/实验/抗风
        return out;
    }

    private static int singleCount(Context ctx, int idx) {
        try {
            DeviceSetting ds = one(ctx, ID_ANC, "降噪模式", 0, MODES.length, idx);
            Parcel p = Parcel.obtain();
            ds.writeToParcel(p, 0);
            p.setDataPosition(0);
            DeviceSetting back = DeviceSetting.readFromParcel(p);
            p.recycle();
            Object pref = back.getPreference();
            if (pref instanceof MultiTogglePreference) {
                return ((MultiTogglePreference) pref).getToggleInfos().size();
            }
            return -1;
        } catch (Throwable t) {
            Log.w(TAG, "singleCount failed", t);
            return -1;
        }
    }

    /** 当前模式不落在本行范围内时用它表示"无选中"（UI 是 idx==selectedIndex 比较，越界即不亮） */
    private static final int NO_SELECTION = 999;

    /** 生成一个 multi-toggle 条目：显示 [from,to) 的档位；选中项按 idx 映射 */
    private static DeviceSetting one(Context ctx, int id, String title, int from, int to, int idx) {
        int localSel = NO_SELECTION;
        if (idx >= from && idx < to) localSel = idx - from;
        MultiTogglePreference.Builder b = new MultiTogglePreference.Builder()
                .setTitle(title)
                .setState(localSel)
                .setIsActive(true)
                .setAllowChangingState(true)
                .setExtras(Bundle.EMPTY);
        for (int i = from; i < to; i++) {
            b.addToggleInfo(new ToggleInfo.Builder()
                    .setLabel(LABELS[i])
                    .setIcon(iconOf(ctx, ICONS[i]))
                    .setExtras(Bundle.EMPTY)
                    .build());
        }
        return new DeviceSetting.Builder()
                .setSettingId(id)
                .setPreference(b.build())
                .setExtras(Bundle.EMPTY)
                .build();
    }

    private static Bitmap iconOf(Context ctx, int resId) {
        int size = Math.round(24 * ctx.getResources().getDisplayMetrics().density);
        try {
            Drawable d = ctx.getDrawable(resId);
            if (d != null) {
                Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, size, size);
                d.draw(c);
                return bmp;
            }
        } catch (Throwable ignored) {
        }
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    }

    public static int indexOf(int mode) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i] == mode) return i;
        return -1;
    }

    /** 从 (settingId, 组内索引) 还原真实 mode 并下发 */
    public static void applyIndex(Context ctx, int settingId, int localIndex) {
        int base = (settingId == ID_ANC2) ? 3 : 0;
        int idx = base + localIndex;
        if (idx < 0 || idx >= MODES.length) return;
        CmdSender.setNoise(ctx, MODES[idx]);
    }
}
