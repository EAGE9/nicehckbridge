# 原道（NICEHCK）耳机控制协议 — 静态还原规格书

来源：`com.yuandao.nicehck` 2.3.8（versionCode 33，targetSdk 37 / Android 17），
classes.dex 7.08 MB，R8 全混淆（类名 a00…/defpackage）。全部结论由 jadx + baksmali 交叉验证。

---

## 1. 传输层：RFCOMM/SPP，不是 GATT

| 项 | 值 | 证据 |
|---|---|---|
| 传输 | `BluetoothDevice.createRfcommSocketToServiceRecord(UUID)` | `b8.smali` line 1632 |
| 服务 UUID | `0000a100-1000-8000-4e48-434b4354524c`（ASCII "NHCKCTRL"） | `w80`（BtUuidProfile: service/write/notify 三个字段实际只有 service 被用到，write=…1001、notify=…1002 是历史遗留） |
| 读写 | `socket.getInputStream()/getOutputStream()` | `b8` 字段 f/g |
| 说明 | 所以手机端**不经过 GATT**，任何"用 GATT 连耳机"的实现都不成立 | — |

> 推论：想实现常驻系统控制，必须自己保持一条 RFCOMM 连接（原道 App 没有任何 Service，
> 只在 App 进程活着时连接，见第 6 节）。

---

## 2. 传输包格式（"yu3" 类型包）

App 侧：`h60` 用 `p04(1)` 在字节流上切包；解析规则
`len = readU16LE(buf,3) & 0x3FF`，要求 `buf.length == len + 5`，载荷 = `buf[5..]`。

```
偏移  长度  含义
0     1     packetType (int8)
1     2     reserved（未确认，疑为 CRC16 或序号；App 解析时忽略）
3     2     payloadLen (uint16 LE, 低 10 位有效)
5     N     payload
```

### packetType 取值（yu3 枚举，完整）

| 值 | 名称 | 值 | 名称 |
|---|---|---|---|
| 0x99 | GET_PROTOCOL | 0x9A | GET_PROTOCOL_RES |
| 0x97 | SET_USER | 0x98 | SET_USER_RES |
| 0x8E | GET_CURRENT_VERSION | 0x8F | GET_CURRENT_VERSION_RES |
| 0x9D | SET_UPGRADE_TYPE | 0x9E | SET_UPGRADE_TYPE_RES |
| 0x9B | GET_ROLE_SWITCH_RANDOM_ID | 0x9C | GET_ROLE_SWITCH_RANDOM_ID_RES |
| 0x90 | SELECT_SIDE | 0x91 | SELECT_SIDE_RES |
| 0x8C | GET_TRANSMISSION_RESUMING_AT_BREAK_POINT | 0x8D | …_RES |
| 0x80 | FETCH_OTA | 0x81 | FETCH_OTA_RES |
| 0x86 | CONFIG | 0x87 | CONFIG_RES |
| **0x85** | **DATA**（承载 origin 命令帧） | 0x82/0x83 | SEGMENT_CRC_VERIFY(_RES) |
| 0x88/0x84 | IMAGE_CRC_VERIFY(_RES) | 0x92/0x93 | IMAGE_OVERWRITING_CONFIRMATION(_RES) |

握手：连上后先发 `GET_PROTOCOL`，响应决定用 origin_v1 还是 origin_v2 适配器
（`c80` 里两个适配器：`l04` = "origin_v1" rev 1，`n04` = 另一版本）。

---

## 3. 命令帧格式（origin_v1）

App 侧：`p04(2)` 在 DATA 载荷里按 0x4E 分帧；`l04.h` 校验并解析；`l04.j` 映射命令字。

```
偏移  长度  含义
0     1     magic 0x4E ('N')
1     2     L = payloadLen + 3 (uint16 LE)      → 总长 = L + 3 (+2 若带 CRC)
3     1     crcPresent (0 = 无；非 0 = 帧尾 2 字节 CRC16 LE)
4     2     cmdId (uint16 LE)
6     N     payload
尾    2     crc16 (仅 crcPresent != 0)
```

CRC = **CRC-16/MODBUS**：init `0xFFFF`，反射多项式 `0xA001`，无最终取反
（`l04.h` / `n04` 中 `i2 = 65535`，`i2 ^= 40961`）。

另有第二种帧头 `FF 4E 4B`（`p04(0)` 分支，len 在 [3..4]、cmd 在 [6..7]），
App 当前两条适配器都未使用，保守起见实现里保留开关。

---

## 4. 命令字表（f04）

