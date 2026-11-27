# 更新日志

## 1.0 · 首个正式版本

**功能**

- 设备详情页原生降噪控件（6 档：关闭 / 通透 / 降噪 / 深度降噪 / 实验降噪 / 抗风噪）
- 系统原生三段电量（左侧 / 充电盒 / 右侧）
- 自适应桌面小组件：横竖屏与任意尺寸通用，三段环形电量 + 一键切换
- 断开耳机后自动转为「未连接」，并清除系统侧残留电量
- 模块 App：MD3 面板与链路诊断

**要求**

- LSPosed（libxposed API ≥ 102）
- 原道 App（`com.yuandao.nicehck`）保持与耳机连接
- LSPosed 作用域：`com.yuandao.nicehck`、`com.android.settings`
