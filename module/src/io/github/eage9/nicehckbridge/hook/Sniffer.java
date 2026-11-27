package io.github.eage9.nicehckbridge.hook;

import io.github.eage9.nicehckbridge.proto.Frames;
import io.github.eage9.nicehckbridge.proto.Transport;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中间件核心：挂在原道 App 自己的 RFCOMM 流上。
 *
 * - RX：解码设备回包 → 电量 / 降噪模式
 * - TX：学习 App 自己的发包模板（传输包 [1..2] 保留位、帧是否带 CRC）→ 之后注入命令时原样复用
 * - 注入：直接写回 App 那条 socket 的输出流
 *
 * 这样完全不依赖 App 的混淆类名，也不和 App 抢连接。
 */
public final class Sniffer {

    public static final String TAG = "NiceHckBridge";
    public static final byte DATA = Transport.DATA;

    /** 每个传输包类型的 [1..2] 保留字节（从 App 自己的 TX 里学到） */
    private static final Map<Byte, byte[]> RESERVED = new ConcurrentHashMap<>();
    /** 每个命令字是否带 CRC16 尾 */
    private static final Map<Short, Boolean> CRC = new ConcurrentHashMap<>();

    private static volatile OutputStream out;

    private static final Object RX_LOCK = new Object();
    private static byte[] rx = new byte[8192];
    private static volatile long lastRxTs = 0;
    private static volatile Object socket;
    private static int rxLen = 0;

    private static final Object TX_LOCK = new Object();
    private static byte[] tx = new byte[8192];
    private static int txLen = 0;

    public interface Sink {
        void onState(int battery, int mode, int level, String firmware);
        default void onBatteryTriple(int left, int right, int caseB) { }
    }
    private static volatile Sink sink;

    private Sniffer() {}

    public static void setSink(Sink s) { sink = s; }
    public static void setOut(OutputStream o) { out = o; }
    public static boolean hasTransport() { return out != null; }

    /** 链路是否活着：TX 流拿到过 或 收到过 RX */
    private static volatile boolean alive;

    /** 连接存活判断：由 setSocket/setOut 置真，读超时/EOF/close 置假 */
    public static boolean isAlive() { return alive; }

    public static void markDead(String why) {
        if (alive) Log.info("link down: " + why);
        alive = false;
    }

    public static void setSocket(Object s) {
        socket = s;
        alive = s != null;
    }

    private static java.io.OutputStream outStream() {
        java.io.OutputStream o = null;
        if (socket instanceof android.bluetooth.BluetoothSocket) {
            try {
                o = ((android.bluetooth.BluetoothSocket) socket).getOutputStream();
                if (o != null) out = o;
            } catch (Throwable ignored) {
            }
        }
        if (o == null) o = out;   // 回退到缓存的流
        return o;
    }

    static int __fallbackMode() { return Frames.NOISE_OFF; }

    /* ---------------- 流监听 ---------------- */

    public static void onRead(byte[] b, int off, int len) {
        if (len <= 0) return;
        byte[] chunk = new byte[len];
        System.arraycopy(b, off, chunk, 0, len);
        synchronized (RX_LOCK) {
            Log.dump("RX raw", chunk);
            lastRxTs = System.currentTimeMillis();
            rxLen = append(rx, rxLen, chunk);

            // 先看是不是 yu3 传输包（OTA 通道用），否则按 origin 帧直接解析
            List<Transport.Packet> pkts = new ArrayList<>();
            int usedT = Transport.decode(rx, rxLen, pkts);
            if (!pkts.isEmpty()) {
                for (Transport.Packet p : pkts) {
                    if (p.type == DATA) handleFrames(p.payload, true);
                }
                if (usedT > 0) shiftRx(usedT);
                return;
            }
            List<Frames.Frame> frames = new ArrayList<>();
            int usedF = Frames.decode(rx, rxLen, frames);
            for (Frames.Frame f : frames) {
                Log.dump("RX frame", Frames.encode(f.cmd, f.payload, false));
                dispatch(f);
            }
            if (usedF > 0) shiftRx(usedF);
        }
    }

    private static void shiftRx(int used) {
        if (used <= 0) return;
        if (used >= rxLen) { rxLen = 0; return; }
        System.arraycopy(rx, used, rx, 0, rxLen - used);
        rxLen -= used;
    }

