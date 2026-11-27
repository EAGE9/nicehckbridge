# NICEHCK Bridge

把原道（NICEHCK）**OriG in** 耳机接入 AOSP 原生蓝牙通道的 **LSPosed 模块**源码仓库。

模块让系统像对待 Pixel Buds / Galaxy Buds 那样对待原道耳机：

- 系统设置 → 已连接的设备 → 设备详情里出现**原生降噪控件**（6 档）
- **三段电量**（左侧 / 充电盒 / 右侧）由系统渲染成圆环
- **桌面小组件**（单套自适应布局，横竖屏通用）
- 模块 App 内提供完整面板与链路诊断

> 模块不自己持有蓝牙连接：它旁路原道 App 已有的 RFCOMM/SPP 连接，
> 读取电量与降噪状态，并把系统侧的切换请求注入为 App 同款 `0x0201` 帧。
> 因此**原道 App 必须保持连接**。

## 仓库结构

```
module/                    模块工程（无 Gradle，直接用 AOSP 工具链构建）
├── AndroidManifest.xml
├── build.sh               构建脚本（aapt2 + javac + d8 + zipalign + apksigner）
├── src/                   Java 源码
│   ├── io/github/eage9/nicehckbridge/         App 侧（UI / 状态 / 广播）
│   │   ├── hook/                              Xposed 侧（原道 App + Settings）
│   │   ├── dss/                               Device Settings Provider 实现
│   │   ├── proto/                             协议编解码
│   │   └── ui/                                MD3 设计令牌
│   └── com/android/settingslib/…              AOSP DeviceSettings parcelable 转写
├── res/                   布局 / 图标 / 日夜间配色
├── libs/                  libxposed API jar（api101 / api102）
├── xposed/META-INF/xposed/  模块元数据（module.prop / scope.list / java_init.list）
├── README.md / SPEC.md    模块说明与协议取证
└── aidl/                  从 AOSP 复制的 DeviceSettings AIDL
```

## 构建

需要一份 AOSP 源码树（提供 `aapt2 / d8 / zipalign / apksigner` 与 `android.jar`）：

```bash
cd module
API_VER=102 bash build.sh
# 产物: module/build/nicehck-system-bridge.apk  (+ module/dist/)
```

可用环境变量覆盖：

| 变量 | 默认 | 说明 |
|---|---|---|
| `API_VER` | 102 | libxposed API 版本（决定 `libs/api$API_VER` 与 `targetApiVersion`） |
| `AOSP` | `$HOME/Android/uwuaosp` | AOSP 树路径 |
| `BUMP` | `1` | 构建后自动递增版本（`0` 关闭） |
| `KEY_NAME` | `release` | 密钥名，读取 `$KEYDIR/<KEY_NAME>.{pk8,x509.pem}` |
| `KEYDIR` | `$HOME/Android/keys/uwu-release` | 密钥目录 |

## 如何获取构建产物

本仓库提供源码，构建产物（APK）发布在 **Releases** 页面：

https://github.com/EAGE9/nicehckbridge/releases

## 兼容性

| 能力 | 依赖 | 缺失时 |
|---|---|---|
| 原生降噪控件 | ROM 含 Device Settings Provider 框架（Android 13+ 蓝牙设备页） | 回退到 App 内 6 档按钮 |
| 三段电量 | `BluetoothDevice#setMetadata`（API 33+） | 只剩总电量 |
| 主题图标 | 启动器支持 monochrome | 使用日/夜双配色图标 |

已测：Meizu 20 (m2381) / Android 17 (CP2A.260605.016) / LSPosed 2.2.1。

## 协议

RFCOMM/SPP（service UUID `0000a100-1000-8000-4e48-434b4354524c`），帧格式与档位定义见 [module/SPEC.md](module/SPEC.md)。

## 许可

Apache License 2.0。`src/com/android/settingslib/**` 为 AOSP 类的转写，沿用其原始许可。
