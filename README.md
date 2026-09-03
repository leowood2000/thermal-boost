# Thermal Boost

K80 Pro (miro) 无线充电加速开关。通过切换 MIUI 热控场景为 ARVR（sconfig=9），使无线充电在中低温段不限流，充电功率提升 1.5–3 倍。

## 原理

MIUI 的 `mi_thermald` 进程通过 inotify 监控 `/sys/devices/virtual/thermal/thermal_message/sconfig` 节点获取当前场景码。本 App 通过 root 写入 `sconfig=9`（ARVR 场景），mi_thermald 即刻加载 ARVR 热控配置：

- **Normal 场景**：虚拟温度 ~35°C 开始限流（wireless_ctrl_limit=3）
- **ARVR 场景**：38.5°C 以下完全不限流（wireless_ctrl_limit=0）

实测充电电流从 ~300mA 提升到 ~900mA（电池端）。

## 要求

- 已 root 的小米手机（Magisk/KernelSU 等）
- 已安装在 `su` 的 root 管理器
- 理论上兼容所有使用 mi_thermald + sconfig 节点的 MIUI/HyperOS 设备

## 使用

1. 安装 APK
2. 打开「充电加速」
3. 点击「切换」按钮 → 显示 **加速充电: ON** 即生效
4. 再次点击 → 恢复默认充电

> 注意：切换前台 App 时 scenariorecognition 服务可能覆盖 sconfig，需重新点开 App 切换。

## 场景码映射

| sconfig | 场景 | sconfig | 场景 |
|---------|------|---------|------|
| 0 | Normal | 11 | VIDEO |
| 1 | 换机 | 14 | VIDEOCHAT |
| 5 | PHONE | 15 | CAMERA |
| 6 | NOLIMITS | 16 | 4K |
| 9 | **ARVR** | 18 | TGAME |
| 10 | NAVIGATION | 20 | 原神 |

## 构建

需要 Android SDK build-tools 34 + JDK 17：

```
aapt package -f -m -J gen -M AndroidManifest.xml -S res -I android.jar
javac -encoding UTF-8 -source 11 -target 11 -cp android.jar -d bin/classes src/.../*.java gen/.../R.java
aapt package -f -M AndroidManifest.xml -S res -I android.jar -F bin/resources.apk
java -cp d8.jar com.android.tools.r8.D8 --output bin --min-api 26 bin/classes/**/*.class
# 将 classes.dex 加入 resources.apk，zipalign + apksigner 签名
```

## License

MIT
