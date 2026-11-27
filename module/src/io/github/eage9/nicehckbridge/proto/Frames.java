package io.github.eage9.nicehckbridge.proto;

import java.util.ArrayList;
import java.util.List;

/**
 * 原道 (NICEHCK) "origin_v1" 命令帧编解码。
 *
 * 线格式（RFCOMM/DATA 载荷）:
 *   [0]      0x4E ('N')
 *   [1..2]   L = payloadLen + 3   (uint16 LE)
 *   [3]      crcPresent (0 = 无校验 / 非0 = 帧尾 2 字节 CRC)
 *   [4..5]   cmdId (uint16 LE)
 *   [6..]    payload (length = L - 3)
 *   [..]     crc16 (uint16 LE, CRC-16/MODBUS: init 0xFFFF, poly 0xA001, 无最终取反)
 *
 * 全部结构来自 com.yuandao.nicehck 2.3.8 静态还原:
 *   w80   -> 服务 UUID 0000a100-1000-8000-4e48-434b4354524c
 *   b8    -> createRfcommSocketToServiceRecord(该 UUID) + InputStream/OutputStream
 *   p04.a -> 拆帧 (indexOf 0x4E / len16 / crcFlag / 总长 = L+3+(crc?2:0))
 *   b80.h -> 同校验收尾 + 命令字解析
 *   f04   -> 命令字表
 *   nt3   -> 降噪模式
 *   z6.r  -> 电量 = payload[0] & 0xFF
 *   b80.g -> 降噪 = (payload[0] = mode, payload[1] = level)
 */
public final class Frames {

    public static final byte MAGIC_N = 0x4E;

    /* ---------------- 命令字 (f04) ---------------- */
    public static final short PROTOCOL_VERSION   = 1;
    public static final short DEVICE_ID          = 2;
    public static final short FIRMWARE_VERSION   = 3;
    public static final short SERIAL_NUMBER      = 4;
    public static final short BATTERY_STATUS     = 5;
    public static final short HEADPHONE_STATUS   = 6;
    public static final short NOISE_CONTROL      = 0x101;
    /** 实测（2026-11-27 日志）：App 写降噪用的是 0x0201，payload = [mode, level] */
    public static final short NOISE_CONTROL_SET  = 0x201;
    public static final short TONE_VOLUME        = 0x102;
    public static final short TOUCH_CONTROLS     = 0x103;
    public static final short CODEC              = 0x104;
    public static final short DUAL_CONNECTION    = 0x105;
    public static final short LOW_LATENCY_MODE   = 0x106;
    public static final short EQUALIZER          = 0x107;
    public static final short GAMING_ENHANCEMENT = 0x108;
    public static final short IN_EAR_DETECTION   = 0x109;
    public static final short ANC_TOUCH_CONTROL  = 0x10A;
    public static final short BASS_BOOST         = 0x10B;
    public static final short SPATIAL_AUDIO      = 0x10C;
    public static final short DUAL_DEVICE_STATUS = 0x10D;
    public static final short VOLUME_ADAPTIVE    = 0x10E;
    public static final short EXPERIMENT_WIND    = 0x1E1;
    public static final short UNKNOWN            = 0x0FFF;

    /* ---------------- 降噪模式 (nt3) ---------------- */
    public static final int NOISE_OFF                = 0;
    public static final int NOISE_TRANSPARENCY       = 1;
    public static final int NOISE_ANC                = 2;
    public static final int NOISE_ANC_DEEP           = 3;
    public static final int NOISE_ANC_EXPERIMENTAL   = 16;
    public static final int NOISE_WIND               = 17;

    public static String modeName(int mode) {
        switch (mode) {
            case NOISE_OFF: return "降噪关";
            case NOISE_TRANSPARENCY: return "通透";
            case NOISE_ANC: return "降噪";
            case NOISE_ANC_DEEP: return "深度降噪";
            case NOISE_ANC_EXPERIMENTAL: return "实验降噪";
            case NOISE_WIND: return "抗风噪";
            default: return "未知(" + mode + ")";
        }
    }

