package io.github.eage9.nicehckbridge.hook;

import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

/**
 * 在「原道 App」进程里安装中间件：
 *  1) hook BluetoothSocket#getInputStream / getOutputStream → 旁路原始字节
 *  2) 解析 RX 得到电量/降噪 → 广播给模块 App（系统侧）
 *  3) 轮询模块 App 写的 nicehck_cmd → 往同一条 socket 注入命令
 */
public final class AppHooks {

    private static final String TAG = "NiceHckBridge";
    public static final String STATE_ACTION = "io.github.eage9.nicehckbridge.STATE";
    public static final String APP_PKG = "com.yuandao.nicehck";

    private static volatile Context appContext;
    private static volatile long lastSeq = -1;
    private static volatile int battery = -1;
    private static volatile int mode = -1;
    private static volatile int level = 0;
    private static volatile String fw = "";
    private static volatile long lastPublish = 0;
    private static volatile long lastQuery = 0;
    private static volatile String peerAddr = "";
    private static volatile int batL = -1, batR = -1, batC = -1;

    /* ---- 原道 App 自身的降噪入口（照抄它的实现）----
     * a40.e(String, nt3 mode, int level, Continuation)   ← UI 点击后走这里
     * 我们不自己拼帧，直接调用它，App 的状态机/重试/快照全部沿用。
     */
    private static volatile Object sDriver;      // a40
    private static volatile Object sSession;     // b04
    private static volatile String sAddr = "";
    private static volatile ClassLoader sAppCl;

    private AppHooks() {}

