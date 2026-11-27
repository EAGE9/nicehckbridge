package io.github.eage9.nicehckbridge.hook;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * LSPosed (libxposed API 102) 侧：把原道 OriGin 接进 AOSP 原生蓝牙通道。
 *
 * 1) hook CachedBluetoothDevice#getBatteryLevel()          → 原生电量
 * 2) hook BluetoothDevice#getMetadata(int)                 → 伪造 Fast Pair 定制字段（发现通道 A）
 * 3) hook BluetoothUtils#getFastPairCustomizedField(...)   → 直接返回端点（发现通道 B）
 * 4) hook BluetoothDetailsConfigurableFragment#onAttach    → 诊断：设备详情页是否打开
 */
public final class NiceHckModule extends XposedModule {

    private static final String TAG = "NiceHckBridge";
    private static final String PREFS = "nicehck_state";

    private static final String KEY_PKG = "DEVICE_SETTINGS_CONFIG_PACKAGE_NAME";
    private static final String KEY_CLASS = "DEVICE_SETTINGS_CONFIG_CLASS";
    private static final String KEY_ACTION = "DEVICE_SETTINGS_CONFIG_ACTION";
    private static final String OUR_PKG = "io.github.eage9.nicehckbridge";
    private static final String OUR_CONFIG_CLASS = "io.github.eage9.nicehckbridge.dss.ConfigService";
    private static final String OUR_ACTION = "io.github.eage9.nicehckbridge.dss.CONFIG";
    /** SettingsLib: BluetoothUtils.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS */
    private static final int META_FAST_PAIR_FIELDS = 25;
    private static volatile long lastBatteryLog = 0;

