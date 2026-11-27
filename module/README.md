# module/

模块工程本体。无 Gradle，直接调用 AOSP 工具链（`aapt2 / javac / d8 / zipalign / apksigner`）构建。

## 工作原理

模块是**旁路式中间件**：不自己连接耳机、不申请蓝牙权限、不占用 RFCOMM 连接。

```
原道 App 进程 (com.yuandao.nicehck)                本模块 App
┌──────────────────────────────────┐    STATE    ┌────────────────────┐
│ 原道 App 自己持有 RFCOMM 连接      │ ─────────►  │ MainActivity       │
│   ↓ getInputStream/getOutputStream│  广播        │ 桌面小组件          │
│ [hook: TapIn / TapOut 旁路字节]   │             │ QS 磁贴（可选）     │
│   ↓ 解析 origin_v1 帧             │             └────────────────────┘
│ Sniffer: 电量 / 当前降噪           │                      ▲
│   ↑ 注入 0x0201 帧（复用 App 模板） │ ◄──── CMD 广播 ──────┘
└──────────────────────────────────┘
```

### 上行（耳机 → 系统）

- Hook `BluetoothSocket#getInputStream`，把 App 读到的字节旁路给 `Sniffer`
- `Sniffer` 解析 `origin_v1` 帧：`0x0005`（电量三元组）、`0x0101`（当前降噪档）
- 结果通过 `Connected` 广播 `io.github.eage9.nicehckbridge.STATE` 发给本模块 App 与 Settings
- 本模块 App 写入 `BluetoothDevice#setMetadata`（`6/10/11/12/18`），系统据此渲染三段电量

### 下行（系统 → 耳机）

- Settings 页的原生控件或本模块 UI 触发切换 → 广播 `…CMD_OUT` 给原道 App 进程
- App 侧接收后由 `Sniffer.setNoise()` 注入 `0x0201 [mode, level]` 帧（与 App 自身发包字节一致）

> 跨进程广播要求本模块在 `AndroidManifest.xml` 中声明 `<queries><package android:name="com.yuandao.nicehck"/></queries>`；
> Settings 进程（system uid）不受该限制。

### 系统集成（Settings 侧）

在 `com.android.settings` 进程中 hook `DeviceSettingsConfig` 的反序列化入口
（`CREATOR.createFromParcel`），往 `mainContentItems` 追加两条 ANC 条目，
让设备详情页用系统自身的 `MultiTogglePreference` 渲染降噪控件：

| 条目 id | 显示 | 档位 |
|---|---|---|
| 1001 | 关闭 / 通透 / 降噪 | 0 / 1 / 2 |
| 1002 | 深度降噪 / 实验降噪 / 抗风噪 | 3 / 16 / 17 |

对应服务由本模块实现：`dss/ConfigService`、`dss/SettingsService`
（实现 AOSP 的 `IDeviceSettingsConfigProviderService` / `IDeviceSettingsProviderService`）。

## 源码结构

```
src/io/github/eage9/nicehckbridge/
├── MainActivity.java      MD3 状态面板（三段电量 + 6 档降噪 + 链路诊断）
├── EarbudsState.java      状态模型（含 30s 新鲜度判断）
├── CmdSender / CmdReceiver / StateReceiver / CmdProvider   跨进程通道
├── NoiseWidget.java       自适应桌面小组件（Canvas 预渲染电量环）
├── NoiseControlTile.java  可选 QS 磁贴
├── proto/                 origin_v1 编解码（Frames）与传输层（Transport）
├── hook/                  Xposed 实现
│   ├── NiceHckModule      模块入口，按进程分发安装
│   ├── AppHooks           原道 App 进程：流旁路、断线检测、心跳、命令注入
│   ├── Sniffer            静态状态机：解析帧、维护存活标志
│   └── Compat             libxposed 101/102 API 差异垫片
├── dss/                   Device Settings Provider 实现（ConfigService / SettingsService / Provider）
└── ui/Md3.java            MD3 设计令牌（Monet 取色 + 基线面层色）

src/com/android/settingslib/**   AOSP DeviceSettings parcelable 的 Java 转写（必须与系统类同名同布局）
```

## 构建

```bash
API_VER=102 bash build.sh
# 产物: build/nicehck-system-bridge.apk
```

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `API_VER` | 102 | libxposed API 版本（选 `libs/api$API_VER`，并写入 `targetApiVersion`） |
| `AOSP` | `$HOME/Android/uwuaosp` | AOSP 树（提供 aapt2/d8/zipalign/apksigner 与 android.jar） |
| `KEY_NAME` | `release` | 密钥名 |
| `KEYDIR` | `$HOME/Android/keys/uwu-release` | 密钥所在目录 |

签名密钥必须固定，否则已安装用户无法覆盖升级。


## 版本号

版本号唯一来源是 `version.properties`（`code` / `name`）：

```properties
code=3
name=1.1
```

`build.sh` 的行为：

1. 读取 `code` / `name`，写入本次构建的 `AndroidManifest.xml` 与自动生成的 `BuildInfo.java`
2. 构建成功后自动把 `code + 1`、`name` 次版本 `+ 1`（`1.0 → 1.1 → 1.2 …`）写回 `version.properties`
3. 需要复用同一版本号（例如重跑一次构建）时：`BUMP=0 bash build.sh`

`BuildInfo.java` 由构建生成、已 `.gitignore`，不要手改。
## 调试

```bash
adb logcat -s NiceHckBridge    # 原道 App 进程：流旁路 / 断线 / 命令注入
adb logcat -s NiceHckDss       # 模块 App 与 Settings 侧：config 注入 / 服务绑定 / 状态推送
adb logcat -s NiceHckWidget    # 小组件：尺寸判定与构建
```

关键日志：

| 日志 | 含义 |
|---|---|
| `module vNN loaded api=102 proc=…` | 模块在目标进程加载成功 |
| `stream hooks installed` | 已挂上蓝牙流 |
| `config patched: +ANC(n)` | Settings 侧 config 注入成功 |
| `SettingsService bound by …` | 系统已绑定我们的设置服务 |
| `link down: …` | 检测到链路断开（EOF / IOException / socket close） |
| `desired confirmed by device: N` | 耳机确认切换到档位 N |

## 已知限制

- 原道 App 必须保持连接，模块本身不建立连接
- 设备详情页的原生控件依赖 ROM 含 Device Settings Provider 框架；缺失时回退为 App 内面板
- 实验 / 抗风档位取决于固件能力