    public static void onWrite(byte[] b, int off, int len) {
        if (len <= 0) return;
        byte[] chunk = new byte[len];
        System.arraycopy(b, off, chunk, 0, len);
        synchronized (TX_LOCK) {
            Log.dump("TX raw", chunk);
            // 学习模板：先看传输包
            List<Transport.Packet> pkts = new ArrayList<>();
            int used = Transport.decode(chunk, chunk.length, pkts);
            for (int i = 0; i + 5 <= chunk.length; i++) {
                if (Transport.isKnownType(chunk[i])) {
                    int plen = ((chunk[i + 3] & 0xFF) | ((chunk[i + 4] & 0xFF) << 8)) & 0x3FF;
                    if (i + plen + 5 <= chunk.length) {
                        learnReserved(new byte[]{chunk[i], chunk[i + 1], chunk[i + 2], chunk[i + 3], chunk[i + 4]});
                    }
                }
            }
            for (Transport.Packet p : pkts) {
                if (p.type == DATA) handleFrames(p.payload, false);
            }
            txLen = append(tx, txLen, chunk);
            if (used > 0) { System.arraycopy(tx, used, tx, 0, txLen - used); txLen -= used; }
            if (txLen >= tx.length) txLen = 0;
        }
    }

    /** 直接学保留位（更准确：从原始包体取 [1..2]） */
    public static void learnReserved(byte[] raw) {
        if (raw == null || raw.length < 5) return;
        byte type = raw[0];
        if (!Transport.isKnownType(type)) return;
        RESERVED.put(type, new byte[]{raw[1], raw[2]});
    }

    public static void logSink(java.util.List<String> out) { out.addAll(Log.snapshot()); }

    private static int append(byte[] dst, int cur, byte[] add) {
        int n = Math.min(add.length, dst.length - cur);
        System.arraycopy(add, 0, dst, cur, n);
        return cur + n;
    }

    /* ---------------- 帧解析 ---------------- */

    private static void handleFrames(byte[] payload, boolean fromDevice) {
        List<Frames.Frame> frames = new ArrayList<>();
        Frames.decode(payload, payload.length, frames);
        for (Frames.Frame f : frames) {
            if (!fromDevice) {
                // 记录 App 发包时该命令字是否带 CRC
                CRC.put(f.cmd, crcUsed(payload, f));
                continue;
            }
            dispatch(f);
        }
    }

    private static boolean crcUsed(byte[] payload, Frames.Frame f) {
        // 简化判定：App 的帧都从 0x4E 起，crcFlag 在第 3 字节
        for (int i = 0; i + 3 < payload.length; i++) {
            if (payload[i] == Frames.MAGIC_N) return payload[i + 3] != 0;
        }
        return false;
    }

    private static void dispatch(Frames.Frame f) {
        Sink s = sink;
        if (s == null) return;
        switch (f.cmd) {
            case Frames.BATTERY_STATUS:
                if (f.payload.length >= 1) {
                    int l = f.payload[0] & 0xFF;
                    int r = f.payload.length >= 2 ? (f.payload[1] & 0xFF) : -1;
                    int c = f.payload.length >= 3 ? (f.payload[2] & 0xFF) : -1;
                    s.onBatteryTriple(l, r, c);
                    s.onState(l, -1, -1, null);
                }
                break;
            case Frames.NOISE_CONTROL:
                if (f.payload.length >= 1) {
                    int m = f.payload[0] & 0xFF;
                    if (desiredMode >= 0 && m == desiredMode) {
                        desiredMode = -1;
                        Log.info("desired confirmed by device: " + m);
                    }
                    s.onState(-1, m, f.payload.length >= 2 ? (f.payload[1] & 0xFF) : 0, null);
                }
                break;
            case Frames.FIRMWARE_VERSION:
                s.onState(-1, -1, -1, new String(f.payload, java.nio.charset.StandardCharsets.UTF_8).trim());
                break;
            default:
                break;
        }
    }

    /* ---------------- 出站改写（关键）----------------
     * 原道 App 自己维护降噪状态，会周期性把自己缓存的值写回设备，
     * 我们旁路注入的帧会被它覆盖。所以：系统侧下发后，我们把
     * App 后续所有 NOISE_CONTROL_SET(0x0201) 帧的 mode 字节改写成目标值，
     * 直到设备回包确认该模式。
     */
    private static volatile int desiredMode = -1;
    private static volatile int desiredLevel = 0;

    public static void setDesired(int mode, int level) {
        desiredMode = mode;
        desiredLevel = level;
        Log.info("desired mode=" + mode + " level=" + level);
    }

    public static boolean hasDesired() { return desiredMode >= 0; }