    private static void noteSocket(BluetoothSocket s) {
        try {
            String a = s.getRemoteDevice().getAddress();
            if (!a.equals(peerAddr)) {
                peerAddr = a;
                Sniffer.Log.info("socket peer " + a);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void install(XposedInterface xp, XposedModuleInterface.PackageLoadedParam param) {
        ClassLoader cl = param.getDefaultClassLoader();
        try { appContext = currentApplication(); } catch (Throwable ignored) {}

        sAppCl = cl;
        hookAppInternals(xp, cl);

        Sniffer.setSink(new Sniffer.Sink() {
            @Override public void onState(int battery, int mode, int level, String firmware) {
                AppHooks.onState(battery, mode, level, firmware);
            }
            @Override public void onBatteryTriple(int left, int right, int caseB) {
                batL = left; batR = right; batC = caseB;
                publish();
            }
        });

        try {
            Method gi = BluetoothSocket.class.getDeclaredMethod("getInputStream");
            gi.setAccessible(true);
            Compat.id(xp.hook(gi), "btIn").intercept(chain -> {
                Object self = chain.getThisObject();
                if (self instanceof BluetoothSocket) { Sniffer.setSocket(self); noteSocket((BluetoothSocket) self); }
                return new TapIn((InputStream) chain.proceed(), (BluetoothSocket) (self instanceof BluetoothSocket ? self : null));
            });
            Method go = BluetoothSocket.class.getDeclaredMethod("getOutputStream");
            go.setAccessible(true);
            Compat.id(xp.hook(go), "btOut").intercept(chain -> new TapOut((OutputStream) chain.proceed()));
            Method gc = BluetoothSocket.class.getDeclaredMethod("close");
            gc.setAccessible(true);
            Compat.id(xp.hook(gc), "btClose").intercept(chain -> {
                onLinkLost("socket closed");
                return chain.proceed();
            });
            Sniffer.Log.info("stream hooks installed in " + APP_PKG + " (v" + io.github.eage9.nicehckbridge.BuildInfo.VERSION_NAME + ")");
        } catch (Throwable t) {
            Sniffer.Log.info("stream hook failed: " + t);
        }

        startPoller(xp);
        startHeartbeat();
    }

    private static volatile boolean sReceiverRegistered = false;
    private static volatile boolean sPollerStarted = false;
    private static volatile int sLastMode = -1;

    /** onPackageReady 时 Context 一定可用，在这里注册接收器并启动轮询 */
    public static void onReady(XposedInterface xp) {
        if (appContext == null) {
            try { appContext = currentApplication(); } catch (Throwable ignored) {}
        }
        registerCmdReceiver();
        startPoller(xp);
    }

    /** 系统侧命令走广播进来，注册在原道 App 进程里 */
    private static void registerCmdReceiver() {
        if (sReceiverRegistered) return;
        try {
            Context ctx = appContext;
            if (ctx == null) { ctx = currentApplication(); appContext = ctx; }
            if (ctx == null) { Sniffer.Log.info("cmd receiver: no context yet"); }
            else {
            android.content.IntentFilter f = new android.content.IntentFilter("io.github.eage9.nicehckbridge.CMD_OUT");
            android.content.BroadcastReceiver rcv = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, android.content.Intent intent) {
                    if (intent == null) return;
                    String op = intent.getStringExtra("op");
                    final int mode = intent.getIntExtra("mode", -1);
                    final int level = intent.getIntExtra("level", 0);
                    Sniffer.Log.info("CMD_OUT rx op=" + op + " mode=" + mode);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if ("noise".equals(op) && mode >= 0) {
                            sLastMode = mode;
                            Sniffer.setDesired(mode, level);
                            // 与 App 自己切换时发的帧完全一致：4e 05 00 00 01 02 <mode> 00
                            Sniffer.setNoise(mode, level);
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(Sniffer::queryNoise, 250);
                        } else {
                            Sniffer.queryBattery();
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(Sniffer::queryNoise, 120);
                        }
                    });
                }
            };
            if (ctx != null) {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(rcv, f, Context.RECEIVER_EXPORTED);
                } else {
                    ctx.registerReceiver(rcv, f);
                }
                sReceiverRegistered = true;
                Sniffer.Log.info("cmd receiver registered in app process");
            }
            }
        } catch (Throwable t) {
            Sniffer.Log.info("cmd receiver register failed: " + t);
        }
        Sniffer.Log.info("registerCmdReceiver done, registered=" + sReceiverRegistered
                + " ctx=" + (appContext == null ? "null" : "ok"));
    }

    private static void hookAppInternals(XposedInterface xp, ClassLoader cl) {
        // a40 = 降噪/功能驱动；b04 = 会话
        try {
            Class<?> a40 = cl.loadClass("a40");
            for (java.lang.reflect.Constructor<?> c : a40.getDeclaredConstructors()) {
                c.setAccessible(true);
                Compat.id(xp.hook(c), "drvCtor").intercept(chain -> {
                    Object r = chain.proceed();
                    sDriver = chain.getThisObject();
                    Sniffer.Log.info("captured a40 (driver)");
                    return r;
                });
            }
        } catch (Throwable t) {
            Sniffer.Log.info("a40 hook failed: " + t);
        }
        try {
            Class<?> b04 = cl.loadClass("b04");
            for (java.lang.reflect.Constructor<?> c : b04.getDeclaredConstructors()) {
                c.setAccessible(true);
                Compat.id(xp.hook(c), "sessCtor").intercept(chain -> {
                    Object r = chain.proceed();
                    sSession = chain.getThisObject();
                    try {
                        java.lang.reflect.Field f = b04.getDeclaredField("b");
                        f.setAccessible(true);
                        Object v = f.get(sSession);
                        if (v instanceof String) sAddr = (String) v;
                    } catch (Throwable ignored) {
                    }
                    Sniffer.Log.info("captured b04 (session) addr=" + sAddr);
                    return r;
                });
            }
        } catch (Throwable t) {
            Sniffer.Log.info("b04 hook failed: " + t);
        }
    }


    /* ---------------- 状态 → 系统 ---------------- */

    private static void onState(int b, int m, int l, String f) {
        boolean changed = false;
        if (b >= 0 && b != battery) { battery = b; changed = true; }
        if (m >= 0 && (m != mode || l != level)) { mode = m; level = l; changed = true; }
        if (f != null && !f.equals(fw)) { fw = f; changed = true; }
        long now = System.currentTimeMillis();
        if (changed || now - lastPublish > 5000) publish();
    }

    /** 链路断开：清空电量/模式并立即广播 connected=false */
    static void onLinkLost(String why) {
        if (!Sniffer.isAlive()) return;      // 已处理过
        Sniffer.markDead(why);
        battery = -1;
        batL = -1; batR = -1; batC = -1;
        mode = -1;
        publish();
    }

    /** 心跳：连着的每 5 秒重播一次状态，让下游能靠"多久没更新"判断掉线 */
    private static void startHeartbeat() {
        if (sHeartbeatStarted) return;
        sHeartbeatStarted = true;
        Thread t = new Thread(() -> {
            sleep(3000);
            while (true) {
                try {
                    if (Sniffer.isAlive()) publish();
                } catch (Throwable ignored) {
                }
                sleep(5000);
            }
        }, "nicehck-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    private static void publish() {
        Context c = appContext;
        if (c == null) { try { c = currentApplication(); appContext = c; } catch (Throwable ignored) {} }
        if (c == null) return;
        lastPublish = System.currentTimeMillis();
        try {
            Intent i = new Intent(STATE_ACTION);
            i.setPackage("io.github.eage9.nicehckbridge");
            fillState(i);
            c.sendBroadcast(i);
            Intent i2 = new Intent(STATE_ACTION);
            i2.setPackage("com.android.settings");
            fillState(i2);
            c.sendBroadcast(i2);
        } catch (Throwable t) {
            Sniffer.Log.info("publish failed: " + t);
        }
    }

    private static void fillState(Intent i) {
        {
            i.putExtra("connected", Sniffer.isAlive());
            i.putExtra("battery", battery);
            i.putExtra("mode", mode);
            i.putExtra("level", level);
            i.putExtra("firmware", fw);
            i.putExtra("addr", peerAddr);
            i.putExtra("bL", batL);
            i.putExtra("bR", batR);
            i.putExtra("bC", batC);
            i.putExtra("ts", lastPublish);
        }
    }

    /* ---------------- 系统 → 命令注入 ---------------- */

    private static volatile boolean sHeartbeatStarted;

    private static void startPoller(final XposedInterface xp) {
        if (sPollerStarted) return;
        sPollerStarted = true;
        Thread t = new Thread(() -> {
            sleep(1500);
            while (true) {
                try {
                    if (!sReceiverRegistered) registerCmdReceiver();
                    pollProvider();
                    SharedPreferences p = xp.getRemotePreferences("nicehck_cmd");
                    long seq = p == null ? -1 : p.getLong("seq", -1);
                    if (seq > 0 && seq != lastSeq) {
                        lastSeq = seq;
                        if (p.getBoolean("refresh", false)) {
                            Sniffer.queryBattery();
                            sleep(60);
                            Sniffer.queryNoise();
                        } else {
                            int m = p.getInt("mode", Sniffer.__fallbackMode());
                            int lv = p.getInt("level", 0);
                            Sniffer.setDesired(m, lv);
                            final int fm = m, fl = lv;
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                Sniffer.setDesired(fm, fl);
                                Sniffer.setNoise(fm, fl);
                            });
                        }
                        publish();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    long now = System.currentTimeMillis();
                    if (Sniffer.isAlive() && now - lastQuery > 30000) {
                        lastQuery = now;
                        Sniffer.queryBattery();
                        sleep(80);
                        Sniffer.queryNoise();
                    }
                } catch (Throwable ignored) {
                }
                sleep(400);
            }
        }, "nicehck-cmd-poller");
        t.setDaemon(true);
        t.start();
        Sniffer.Log.info("command poller started");
    }

    private static volatile long sLastProvSeq = -1;

    /** 通过 ContentProvider 拉命令（不依赖广播投递） */
    private static void pollProvider() {
        Context ctx = appContext;
        if (ctx == null) { try { ctx = currentApplication(); appContext = ctx; } catch (Throwable ignored) {} }
        if (ctx == null) return;
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    android.net.Uri.parse("content://io.github.eage9.nicehckbridge.cmd/cmd"),
                    null, null, null, null);
            if (c == null || !c.moveToFirst()) return;
            long seq = c.getLong(0);
            int mode = c.getInt(1);
            int level = c.getInt(2);
            boolean refresh = c.getInt(3) != 0;
            if (seq > 0 && seq != sLastProvSeq) {
                sLastProvSeq = seq;
                Sniffer.Log.info("PROVIDER cmd seq=" + seq + " mode=" + mode + " refresh=" + refresh);
                final int fm = mode, fl = level;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (refresh) {
                        Sniffer.queryBattery();
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(Sniffer::queryNoise, 120);
                    } else if (fm >= 0) {
                        Sniffer.setDesired(fm, fl);
                        Sniffer.setNoise(fm, fl);
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(Sniffer::queryNoise, 250);
                    }
                });
            }
        } catch (Throwable t) {
            Sniffer.Log.info("provider poll err: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object o = m.invoke(null);
            return o instanceof Context ? (Context) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sleepMs(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /* ---------------- 流包装 ---------------- */

    static final class TapIn extends InputStream {
        private final InputStream d;
        TapIn(InputStream d, BluetoothSocket sock) {
            this.d = d;
            if (sock != null) Sniffer.setSocket(sock);
        }

        @Override public int read() throws IOException {
            int b;
            try {
                b = d.read();
            } catch (IOException e) {
                onLinkLost("read() threw " + e);
                throw e;
            }
            if (b >= 0) Sniffer.onRead(new byte[]{(byte) b}, 0, 1);
            else onLinkLost("read() EOF");
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n;
            try {
                n = d.read(b, off, len);
            } catch (IOException e) {
                onLinkLost("read(buf) threw " + e);
                throw e;
            }
            if (n > 0) Sniffer.onRead(b, off, n);
            else if (n < 0) onLinkLost("read(buf) EOF");
            return n;
        }
        @Override public int available() throws IOException { return d.available(); }
        @Override public void close() throws IOException {
            onLinkLost("input stream closed");
            d.close();
        }
    }

    static final class TapOut extends OutputStream {
        private final OutputStream d;
        TapOut(OutputStream d) { this.d = d; Sniffer.setOut(d); }

        @Override public void write(int b) throws IOException {
            d.write(b);
            Sniffer.onWrite(new byte[]{(byte) b}, 0, 1);
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            byte[] mod = Sniffer.transformTx(b, off, len);
            if (mod != null) {
                d.write(mod, 0, mod.length);
                Sniffer.onWrite(mod, 0, mod.length);
            } else {
                d.write(b, off, len);
                Sniffer.onWrite(b, off, len);
            }
        }
        @Override public void flush() throws IOException { d.flush(); Sniffer.setOut(d); }
        @Override public void close() throws IOException {
            onLinkLost("input stream closed");
            d.close();
        }
    }
}