    /* Settings 进程内的状态缓存（由原道 App 进程的 STATE 广播填充） */
    private static volatile int sL = -1, sR = -1, sC = -1, sMode = -1, sNoiseLevel = 0;
    private static volatile boolean sConn = false;
    private static volatile String sMac = "";
    private static volatile boolean sStateRcv = false;
    private static volatile int sBatteryCache = -1;
    private static volatile android.content.Context sCtx;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(4, TAG, "module v" + io.github.eage9.nicehckbridge.BuildInfo.VERSION_NAME + " loaded api=" + getApiVersion() + " framework=" + getFrameworkName()
                + " " + getFrameworkVersion() + " proc=" + param.getProcessName());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        if ("com.yuandao.nicehck".equals(pkg)) {
            AppHooks.onReady(this);
            log(4, TAG, "onPackageReady -> app ready hook done");
        } else if ("com.android.settings".equals(pkg)) {
            ensureStateReceiver(currentApp());
            log(4, TAG, "onPackageReady -> settings state receiver: " + sStateRcv);
        }
    }

    private static android.content.Context currentApp() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object o = m.invoke(null);
            return o instanceof android.content.Context ? (android.content.Context) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if ("com.yuandao.nicehck".equals(pkg)) {
            log(4, TAG, "installing middleware into nicehck app");
            try {
                AppHooks.install(this, param);
            } catch (Throwable t) {
                log(5, TAG, "app hook failed", t);
            }
            return;
        }
        if (!"com.android.settings".equals(pkg)) return;
        ClassLoader cl = param.getDefaultClassLoader();

        // --- 1) 电量 ---
        try {
            Class<?> cls = cl.loadClass("com.android.settingslib.bluetooth.CachedBluetoothDevice");
            Method m = cls.getDeclaredMethod("getBatteryLevel");
            m.setAccessible(true);
            Compat.id(hook(m), "batteryLevel").intercept(chain -> {
                int v = batteryFor(chain.getThisObject());
                if (v >= 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastBatteryLog > 10000) {
                        lastBatteryLog = now;
                        log(4, TAG, "batteryHook -> " + v + "%");
                    }
                    return v;
                }
                return chain.proceed();
            });
            log(4, TAG, "hooked CachedBluetoothDevice#getBatteryLevel");
        } catch (Throwable t) {
            log(5, TAG, "battery hook failed", t);
        }

        // --- 2) 伪装成 Fast Pair 设备：拦截 BluetoothDevice 元数据（key 25 = Fast Pair 定制字段）---
        //     不依赖 SettingsLib 的哪一份副本，因为 getMetadata 是 framework 方法。
        try {
            Method gm = BluetoothDevice.class.getDeclaredMethod("getMetadata", int.class);
            gm.setAccessible(true);
            Compat.id(hook(gm), "metaData").intercept(chain -> {
                Object r = chain.proceed();
                try {
                    Object dev = chain.getThisObject();
                    Object key = chain.getArg(0);
                    if (dev instanceof BluetoothDevice && key instanceof Integer
                            && ((Integer) key) == META_FAST_PAIR_FIELDS) {
                        BluetoothDevice d = (BluetoothDevice) dev;
                        String old = r instanceof byte[] ? new String((byte[]) r, StandardCharsets.UTF_8) : "";
                        SharedPreferences p = remotePrefs();
                        String want = p == null ? "" : p.getString("mac", "");
                        boolean hit = matches(d, want) || (old.contains("ACTION_BIND_DEVICE_SETTINGS_CONFIG_PROVIDER") && d.getBondState() == BluetoothDevice.BOND_BONDED);
                        log(4, TAG, "meta25 dev=" + d.getName() + "/" + d.getAddress()
                                + " want=" + want + " oldLen=" + old.length() + " hit=" + hit);
                        if (hit) {
                            return ourMeta().getBytes(StandardCharsets.UTF_8);
                        }
                    }
                } catch (Throwable t) {
                    log(5, TAG, "meta25 err", t);
                }
                return r;
            });
            log(4, TAG, "hooked BluetoothDevice#getMetadata(int)");
        } catch (Throwable t) {
            log(5, TAG, "metaData hook failed", t);
        }

        // --- 3) 发现通道 B：在【所有】classloader 上挂取字段函数（Settings 里有双副本）---
        hookFastPairField(cl);
        hookFastPairField(ClassLoader.getSystemClassLoader());
        hookFastPairField(NiceHckModule.class.getClassLoader());

        // --- 4) 诊断：设备详情页是否打开（正确 hook 点：基类的 onAttach(Context)） ---
        try {
            Class<?> base = cl.loadClass("com.android.settings.bluetooth.BluetoothDetailsConfigurableFragment");
            Method att = base.getDeclaredMethod("onAttach", Context.class);
            att.setAccessible(true);
            Compat.id(hook(att), "pageAttach").intercept(chain -> {
                Object r = chain.proceed();
                try {
                    log(4, TAG, "pageOpen " + chain.getThisObject().getClass().getSimpleName());
                    Object ctxArg = chain.getArg(0);
                    if (ctxArg instanceof android.content.Context) ensureStateReceiver((android.content.Context) ctxArg);
                } catch (Throwable ignored) {
                }
                return r;
            });
            log(4, TAG, "hooked BluetoothDetailsConfigurableFragment#onAttach");
        } catch (Throwable t) {
            log(5, TAG, "page diag hook failed: " + t);
        }

        // --- 4.5) 原生通道：GMS 提供 config，我们往里追加 ANC 条目 ---
        installConfigInject(cl);

        // --- 4.30) 往 config 里追加我们的条目（决定页面显示什么）---
        installConfigPatch(cl);

        // --- 4.35) 页面重建时补注入 ---
        installScreenRebuildHook(cl);

        // --- 4.4) 防止我们的行被页面隐藏 ---
        installVisibilityVeto(cl);

        // --- 4.5b) EndPoint 探针 ---
        installEndpointProbe(cl);

        // --- 4.6) 原生单边电量：往 BluetoothDevice 写 untethered 元数据 ---
        startBatteryMetaPusher();

        // --- 4.7) 让 Settings 用我们的 config（原生降噪分段控件）---
        installEndpointOverride(cl);

        // --- 5) 兜底条目 ---
        installFallbackRow(cl, param);
    }

    private static String ourMeta() {
        return "<" + KEY_PKG + ">" + OUR_PKG + "</" + KEY_PKG + ">"
                + "<" + KEY_CLASS + ">" + OUR_CONFIG_CLASS + "</" + KEY_CLASS + ">"
                + "<" + KEY_ACTION + ">" + OUR_ACTION + "</" + KEY_ACTION + ">";
    }

    /* ================= 原生单边电量 ================= */
    private static final int META_UNTETHERED = 6;
    private static final int META_LEFT = 10;
    private static final int META_RIGHT = 11;
    private static final int META_CASE = 12;
    private static final int META_MAIN = 18;

    private static volatile boolean sPusherStarted = false;

    /** 注册 STATE 广播接收器（在 Settings 进程用，替换掉失效的 remote prefs） */
    private void ensureStateReceiver(android.content.Context ctx) {
        if (ctx != null) sCtx = ctx.getApplicationContext();
        if (sStateRcv || sCtx == null) return;
        try {
            android.content.IntentFilter f = new android.content.IntentFilter("io.github.eage9.nicehckbridge.STATE");
            android.content.BroadcastReceiver rcv = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                    if (i == null) return;
                    sConn = i.getBooleanExtra("connected", false);
                    int b = i.getIntExtra("battery", -1);
                    int l = i.getIntExtra("bL", -1), r = i.getIntExtra("bR", -1), cc = i.getIntExtra("bC", -1);
                    if (!sConn) {
                        // 掉线：清掉缓存，界面与 metadata 都不该再显示旧电量
                        sL = sR = sC = -1;
                        sBatteryCache = -1;
                        sMode = -1;
                    } else {
                        if (l >= 0) sL = l;
                        if (r >= 0) sR = r;
                        if (cc >= 0) sC = cc;
                        if (l < 0 && r < 0 && b >= 0) { sL = sR = b; }
                        if (b >= 0) sBatteryCache = b;
                    }
                    int m = i.getIntExtra("mode", -1);
                    if (m >= 0) sMode = m;
                    String mac = i.getStringExtra("addr");
                    if (mac != null && !mac.isEmpty()) sMac = mac;
                    SettingsInjector.setState(m, b, sConn, i.getIntExtra("level", 0));
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                sCtx.registerReceiver(rcv, f, android.content.Context.RECEIVER_EXPORTED);
            } else {
                sCtx.registerReceiver(rcv, f);
            }
            sStateRcv = true;
            log(4, TAG, "STATE receiver registered in settings process");
        } catch (Throwable t) {
            log(5, TAG, "STATE receiver failed: " + t);
        }
    }

    private void startBatteryMetaPusher() {
        if (sPusherStarted) return;
        sPusherStarted = true;
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(new Runnable() {
            @Override public void run() {
                try {
                    if (!sStateRcv) ensureStateReceiver(sCtx != null ? sCtx : currentApp());
                    pushBatteryFromPrefs();
                } catch (Throwable ignored) {
                }
                h.postDelayed(this, 2000);
            }
        });
        log(4, TAG, "battery metadata pusher started");
    }

    private void pushBatteryFromPrefs() {
        // 掉线：把之前写进 BluetoothDevice 的电量元数据清掉，系统就不会再显示陈旧电量
        if (!sConn) {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ad != null) {
                for (BluetoothDevice d : ad.getBondedDevices()) {
                    if (matches(d, sMac)) clearMeta(d);
                }
            }
            return;
        }
        int l = sL, r = sR, c = sC;
        String want = sMac;
        if (l < 0 && r < 0 && c < 0) {
            SharedPreferences p = remotePrefs();
            if (p != null) {
                l = p.getInt("bL", -1); r = p.getInt("bR", -1); c = p.getInt("bC", -1);
                if (want.isEmpty()) want = p.getString("mac", "");
            }
        }
        if (l < 0 && r < 0 && c < 0) return;
        android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        if (a == null) return;
        for (BluetoothDevice d : a.getBondedDevices()) {
            if (!matches(d, want)) continue;
            setMeta(d, META_UNTETHERED, "true");
            if (l >= 0) setMeta(d, META_LEFT, String.valueOf(l));
            if (r >= 0) setMeta(d, META_RIGHT, String.valueOf(r));
            if (c >= 0) setMeta(d, META_CASE, String.valueOf(c));
            int overall = -1;
            if (l >= 0 && r >= 0) overall = Math.min(l, r);
            else if (l >= 0) overall = l;
            else if (r >= 0) overall = r;
            if (overall < 0) overall = c;
            if (overall >= 0) setMeta(d, META_MAIN, String.valueOf(overall));
        }
    }

    /** 清空电量元数据（null = 移除该键） */
    private static void clearMeta(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getDeclaredMethod("setMetadata", int.class, byte[].class);
            m.setAccessible(true);
            for (int key : new int[]{META_UNTETHERED, META_LEFT, META_RIGHT, META_CASE, META_MAIN}) {
                try { m.invoke(d, key, null); } catch (Throwable ignored) { }
            }
            android.util.Log.i(TAG, "cleared battery metadata for " + d.getAddress());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "clearMeta failed: " + t);
        }
    }

    private static volatile long sLastMetaLog = 0;

    private static void setMeta(BluetoothDevice d, int key, String value) {
        try {
            Method m = BluetoothDevice.class.getDeclaredMethod("setMetadata", int.class, byte[].class);
            m.setAccessible(true);
            Object ok = m.invoke(d, key, value.getBytes(StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            boolean success = ok instanceof Boolean && (Boolean) ok;
            if (now - sLastMetaLog > 20000) {
                sLastMetaLog = now;
                android.util.Log.i(TAG, "setMetadata " + (success ? "ok" : "FALSE")
                        + " key=" + key + " val=" + value + " dev=" + d.getAddress());
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "setMetadata failed key=" + key + ": " + t);
        }
    }

    /* ================= 让 Settings 用我们的 config ================= */
    private void installEndpointOverride(ClassLoader cl) {
        try {
            Class<?> ep = cl.loadClass(
                    "com.android.settingslib.bluetooth.devicesettings.data.repository.DeviceSettingServiceConnection$EndPoint");
            Method ti = ep.getDeclaredMethod("toIntent");
            ti.setAccessible(true);
            Compat.id(hook(ti), "endpoint").intercept(chain -> {
                Object r = chain.proceed();
                try {
                    android.content.Intent orig = (r instanceof android.content.Intent)
                            ? (android.content.Intent) r : null;
                    String was = orig == null ? String.valueOf(r) : orig.getPackage();
                    // 我们自己条目的 endpoint（指向 SettingsService）必须原样放行，
                    // 否则会把 config 服务的 binder 当成 provider 服务用，register 永远失败。
                    if (orig != null && "io.github.eage9.nicehckbridge".equals(orig.getPackage())) {
                        log(4, TAG, "EndPoint.toIntent passthrough (ours, act=" + orig.getAction() + ")");
                        return orig;
                    }
                    // 只有明确指向 GMS 的 config endpoint 才替换；
                    // 其它（含字段缺失的 null）一律放弃，绝不能让 Settings 把
                    // config 服务的 binder 当 provider 用 —— 那会 SecurityException 崩溃。
                    if (orig != null && "com.google.android.gms".equals(orig.getPackage())) {
                        android.content.Intent mine = new android.content.Intent("io.github.eage9.nicehckbridge.dss.CONFIG");
                        mine.setClassName("io.github.eage9.nicehckbridge", "io.github.eage9.nicehckbridge.dss.ConfigService");
                        log(4, TAG, "EndPoint.toIntent overridden (was=gms) -> ours CONFIG");
                        return mine;
                    }
                    log(4, TAG, "EndPoint.toIntent left as-is (was=" + was + ")");
                    return r;
                } catch (Throwable t) {
                    return r;
                }
            });
            log(4, TAG, "hooked EndPoint#toIntent");
        } catch (Throwable t) {
            log(5, TAG, "EndPoint hook failed: " + t);
        }
    }

    /** 反射构造 DeviceSettingItem(settingId=1001, 指向我们的 SettingsService) */
    private static Object buildAncItem(ClassLoader cl) {
        try {
            Class<?> b = cl.loadClass("com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem$Builder");
            Object builder = b.getDeclaredConstructor().newInstance();
            b.getMethod("setSettingId", int.class).invoke(builder, 1001);
            b.getMethod("setPackageName", String.class).invoke(builder, OUR_PKG);
            b.getMethod("setClassName", String.class).invoke(builder, "io.github.eage9.nicehckbridge.dss.SettingsService");
            b.getMethod("setIntentAction", String.class).invoke(builder, "io.github.eage9.nicehckbridge.dss.SETTINGS");
            b.getMethod("setIsOptional", boolean.class).invoke(builder, false);
            b.getMethod("setExtras", android.os.Bundle.class).invoke(builder, android.os.Bundle.EMPTY);
            return b.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 往 Settings 拿到的 DeviceSettingsConfig 里追加我们的 ANC 条目 */
    private void installConfigInject(ClassLoader cl) {
        try {
            Class<?> cfg = cl.loadClass("com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig");
            Method gm = cfg.getDeclaredMethod("getMainContentItems");
            gm.setAccessible(true);
            Compat.id(hook(gm), "cfgItems").intercept(chain -> {
                Object r = chain.proceed();
                try {
                    java.util.List<Object> out = new java.util.ArrayList<>();
                    boolean has = false;
                    if (r instanceof java.util.List) {
                        for (Object o : (java.util.List<?>) r) {
                            out.add(o);
                            try {
                                Object id = o.getClass().getMethod("getSettingId").invoke(o);
                                if (id instanceof Integer && ((Integer) id) == 1001) has = true;
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    if (!has) {
                        Object item = buildAncItem(cl);
                        if (item != null) {
                            out.add(item);
                            log(4, TAG, "cfgItems inject ok, size=" + out.size());
                        } else {
                            log(5, TAG, "cfgItems: buildAncItem failed");
                        }
                    }
                    return out;
                } catch (Throwable t) {
                    log(5, TAG, "cfgItems inject failed", t);
                    return r;
                }
            });
            log(4, TAG, "hooked DeviceSettingsConfig#getMainContentItems");
        } catch (Throwable t) {
            log(5, TAG, "cfgItems hook failed: " + t);
        }
    }

    private void hookFastPairField(ClassLoader cl) {
        if (cl == null) return;
        try {
            Class<?> bu = cl.loadClass("com.android.settingslib.bluetooth.BluetoothUtils");
            Method m = bu.getDeclaredMethod("getFastPairCustomizedField",
                    BluetoothDevice.class, String.class);
            m.setAccessible(true);
            Compat.id(hook(m), "dssMeta").intercept(chain -> {
                try {
                    Object dev = chain.getArg(0);
                    Object key = chain.getArg(1);
                    if (dev instanceof BluetoothDevice && key instanceof String) {
                        SharedPreferences p = remotePrefs();
                        String want = p == null ? "" : p.getString("mac", "");
                        boolean hit = matches((BluetoothDevice) dev, want);
                        String k = (String) key;
                        if (k.startsWith("DEVICE_SETTINGS_CONFIG")) {
                            log(4, TAG, "metaQuery cl=" + cl.getClass().getSimpleName()
                                    + " key=" + k + " dev=" + ((BluetoothDevice) dev).getAddress()
                                    + " want=" + want + " hit=" + hit);
                        }
                        if (hit) {
                            if (KEY_PKG.equals(k)) return OUR_PKG;
                            if (KEY_CLASS.equals(k)) return OUR_CONFIG_CLASS;
                            if (KEY_ACTION.equals(k)) return OUR_ACTION;
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            log(4, TAG, "hooked getFastPairCustomizedField on " + cl.getClass().getSimpleName());
        } catch (Throwable t) {
            log(4, TAG, "  (skip) " + cl.getClass().getSimpleName() + ": " + t);
        }
    }

    /** 探针：打印 EndPoint 构造时的四个字段，看字段到底丢没丢 */
    /**
     * 这个页面会把不在它布局表里的 preference 丢进 invisiblePrefCategory 并 setVisible(false)，
     * 我们在 setVisible(false) 上否决自己的 key，强制保持可见。
     */
    /** 页面重建 PreferenceScreen 时立刻补注入 */
    /**
     * 关键：设备页只渲染 config.mainContentItems 里列出的条目。
     * 我们在 config 反序列化的唯一入口（CREATOR.createFromParcel）上，
     * 往 mainContentItems 里追加我们的 ANC 条目（id=1001，指向我们的 SettingsService）。
     * 这样页面就会用系统自己的渲染器画出降噪控件。
     */
    private void installConfigPatch(ClassLoader cl) {
        try {
            final Class<?> cfgCls = cl.loadClass(
                    "com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig");
            java.lang.reflect.Field cf = cfgCls.getDeclaredField("CREATOR");
            cf.setAccessible(true);
            Object creator = cf.get(null);
            if (creator == null) { log(5, TAG, "CREATOR null"); return; }
            Method m = creator.getClass().getMethod("createFromParcel", android.os.Parcel.class);
            m.setAccessible(true);
            Compat.id(hook(m), "cfgPatch").intercept(chain -> {
                Object cfg = chain.proceed();
                try {
                    patchConfig(cfg, cl);
                } catch (Throwable t) {
                    log(5, TAG, "patchConfig failed", t);
                }
                return cfg;
            });
            log(4, TAG, "hooked DeviceSettingsConfig CREATOR.createFromParcel");
        } catch (Throwable t) {
            log(5, TAG, "config patch hook failed: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private void patchConfig(Object cfg, ClassLoader cl) throws Exception {
        if (cfg == null) return;
        Class<?> cfgCls = cfg.getClass();
        java.lang.reflect.Field itemsF = cfgCls.getDeclaredField("mainContentItems");
        itemsF.setAccessible(true);
        Object v = itemsF.get(cfg);
        if (!(v instanceof java.util.List)) return;
        java.util.List<Object> items = (java.util.List<Object>) v;

        Class<?> itemCls = cl.loadClass(
                "com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem");
        java.lang.reflect.Constructor<?> ctor = itemCls.getDeclaredConstructor(
                int.class, String.class, String.class, String.class, String.class,
                boolean.class, Integer.class, boolean.class, android.os.Bundle.class);
        ctor.setAccessible(true);
        int added = 0;
        for (int want : new int[]{1001, 1002}) {
            boolean exists = false;
            for (Object it : items) {
                try {
                    Object id = it.getClass().getMethod("getSettingId").invoke(it);
                    if (id instanceof Integer && ((Integer) id) == want) { exists = true; break; }
                } catch (Throwable ignored) {
                }
            }
            if (exists) continue;
            items.add(ctor.newInstance(want,
                    "io.github.eage9.nicehckbridge", "io.github.eage9.nicehckbridge.dss.SettingsService",
                    "io.github.eage9.nicehckbridge.dss.SETTINGS", null, true, null, false,
                    android.os.Bundle.EMPTY));
            added++;
        }
        if (added > 0) log(4, TAG, "config patched: +ANC(" + added + "), items now " + items.size());
    }

    private void installScreenRebuildHook(ClassLoader cl) {
        try {
            Class<?> pfc = cl.loadClass("androidx.preference.PreferenceFragmentCompat");
            Class<?> scr = cl.loadClass("androidx.preference.PreferenceScreen");
            Method m = pfc.getDeclaredMethod("setPreferenceScreen", scr);
            m.setAccessible(true);
            Compat.id(hook(m), "setScreen").intercept(chain -> {
                Object r = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    String cn = self.getClass().getName();
                    if (cn.startsWith("com.android.settings.bluetooth.") && cn.contains("DeviceDetails")) {
                        SettingsInjector.inject(self, remotePrefs());
                        log(4, TAG, "re-inject after setPreferenceScreen -> " + SettingsInjector.lastStatus);
                    }
                } catch (Throwable t) {
                    log(5, TAG, "re-inject failed", t);
                }
                return r;
            });
            log(4, TAG, "hooked PreferenceFragmentCompat#setPreferenceScreen");
        } catch (Throwable t) {
            log(5, TAG, "setPreferenceScreen hook failed: " + t);
        }
    }

    private void installVisibilityVeto(ClassLoader cl) {
        try {
            Class<?> prefCls = cl.loadClass("androidx.preference.Preference");
            Method sv = prefCls.getDeclaredMethod("setVisible", boolean.class);
            sv.setAccessible(true);
            Compat.id(hook(sv), "visVeto").intercept(chain -> {
                try {
                    Object v = chain.getArg(0);
                    if (Boolean.FALSE.equals(v)) {
                        Object self = chain.getThisObject();
                        Object key = prefCls.getMethod("getKey").invoke(self);
                        if (key instanceof String && ((String) key).startsWith("nicehck_anc_")) {
                            return null;   // 吞掉这次隐藏
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
            log(4, TAG, "hooked Preference#setVisible (visibility veto for nicehck_anc_*)");
        } catch (Throwable t) {
            log(5, TAG, "visibility veto failed: " + t);
        }
    }

    private void installEndpointProbe(ClassLoader cl) {
        try {
            Class<?> ep = cl.loadClass(
                    "com.android.settingslib.bluetooth.devicesettings.data.repository.DeviceSettingServiceConnection$EndPoint");
            for (java.lang.reflect.Constructor<?> c : ep.getDeclaredConstructors()) {
                c.setAccessible(true);
                Compat.id(hook(c), "epCtor").intercept(chain -> {
                    Object r = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        StringBuilder sb = new StringBuilder("EndPoint(");
                        for (String f : new String[]{"packageName", "className", "intentAction", "isOptional"}) {
                            try {
                                java.lang.reflect.Field fd = ep.getDeclaredField(f);
                                fd.setAccessible(true);
                                sb.append(f).append('=').append(fd.get(self)).append(' ');
                            } catch (Throwable ignored) {
                            }
                        }
                        log(4, TAG, sb.append(')').toString());
                    } catch (Throwable ignored) {
                    }
                    return r;
                });
            }
            log(4, TAG, "hooked EndPoint ctor (probe)");
        } catch (Throwable t) {
            log(5, TAG, "EndPoint probe failed: " + t);
        }
    }

    /**
     * 兜底：在设备详情页注入一条原生 Preference（不依赖 Device Settings Provider 是否被采纳）。
     * onViewCreated 是 PreferenceFragmentCompat 自己声明的方法，比 onResume 稳。
     */
    private void installFallbackRow(ClassLoader cl, XposedModuleInterface.PackageLoadedParam param) {
        try {
            Class<?> pfc = cl.loadClass("androidx.preference.PreferenceFragmentCompat");
            Method m = pfc.getDeclaredMethod("onViewCreated", android.view.View.class, android.os.Bundle.class);
            m.setAccessible(true);
            Compat.id(hook(m), "pageRow").intercept(chain -> {
                Object r = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    String cn = self.getClass().getName();
                    if (cn.startsWith("com.android.settings.bluetooth.") && cn.contains("DeviceDetails")) {
                        try {
                            Object c0 = self.getClass().getMethod("requireContext").invoke(self);
                            if (c0 instanceof android.content.Context) ensureStateReceiver((android.content.Context) c0);
                        } catch (Throwable ignored) {
                        }
                        SettingsInjector.inject(self, remotePrefs());
                        log(4, TAG, "injected row into " + self.getClass().getSimpleName()
                                + " -> " + SettingsInjector.lastStatus);
                    }
                } catch (Throwable t) {
                    log(5, TAG, "inject row failed", t);
                }
                return r;
            });
            log(4, TAG, "hooked PreferenceFragmentCompat#onViewCreated (fallback row)");
        } catch (Throwable t) {
            log(5, TAG, "fallback row hook failed: " + t);
        }
    }

    private SharedPreferences remotePrefs() {
        try {
            SharedPreferences p = getRemotePreferences(PREFS);
            if (p == null) log(5, TAG, "remotePrefs null!");
            return p;
        } catch (Throwable t) {
            log(5, TAG, "remotePrefs failed: " + t);
            return null;
        }
    }

    /** 若该 CachedBluetoothDevice 是原道 OriGin，返回电量；否则 -1。 */
    private int batteryFor(Object cached) {
        try {
            Method gd = cached.getClass().getMethod("getDevice");
            Object o = gd.invoke(cached);
            if (!(o instanceof BluetoothDevice)) return -1;
            BluetoothDevice dev = (BluetoothDevice) o;
            SharedPreferences p = remotePrefs();
            if (p == null) return -1;
            int b = sBatteryFor(dev, p);
            return b;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int sBatteryFor(BluetoothDevice dev, SharedPreferences p) {
        try {
            if (sL >= 0 && (matches(dev, sMac) || matches(dev, p == null ? "" : p.getString("mac", "")))) {
                int l = sL, r = sR;
                int overall = (l >= 0 && r >= 0) ? Math.min(l, r) : Math.max(l, r);
                if (overall < 0) overall = sC;
                if (overall >= 0) return overall;
            }
            if (p != null && matches(dev, p.getString("mac", ""))) {
                int b = p.getInt("battery", -1);
                if (b >= 0) return b;
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean matches(BluetoothDevice dev, String want) {
        try {
            if (want != null && !want.isEmpty()) return want.equalsIgnoreCase(dev.getAddress());
            String n = dev.getName();
            String u = n == null ? "" : n.toUpperCase();
            return u.contains("ORIG") || u.contains("NICEHCK") || u.contains("YUANDAO")
                    || u.contains("NK1") || u.contains("OCTAVE") || u.contains("\u539f\u9053");
        } catch (Throwable t) {
            return false;
        }
    }
}
