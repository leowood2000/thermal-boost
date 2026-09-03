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

开启后，后台前台服务会自动守护 ARVR 场景：当其他 App（如相机、导航等）触发 scenariorecognition 覆盖 sconfig 时，会在 300ms 内自动拉回 ARVR(9)，无需手动干预。关闭加速时守护服务随之停止。

> v1.0 需手动重新切换；v1.1 起支持自动场景守卫。

## 场景码映射

| sconfig | 场景 | sconfig | 场景 |
|---------|------|---------|------|
| 0 | Normal | 11 | VIDEO |
| 1 | 换机 | 14 | VIDEOCHAT |
| 5 | PHONE | 15 | CAMERA |
| 6 | NOLIMITS | 16 | 4K |
| 9 | **ARVR** | 18 | TGAME |
| 10 | NAVIGATION | 20 | 原神 |

## v1.1 改进

- **场景守卫前台服务**：开启加速后，通过 root inotifyd 事件驱动监控 sconfig，场景被改走时自动拉回 ARVR(9)
- **省电设计**：inotify 事件驱动，无事件时 CPU 占用 0%；60s 低频轮询兜底
- **前台通知**：低优先级常驻通知，仅提示运行状态，无声音无振动
- 对有线充电热控限流也有改善（ARVR 高温段限流比 Normal 更宽松）

## v1.1 修复记录

### v1.1.1 — 关闭时竞态拉回
- 修复关闭加速时 inotify 守卫线程把 sconfig 拉回 9 的问题
- 根因：`stopService()` 异步执行，关闭时写 sconfig=0 触发 inotify 事件，守卫线程在服务停止前抢先拉回
- 修复：增加 `guardEnabled` 静态标志位，关闭时先禁用守护再写 sconfig

### v1.1.2 — 进程健壮性
- 修复 inotifyd 孙进程残留 + 僵尸 sh 持有管道写端导致守卫静默失效的问题
- 修复：`startInotify` 改用 `exec inotifyd`（su 的 shell 直接替换为 inotifyd，消除中间 sh 层）
- 修复：关闭时 `pkill -f 'inotifyd - <path>'` 强制清理残留进程
- 修复：`onStartCommand` 中置 `guardEnabled=true`，解决进程被杀后以 `START_STICKY` 重建时守卫不启动的问题

### v1.1.3 — 规避 MIUI 进程冻结
- 修复 App 退后台后 MIUI/HyperOS 通过 cgroup freezer 冻结进程导致守卫失效的问题
- 根因：MIUI 的 PowerKeeper/智能省电把 App 进程冻结到 `do_freezer_trap`，inotifyd 虽不受影响但 App 无法消费事件
- 修复：`onStartCommand` 中调用 `migrateOutOfFreezer()`，用 root 把自身进程从 `uid_<uid>/pid_<pid>` cgroup 迁移到 `uid_<uid>` 级 cgroup，MIUI 冻结动作针对 pid 子目录，迁移后找不到目标
- 实测：打开 YouTube 后台运行 60 秒，sconfig 持续保持 9，线程无冻结痕迹

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