    /** 若本批 TX 里含 0x0201 且我们有目标值，则改写 mode/level 字节 */
    public static byte[] transformTx(byte[] b, int off, int len) {
        int want = desiredMode;
        if (want < 0) return null;
        try {
            byte[] src = new byte[len];
            System.arraycopy(b, off, src, 0, len);
            java.util.List<Frames.Frame> frames = new ArrayList<>();
            Frames.decode(src, src.length, frames);
            boolean changed = false;
            for (Frames.Frame f : frames) {
                if (f.cmd == Frames.NOISE_CONTROL_SET && f.payload.length >= 1) {
                    int oldMode = f.payload[0] & 0xFF;
                    int target = want;
                    if (oldMode != target) {
                        byte[] np = new byte[Math.max(2, f.payload.length)];
                        System.arraycopy(f.payload, 0, np, 0, f.payload.length);
                        np[0] = (byte) target;
                        if (np.length >= 2) np[1] = (byte) desiredLevel;
                        byte[] nf = Frames.encode(f.cmd, np, false);
                        byte[] merged = replaceFrame(src, f, nf);
                        if (merged != null) { src = merged; changed = true; }
                    }
                }
            }
            if (changed) {
                Log.dump("TX-REWRITE", src);
                return src;
            }
        } catch (Throwable t) {
            Log.info("transformTx err " + t);
        }
        return null;
    }

    /** 在整包里用 nf 替换与 f 同 cmd 的那一帧（简化：按首次出现位置替换） */
    private static byte[] replaceFrame(byte[] src, Frames.Frame f, byte[] nf) {
        for (int i = 0; i + 5 < src.length; i++) {
            if (src[i] == Frames.MAGIC_N) {
                int L = (src[i + 1] & 0xFF) | ((src[i + 2] & 0xFF) << 8);
                short cmd = (short) ((src[i + 4] & 0xFF) | ((src[i + 5] & 0xFF) << 8));
                int total = L + 3 + (src[i + 3] != 0 ? 2 : 0);
                if (cmd == f.cmd && i + total <= src.length) {
                    byte[] out = new byte[src.length - total + nf.length];
                    System.arraycopy(src, 0, out, 0, i);
                    System.arraycopy(nf, 0, out, i, nf.length);
                    System.arraycopy(src, i + total, out, i + nf.length, src.length - i - total);
                    return out;
                }
            }
        }
        return null;
    }

    /* ---------------- 命令注入 ---------------- */

    /** 写降噪：实测 App 用的是 NOISE_CONTROL_SET(0x0201) + [mode, level] */
    public static boolean setNoise(int mode, int level) {
        return inject(Frames.NOISE_CONTROL_SET, new byte[]{(byte) mode, (byte) level});
    }

    public static boolean queryBattery() { return inject(Frames.BATTERY_STATUS, new byte[0]); }
    public static boolean queryNoise() { return inject(Frames.NOISE_CONTROL, new byte[0]); }
    public static boolean queryFirmware() { return inject(Frames.FIRMWARE_VERSION, new byte[0]); }

    public static boolean inject(short cmd, byte[] payload) {
        OutputStream o = outStream();
        if (o == null) {
            Log.info("inject-skip: no stream for " + Frames.cmdName(cmd)
                    + " socket=" + (socket == null ? "null" : socket.getClass().getSimpleName()));
            return false;
        }
        Boolean crc = CRC.get(cmd);
        byte[] pkt = Frames.encode(cmd, payload, crc != null && crc);
        try {
            synchronized (Sniffer.class) {
                o.write(pkt);
                o.flush();
            }
            Log.dump("INJECT " + Frames.cmdName(cmd), pkt);
            return true;
        } catch (Throwable t) {
            Log.info("inject-fail " + Frames.cmdName(cmd) + ": " + t);
            return false;
        }
    }

    /** 简易日志（同时写 logcat 与内存环形缓冲） */
    public static final class Log {
        private static final java.util.ArrayDeque<String> BUF = new java.util.ArrayDeque<>();
        private static final int MAX = 120;

        public static void dump(String tag, byte[] b) {
            String h = Frames.Hex.of(b);
            String line = tag + " " + h;
            if (line.length() > 400) line = line.substring(0, 400) + "…";
            android.util.Log.i(TAG, line);
            synchronized (BUF) { BUF.add(line); while (BUF.size() > MAX) BUF.poll(); }
        }

        public static void info(String s) {
            android.util.Log.i(TAG, s);
            synchronized (BUF) { BUF.add(s); while (BUF.size() > MAX) BUF.poll(); }
        }

        public static java.util.List<String> snapshot() {
            synchronized (BUF) { return new java.util.ArrayList<>(BUF); }
        }
    }
}
