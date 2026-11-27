package io.github.eage9.nicehckbridge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** 系统侧 → 原道 App 进程的命令通道（写 prefs，App 侧 hook 每 400ms 轮询）。 */
public final class CmdSender {

    /** 系统侧 → 原道 App 进程的广播通道（不依赖 libxposed remote prefs） */
    public static final String ACTION_OUT = "io.github.eage9.nicehckbridge.CMD_OUT";
    public static final String ACTION_STATE = "io.github.eage9.nicehckbridge.STATE";

    public static final String PREFS = "nicehck_cmd";

    private CmdSender() {}

    private static void send(Context c, String op, int mode, int level) {
        try {
            Intent i = new Intent(ACTION_OUT);
            i.setPackage("com.yuandao.nicehck");
            i.putExtra("op", op);
            i.putExtra("mode", mode);
            i.putExtra("level", level);
            c.getApplicationContext().sendBroadcast(i);
            android.util.Log.i("NiceHckBridge", "CMD_OUT sent op=" + op + " mode=" + mode);
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void setNoise(Context c, int mode) {
        send(c, "noise", mode, 0);
        p(c).edit()
                .putInt("mode", mode)
                .putInt("level", 0)
                .putBoolean("refresh", false)
                .putLong("seq", System.currentTimeMillis())
                .apply();
    }

    public static void refresh(Context c) {
        send(c, "refresh", 0, 0);
        p(c).edit()
                .putBoolean("refresh", true)
                .putLong("seq", System.currentTimeMillis())
                .apply();
    }
}