    public static String cmdName(short cmd) {
        switch (cmd) {
            case PROTOCOL_VERSION: return "PROTOCOL_VERSION";
            case DEVICE_ID: return "DEVICE_ID";
            case FIRMWARE_VERSION: return "FIRMWARE_VERSION";
            case SERIAL_NUMBER: return "SERIAL_NUMBER";
            case BATTERY_STATUS: return "BATTERY_STATUS";
            case HEADPHONE_STATUS: return "HEADPHONE_STATUS";
            case NOISE_CONTROL: return "NOISE_CONTROL";
            case NOISE_CONTROL_SET: return "NOISE_CONTROL_SET";
            case TONE_VOLUME: return "TONE_VOLUME";
            case TOUCH_CONTROLS: return "TOUCH_CONTROLS";
            case CODEC: return "CODEC";
            case DUAL_CONNECTION: return "DUAL_CONNECTION";
            case LOW_LATENCY_MODE: return "LOW_LATENCY_MODE";
            case EQUALIZER: return "EQUALIZER";
            case GAMING_ENHANCEMENT: return "GAMING_ENHANCEMENT";
            case IN_EAR_DETECTION: return "IN_EAR_DETECTION";
            case ANC_TOUCH_CONTROL: return "ANC_TOUCH_CONTROL";
            case BASS_BOOST: return "BASS_BOOST";
            case SPATIAL_AUDIO: return "SPATIAL_AUDIO";
            case DUAL_DEVICE_STATUS: return "DUAL_DEVICE_STATUS";
            case VOLUME_ADAPTIVE: return "VOLUME_ADAPTIVE";
            case EXPERIMENT_WIND: return "EXPERIMENT_WIND";
            default: return "0x" + Integer.toHexString(cmd & 0xFFFF);
        }
    }

    public static final class Frame {
        public final short cmd;
        public final byte[] payload;
        Frame(short cmd, byte[] payload) { this.cmd = cmd; this.payload = payload; }
        @Override public String toString() { return cmdName(cmd) + "(" + Hex.of(payload) + ")"; }
    }

    private Frames() {}

    /** 组帧。crc=false 时帧尾不带校验。 */
    public static byte[] encode(short cmd, byte[] payload, boolean crc) {
        if (payload == null) payload = new byte[0];
        int L = payload.length + 3;
        int total = L + 3 + (crc ? 2 : 0);
        byte[] f = new byte[total];
        f[0] = MAGIC_N;
        f[1] = (byte) (L & 0xFF);
        f[2] = (byte) ((L >> 8) & 0xFF);
        f[3] = (byte) (crc ? 1 : 0);
        f[4] = (byte) (cmd & 0xFF);
        f[5] = (byte) ((cmd >> 8) & 0xFF);
        System.arraycopy(payload, 0, f, 6, payload.length);
        if (crc) {
            int c = crc16(f, 0, total - 2);
            f[total - 2] = (byte) (c & 0xFF);
            f[total - 1] = (byte) ((c >> 8) & 0xFF);
        }
        return f;
    }

    /** 从流缓冲中拆出尽可能多的完整帧；返回消耗的字节数，帧写入 out。 */
    public static int decode(byte[] buf, int len, List<Frame> out) {
        int pos = 0;
        while (pos < len) {
            int start = -1;
            for (int i = pos; i < len; i++) if (buf[i] == MAGIC_N) { start = i; break; }
            if (start < 0) { pos = len; break; }
            if (len - start < 6) { pos = start; break; }          // 头部不全
            int L = (buf[start + 1] & 0xFF) | ((buf[start + 2] & 0xFF) << 8);
            boolean crc = buf[start + 3] != 0;
            int total = L + 3 + (crc ? 2 : 0);
            if (total < 6 || total > 0x1001) { pos = start + 1; continue; }  // 假帧头
            if (len - start < total) { pos = start; break; }                 // 等待更多数据
            if (crc) {
                int want = (buf[start + total - 2] & 0xFF) | ((buf[start + total - 1] & 0xFF) << 8);
                if (want != crc16(buf, start, total - 2)) { pos = start + 1; continue; }
            }
            short cmd = (short) ((buf[start + 4] & 0xFF) | ((buf[start + 5] & 0xFF) << 8));
            int plen = total - 6 - (crc ? 2 : 0);
            byte[] payload = new byte[plen];
            System.arraycopy(buf, start + 6, payload, 0, plen);
            out.add(new Frame(cmd, payload));
            pos = start + total;
        }
        return pos;
    }

    /** CRC-16/MODBUS (init 0xFFFF, 反射多项式 0xA001, 无最终取反) — 与 l04.h 完全一致。 */
    public static int crc16(byte[] d, int off, int end) {
        int c = 0xFFFF;
        for (int i = off; i < end; i++) {
            c ^= d[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                if ((c & 1) != 0) c = (c >>> 1) ^ 0xA001;
                else c >>>= 1;
            }
        }
        return c;
    }

    public static final class Hex {
        public static String of(byte[] b) {
            if (b == null) return "";
            StringBuilder sb = new StringBuilder(b.length * 3);
            for (int i = 0; i < b.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(Character.forDigit((b[i] >> 4) & 0xF, 16)).append(Character.forDigit(b[i] & 0xF, 16));
            }
            return sb.toString();
        }
        public static String of(byte[] b, int len) {
            byte[] c = new byte[len];
            System.arraycopy(b, 0, c, 0, Math.min(len, b.length));
            return of(c);
        }
    }

    public static List<Frame> emptyList() { return new ArrayList<>(); }
}
