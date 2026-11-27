package io.github.eage9.nicehckbridge.proto;

import java.util.ArrayList;
import java.util.List;

/**
 * RFCOMM 传输层分片（"yu3" 类型包）。
 *
 * 线格式:
 *   [0]      packetType (int8, 见下方常量)
 *   [1..2]   reserved / 可能是 CRC16 LE（app 侧解析时忽略，见 p04.a 分支 1）
 *   [3..4]   payloadLen (uint16 LE，取低 10 位)
 *   [5..]    payload
 *
 * 证据: p04.a 分支 (a==1) 中 ri0.M(3, arr) & 0x3FF 加 5 等于 arr.length，载荷自下标 5 开始;
 *       h60.f 对每个包按该规则切分后交给协议适配器。
 *
 * 握手: 连接后先发 GET_PROTOCOL(0x99) 查询固件支持的协议版本，再在 DATA(0x85) 通道里跑 origin 帧。
 */
public final class Transport {

    public static final byte GET_PROTOCOL = (byte) 0x99;
    public static final byte GET_PROTOCOL_RES = (byte) 0x9A;
    public static final byte SET_USER = (byte) 0x97;
    public static final byte SET_USER_RES = (byte) 0x98;
    public static final byte GET_CURRENT_VERSION = (byte) 0x8E;
    public static final byte GET_CURRENT_VERSION_RES = (byte) 0x8F;
    public static final byte SET_UPGRADE_TYPE = (byte) 0x9D;
    public static final byte SET_UPGRADE_TYPE_RES = (byte) 0x9E;
    public static final byte GET_ROLE_SWITCH_RANDOM_ID = (byte) 0x9B;
    public static final byte GET_ROLE_SWITCH_RANDOM_ID_RES = (byte) 0x9C;
    public static final byte SELECT_SIDE = (byte) 0x90;
    public static final byte SELECT_SIDE_RES = (byte) 0x91;
    public static final byte CONFIG = (byte) 0x86;
    public static final byte CONFIG_RES = (byte) 0x87;
    /** 承载 origin 命令帧的通道 */
    public static final byte DATA = (byte) 0x85;

    /** 是否为已知的传输包类型 */
    public static boolean isKnownType(byte t) {
        switch (t) {
            case GET_PROTOCOL: case GET_PROTOCOL_RES:
            case SET_USER: case SET_USER_RES:
            case GET_CURRENT_VERSION: case GET_CURRENT_VERSION_RES:
            case SET_UPGRADE_TYPE: case SET_UPGRADE_TYPE_RES:
            case GET_ROLE_SWITCH_RANDOM_ID: case GET_ROLE_SWITCH_RANDOM_ID_RES:
            case SELECT_SIDE: case SELECT_SIDE_RES:
            case CONFIG: case CONFIG_RES:
            case DATA:
            case (byte) 0x80: case (byte) 0x81:
            case (byte) 0x82: case (byte) 0x83:
            case (byte) 0x84: case (byte) 0x88:
            case (byte) 0x8C: case (byte) 0x8D:
            case (byte) 0x92: case (byte) 0x93:
                return true;
            default:
                return false;
        }
    }

    public static String typeName(byte t) {
        switch (t) {
            case GET_PROTOCOL: return "GET_PROTOCOL";
            case GET_PROTOCOL_RES: return "GET_PROTOCOL_RES";
            case SET_USER: return "SET_USER";
            case SET_USER_RES: return "SET_USER_RES";
            case GET_CURRENT_VERSION: return "GET_CURRENT_VERSION";
            case GET_CURRENT_VERSION_RES: return "GET_CURRENT_VERSION_RES";
            case SELECT_SIDE: return "SELECT_SIDE";
            case SELECT_SIDE_RES: return "SELECT_SIDE_RES";
            case CONFIG: return "CONFIG";
            case CONFIG_RES: return "CONFIG_RES";
            case DATA: return "DATA";
            default: return String.format("0x%02x", t & 0xFF);
        }
    }

    public static final class Packet {
        public final byte type;
        public final byte[] payload;
        Packet(byte type, byte[] payload) { this.type = type; this.payload = payload; }
        @Override public String toString() { return typeName(type) + "(" + Frames.Hex.of(payload) + ")"; }
    }

    private Transport() {}

    public static byte[] encode(byte type, byte[] payload) {
        if (payload == null) payload = new byte[0];
        int len = payload.length & 0x3FF;
        byte[] p = new byte[len + 5];
        p[0] = type;
        p[1] = 0;
        p[2] = 0;
        p[3] = (byte) (len & 0xFF);
        p[4] = (byte) ((len >> 8) & 0x03);
        System.arraycopy(payload, 0, p, 5, len);
        return p;
    }

    /** 从流缓冲拆包；返回消耗字节数。 */
    public static int decode(byte[] buf, int len, List<Packet> out) {
        int pos = 0;
        while (len - pos >= 5) {
            byte t = buf[pos];
            if (!isKnownType(t)) { pos++; continue; }
            int plen = ((buf[pos + 3] & 0xFF) | ((buf[pos + 4] & 0xFF) << 8)) & 0x3FF;
            if (plen + 5 > 1024) { pos++; continue; }
            if (len - pos < plen + 5) break;
            byte[] payload = new byte[plen];
            System.arraycopy(buf, pos + 5, payload, 0, plen);
            out.add(new Packet(t, payload));
            pos += plen + 5;
        }
        return pos;
    }

    public static List<Packet> decodeAll(byte[] buf, int len) {
        List<Packet> l = new ArrayList<>();
        decode(buf, len, l);
        return l;
    }
}