| 值 | 名称 | 值 | 名称 |
|---|---|---|---|
| 0x001 | PROTOCOL_VERSION | 0x002 | DEVICE_ID |
| 0x003 | FIRMWARE_VERSION | 0x004 | SERIAL_NUMBER |
| **0x005** | **BATTERY_STATUS** | 0x006 | HEADPHONE_STATUS |
| **0x101** | **NOISE_CONTROL** | 0x102 | TONE_VOLUME |
| 0x103 | TOUCH_CONTROLS | 0x104 | CODEC |
| 0x105 | DUAL_CONNECTION | 0x106 | LOW_LATENCY_MODE |
| 0x107 | EQUALIZER | 0x108 | GAMING_ENHANCEMENT |
| 0x109 | IN_EAR_DETECTION | 0x10A | ANC_TOUCH_CONTROL |
| 0x10B | BASS_BOOST | 0x10C | SPATIAL_AUDIO |
| 0x10D | DUAL_DEVICE_STATUS | 0x10E | VOLUME_ADAPTIVE |
| 0x1E1 | EXPERIMENT_WIND | 0xFFF | UNKNOWN |

### 电量（BATTERY_STATUS=0x005）
解析器 `z6.r`：`payload[0] & 0xFF` → 百分比。
`z6.t` 还能把 3 字节解析成三元组（左右耳/充电仓），对应载荷长度 3 的情况。

### 降噪（NOISE_CONTROL=0x101）
解析器 `l04.g` → `lt3(mode, level)`：`payload[0]` = 模式，`payload[1] & 0xFF` = 等级。
写入时应发送 `[mode, level]` 两字节。

| 值 | 模式（nt3） |
|---|---|
| 0 | OFF 关 |
| 1 | TRANSPARENCY 通透 |
| 2 | NOISE_CANCELING 降噪 |
| 3 | DEEP_NOISE_CANCELING 深度降噪 |
| 16 | EXPERIMENT_NOISE_CANCELING |
| 17 | WIND_NOISE_CANCELING 抗风噪 |

> `mt3` 是 3 值的老版本（0/1/2），部分机型/固件只支持三档。

其他：FIRMWARE_VERSION/SERIAL_NUMBER = UTF-8 字符串（`z6.N`）；
CODEC 列表 `z6.J`；IN_EAR/BASS_BOOST 等布尔 = `z6.A`（0/1）。

---

## 5. App 能力自述（f80 BtSessionProfile）

```
BtSessionProfile(firmwareRevision, resolvedDevice, toneVolume, touch,
                 spatialAudio, gameLatency, equalizer, availableCodecs, noiseModes)
```
→ 支持的能力集合由固件回报决定，App 通过 `a10`（feature→driver 表）匹配；
未知 feature key 会直接崩溃（"Bluetooth drivers declare unknown feature keys"）。

## 6. App 生命周期

AndroidManifest 只有 1 个 Activity + 3 个 provider（FileProvider / Compose resources /
androidx.startup），**没有 Service、没有 Receiver**。

因此连接完全属于原道 App 进程：进程被杀则连接断开。本模块不建立自己的连接，
而是旁路该进程已有的 socket（见 module/README.md「工作原理」）。

## 7. 实测确认

以下三项最初只有静态推断，已由真机抓包确认：

1. 传输包保留位（第 1~2 字节）写 0 即可，设备接受。
2. 请求帧默认不带 CRC（useCrc 关闭）可用；设备应答帧带 CRC 时按 MODBUS 校验。
3. SET_USER(0x97) 不是必需握手，仅发 GET_PROTOCOL 即可完成初始化。

已在实机验证的关键交互（原始字节）：

```
TX 4e 05 00 00 01 02 02 00     <- 切到「降噪」(mode=2)
RX 4e 04 00 00 01 02 02        <- 设备确认写入
TX 4e 03 00 00 01 01           <- 查询当前档位
RX 4e 04 00 00 01 01 02        <- 当前 = 2

RX 4e 06 00 00 05 00 64 5a 00  <- 电量：左 100 / 右 90 / 仓 0
```
---

## 附：OriGin 机型配置（APK 内置，权威）

`assets/device-config/device-2.json`：

| 字段 | 值 |
|---|---|
| name / customName | YUANDAO OriG in / OriG in |
| bluetoothNames | "OriG in"、"YUANDAO OriG in" |
| protocolVariant | **origin_v1** |
| serviceUuid | 0000a100-1000-8000-4e48-434b4354524c |
| write/notifyCharUuid | …1001 / …1002（Android 侧实际走 RFCOMM，见第 1 节） |

降噪（`bt.noise_control`，variant `with_experimental`）options 只有两项：
`NOISE_CANCELING`(2)、`DEEP_NOISE_CANCELING`(3)。
另有 `bt.wind_noise`（固件 ≥402，mode 17）与 `bt.touch_control: with_anc_cycle`（固件 ≥407）。

其余 feature：`bt.equalizer_preset`(7 段预设)、`bt.game_mode`、`bt.dual_connection`、
`bt.ota`、`bt.find_earbuds`、`bt.tone_volume`、`bt.latency_mode`、`bt.codec_control`、
`bt.in_ear_detection`。
