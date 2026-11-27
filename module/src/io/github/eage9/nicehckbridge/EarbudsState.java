package io.github.eage9.nicehckbridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 系统侧耳机状态（由原道 App 进程广播驱动）。
 * 同时写入 SharedPreferences("nicehck_state")，供 Settings 侧通过 getRemotePreferences 读取。
 */
public final class EarbudsState {

    public interface Listener { void onStateChanged(); }

    public static final String PREFS = "nicehck_state";

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Context app;

    private static volatile boolean connected;
    private static volatile int battery = -1;
    private static volatile int noiseMode = -1;
    private static volatile int noiseLevel;
    private static volatile String firmware = "";
    private static volatile long lastTs;
    private static volatile int batL = -1, batR = -1, batC = -1;

    /** 心跳每 5 秒一次；超过这个时间没更新就认为掉线（App 进程被杀等） */
    private static final long STALE_MS = 30_000;

    private EarbudsState() {}

    public static void bind(Context ctx) {
        if (app != null || ctx == null) return;
        app = ctx.getApplicationContext();
        // 进程刚起来时先用上次落盘的状态填充，避免设置页首次打开读到空值
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        connected = p.getBoolean("connected", false);
        battery = p.getInt("battery", -1);
        noiseMode = p.getInt("noiseMode", -1);
        noiseLevel = p.getInt("noiseLevel", 0);
        firmware = p.getString("firmware", "");
        lastTs = p.getLong("ts", 0);
    }

    public static void addListener(Listener l) { if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l); }
    public static void removeListener(Listener l) { LISTENERS.remove(l); }

    /** 由 StateReceiver 调用 */
    public static void apply(boolean conn, int bat, int mode, int level, String fw, String addr) {
        apply(conn, bat, mode, level, fw, addr, -1, -1, -1);
    }

    public static void apply(boolean conn, int bat, int mode, int level, String fw, String addr, int bl, int br, int bc) {
        if (!conn) {
            // 掉线：清空所有读数，避免界面上留着断开前的电量
            battery = -1;
            batL = -1; batR = -1; batC = -1;
            noiseMode = -1;
        } else {
            if (bl >= 0) batL = bl;
            if (br >= 0) batR = br;
            if (bc >= 0) batC = bc;
        }
        connected = conn;
        if (bat >= 0) battery = bat;
        if (mode >= 0) { noiseMode = mode; noiseLevel = level; }
        if (fw != null && !fw.isEmpty()) firmware = fw;
        lastTs = System.currentTimeMillis();
        Context c = app;
        if (c != null) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("connected", connected)
                    .putInt("battery", battery)
                    .putInt("noiseMode", noiseMode)
                    .putInt("noiseLevel", noiseLevel)
                    .putString("firmware", firmware)
                    .putString("mac", addr == null || addr.isEmpty() ? c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mac", "") : addr)
                    .putInt("bL", batL)
                    .putInt("bR", batR)
                    .putInt("bC", batC)
                    .putLong("ts", lastTs)
                    .apply();
        }
        for (Listener l : LISTENERS) { try { l.onStateChanged(); } catch (Throwable ignored) {} }
    }

    /** 连接判定：既要有 connected 标志，也要状态足够新 */
    public static boolean isConnected() {
        return connected && (System.currentTimeMillis() - lastTs) < STALE_MS;
    }

    public static int battery() { return isConnected() ? battery : -1; }
    public static int noiseMode() { return isConnected() ? noiseMode : -1; }
    public static int noiseLevel() { return noiseLevel; }
    public static String firmware() { return firmware; }
    public static long lastTs() { return lastTs; }
    public static int batteryL() { return isConnected() ? batL : -1; }
    public static int batteryR() { return isConnected() ? batR : -1; }
    public static int batteryC() { return isConnected() ? batC : -1; }
}
