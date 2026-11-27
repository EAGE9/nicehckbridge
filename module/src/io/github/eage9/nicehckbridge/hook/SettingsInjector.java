package io.github.eage9.nicehckbridge.hook;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * 在 AOSP 蓝牙设备详情页注入 5 行「降噪」按钮（每行一个档位）。
 * 全部走反射，不依赖 androidx 编译期；拿不到 PreferenceScreen 时什么都不做。
 */
public final class SettingsInjector {

    private static final String KEY_PREFIX = "nicehck_anc_";
    private static final String ACTION = "io.github.eage9.nicehckbridge.CMD";
    private static final String PKG = "io.github.eage9.nicehckbridge";
    private static final String APP_PKG = "com.yuandao.nicehck";
    private static final String APP_ACT = "com.yuandao.nicehck.MainActivity";

    private static final String[] LABELS = {"关闭", "通透", "降噪", "深度降噪", "抗风噪"};
    private static final int[] MODES = {0, 1, 2, 3, 17};
    private static final String[] ICONS = {
            "ic_bt_cellphone", "ic_bt_cellphone", "ic_bt_cellphone", "ic_bt_cellphone", "ic_bt_cellphone"};

    public static volatile String lastStatus = "";
    public static volatile String segErr = "";

    private static volatile int sMode = -1, sBattery = -1, sLevel = 0;
    private static volatile boolean sConnected = false;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Object> sFragment = new WeakReference<>(null);
    private static WeakReference<SharedPreferences> sShared = new WeakReference<>(null);
    private static volatile int screenCount = -1;
    private static final List<WeakReference<Object>> sPrefs = new ArrayList<>();
    private static Runnable sTicker;

    private SettingsInjector() {}

    public static void setState(int mode, int battery, boolean connected, int level) {
        if (mode >= 0) sMode = mode;
        if (battery >= 0) sBattery = battery;
        sLevel = level;
        sConnected = connected;
    }

    public static void inject(Object fragment, SharedPreferences prefs) {
        try {
            sFragment = new WeakReference<>(fragment);
            if (prefs != null) sShared = new WeakReference<>(prefs);
            Object screen = fragment.getClass().getMethod("getPreferenceScreen").invoke(fragment);
            if (screen == null) { lastStatus = "no-screen"; return; }
            Context ctx = (Context) fragment.getClass().getMethod("requireContext").invoke(fragment);
            ClassLoader cl = fragment.getClass().getClassLoader();

            Class<?> prefCls = Class.forName("androidx.preference.Preference", true, cl);
            Class<?> groupCls = Class.forName("androidx.preference.PreferenceGroup", true, cl);
            Class<?> cbCls = Class.forName("androidx.preference.Preference$OnPreferenceClickListener", true, cl);

            sPrefs.clear();
            for (int i = 0; i < LABELS.length; i++) {
                final String key = KEY_PREFIX + i;
                Object pref = null;
                try {
                    pref = groupCls.getMethod("findPreference", CharSequence.class).invoke(screen, key);
                } catch (Throwable ignored) {
                }
                if (pref == null) {
                    pref = prefCls.getConstructor(Context.class).newInstance(ctx);
                    prefCls.getMethod("setKey", String.class).invoke(pref, key);
                    prefCls.getMethod("setPersistent", boolean.class).invoke(pref, false);
                    prefCls.getMethod("setTitle", CharSequence.class).invoke(pref, "降噪 · " + LABELS[i]);
                    prefCls.getMethod("setOrder", int.class).invoke(pref, -1000 + i);
                    final int fm = MODES[i];
                    final Object fPref = pref;
                    InvocationHandler h = (proxy, method, args) -> {
                        if ("onPreferenceClick".equals(method.getName())) {
                            sendCmd(ctx, fm);
                            MAIN.postDelayed(SettingsInjector::refreshAll, 400);
                            return Boolean.TRUE;
                        }
                        return null;
                    };
                    Object listener = Proxy.newProxyInstance(cl, new Class<?>[]{cbCls}, h);
                    prefCls.getMethod("setOnPreferenceClickListener", cbCls).invoke(pref, listener);
                    groupCls.getMethod("addPreference", prefCls).invoke(screen, pref);
                }
                sPrefs.add(new WeakReference<>(pref));
            }

            Object first = sPrefs.isEmpty() ? null : sPrefs.get(0).get();
            int cnt = -1;
            try { cnt = (Integer) groupCls.getMethod("getPreferenceCount").invoke(screen); } catch (Throwable ignored) {}
            lastStatus = "ok rows=" + sPrefs.size() + " count=" + cnt
                    + " first=" + (first != null) + " mode=" + sMode;
            refreshAll();
            startTicker();
        } catch (Throwable t) {
            lastStatus = "ERR " + t;
        }
    }

    /** 页面可能重建 PreferenceScreen 把我们的行冲掉 → 每 2 秒自检，掉了就补 */
    private static void startTicker() {
        if (sTicker != null) return;
        sTicker = new Runnable() {
            @Override public void run() {
                Object frag = sFragment.get();
                if (frag == null) { sTicker = null; return; }
                boolean needReinject = false;
                try {
                    Object screen = frag.getClass().getMethod("getPreferenceScreen").invoke(frag);
                    if (screen == null) { sTicker = null; return; }
                    Class<?> groupCls = Class.forName("androidx.preference.PreferenceGroup",
                            true, frag.getClass().getClassLoader());
                    Object p0 = groupCls.getMethod("findPreference", CharSequence.class)
                            .invoke(screen, KEY_PREFIX + 0);
                    if (p0 == null) {
                        needReinject = true;
                        lastStatus = "rows lost (screen rebuilt) -> reinject";
                    }
                } catch (Throwable ignored) {
                }
                if (needReinject) {
                    inject(frag, sShared.get());
                    return;
                }
                refreshAll();
                MAIN.postDelayed(this, 2000);
            }
        };
        MAIN.postDelayed(sTicker, 2000);
    }

    /** 刷新 5 行的摘label：当前档位打勾，其余显示电量/未连接 */
    private static void refreshAll() {
        for (int i = 0; i < sPrefs.size() && i < MODES.length; i++) {
            Object pref = sPrefs.get(i).get();
            if (pref == null) continue;
            try {
                boolean current = (sMode == MODES[i]);
                String summary;
                if (!sConnected && sBattery < 0) summary = "未连接（原道 App 未持有连接）";
                else if (current) summary = "✓ 当前" + (sBattery >= 0 ? "　电量 " + sBattery + "%" : "");
                else summary = "点击切换";
                pref.getClass().getMethod("setSummary", CharSequence.class).invoke(pref, summary);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sendCmd(Context ctx, int mode) {
        // ① 直接发给原道 App（Settings 是 system uid，能看见所有包；
        //    原道 App 未声明 <queries>，所以只有系统身份发才送得到）
        try {
            Intent d = new Intent("io.github.eage9.nicehckbridge.CMD_OUT");
            d.setPackage("com.yuandao.nicehck");
            d.putExtra("op", "noise");
            d.putExtra("mode", mode);
            d.putExtra("level", 0);
            ctx.sendBroadcast(d);
        } catch (Throwable ignored) {
        }
        // ② 同时发给模块 App（写 prefs / 便于观察）
        try {
            Intent i = new Intent(ACTION);
            i.setPackage(PKG);
            i.putExtra("op", "noise");
            i.putExtra("mode", mode);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /** 兜底：打开原道 App 的开关（系统控件只是镜像+入口） */
    public static void openApp(Context ctx) {
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.setClassName(APP_PKG, APP_ACT);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable ignored) {
        }
    }
}
