# Thermal Boost

Redmi K80 Pro (miro) 热控场景切换器，使用 Xiaomi 自带的 `mi_thermald` 场景，不关闭热控或改写充电电流节点。

## 下载

[下载 v1.2 APK 和查看发布说明](https://github.com/leowood2000/thermal-boost/releases/tag/v1.2)

> v1.2 APK 使用本次 GitHub Actions 构建时生成的临时调试签名。升级安装会因签名不同而失败；需先卸载旧版再安装，卸载会清除 App 本地数据。后续要支持原位升级，需配置可复用的发布签名密钥。

- **无线充电**：选择 ARVR（`sconfig=9`），沿用原有无线加速与场景守护。
- **有线充电**：选择 hp-normal（`sconfig=500`），依据热控场景分析中的候选场景实现；仅在 miro、USB/AC 有线供电且屏幕亮起时应用。

## 原理

MIUI 的 `mi_thermald` 进程通过 inotify 监控 `/sys/devices/virtual/thermal/thermal_message/sconfig` 节点获取当前场景码。本 App 通过 root 写入 `sconfig` 场景码，随后由 `mi_thermald` 加载对应热控配置：

- **Normal 场景**：虚拟温度 ~35°C 开始限流（wireless_ctrl_limit=3）
- **ARVR 场景**：38.5°C 以下无线热控不限流（wireless_ctrl_limit=0）

文档 4.5 将 hp-normal（500）列为有线充电值得实测的候选场景，主要优势在约 33–40°C；它不保证实际充电电流一定提高，实际值仍受协议曲线、热控等级和电池 PID 控制。

## 要求

- 已 root 的小米手机（Magisk/KernelSU 等）
- 已安装在 `su` 的 root 管理器
- 理论上兼容所有使用 mi_thermald + sconfig 节点的 MIUI/HyperOS 设备

## 使用

1. 安装 APK 并打开「充电加速」。
2. 无线充电时选择 **无线 ARVR (9)**；有线充电时选择 **有线 hp-normal (500)**。
3. 两种模式互斥。再次点击当前模式即可关闭。

hp-normal 仅允许在设备代号为 `miro`、电源类型为 USB/AC 且屏幕亮起时应用。拔掉有线充电或熄屏后，守护服务不再强制 500；若当前仍是 App 写入的 500，则恢复默认场景 0。选择有线模式会清理由 App 设置且仍有效的旧 ARVR 场景；条件不满足时保留待命状态，满足后再应用 500。服务正常销毁时也会清理由 App 管理的 500。无线充电请使用 ARVR：hp-normal 的无线侧会更早限流。

守护服务使用 inotify 监控场景变化，并以 60 秒轮询兜底。ARVR 模式保持原有行为；hp-normal 模式只在上述条件成立时拉回 500，不覆盖其他系统场景。

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
| 500 | **hp-normal**（仅 K80 Pro 有线亮屏） | — | — |

## v1.2 改进

- 新增独立的有线 hp-normal (500) 控制；与无线 ARVR (9) 互斥。
- 仅在 Redmi K80 Pro (miro)、USB/AC 供电、屏幕交互时写入 500；条件失效或服务正常销毁时只清理由本 App 管理的 500。
- 前台状态和通知显示所选模式与当前场景；保留 OEM 热控和充电策略。

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

### v1.1.4 — 逻辑 bug 修复 + 健壮性提升
- **删除 800ms quiet window**：自己写 9 后 800ms 内的真正场景修改被忽略，直到 60s poll 才拉回；`readSconfig()==9` 天然不会死循环，quiet window 完全多余且有害
- **FGS 启动顺序修正**：`migrateOutOfFreezer()` 移到 `startForeground()` 之后，避免 su/root 操作卡住导致 FGS 启动超时被系统杀
- **cgroup 迁移验证**：增加返回码检查 + readback 验证（读 `/proc/pid/cgroup` 确认已离开 pid 子目录），不再假成功
- **UI 异步化**：`toggle()`/`execRoot()`/`readSconfig()` 移到 `ExecutorService`，不再阻塞主线程
- **UI 状态分离**：区分「场景 = ARVR」和「守卫运行中」，新增 guardText 显示守卫状态
- **PID 精确 kill**：`startInotify` 时用 `pgrep` 记录 inotifyd PID，`stopGuard` 时 `kill PID` 而非 `pkill` 模糊匹配

### v1.1.5 — 完整性修复
- **关闭时恢复 cgroup**：保存迁移前原始 cgroup 路径，`onDestroy` 时 `migrateBackToFreezer()` 迁回 pid 子目录，恢复 MIUI freezer 管控；原目录不存在时安全跳过
- **生命周期竞态**：`onDestroy` 先 `removeCallbacksAndMessages` 再 `shutdownNow`；`updateUI` 提交任务前检查 `isShutdown`，post 前检查 `isFinishing`
- **初始状态竞态**：`onCreate` 先禁用按钮，异步读取完成后才启用，防止读到错误初始状态误触 toggle
- **root 写入失败检查**：`execRoot` 返回 `boolean`，开启时 root 失败不启动守护服务并提示用户

### v1.1.6 — guard-init 线程竞态修复
- 修复 `onStartCommand` 中 cgroup 迁移线程与 `onDestroy` 的竞态：迁移完成后若服务已被关闭，会回滚迁移并 return，不再对已停止的服务执行后续操作
- `startGuard` 开头增加 `running` 检查，避免在服务已停止后仍启动 inotifyd 守卫

### v1.1.7 — 守卫状态一致性修复
- **startGuardService 返回 boolean**：服务启动失败时不设 `guardEnabled=true`，避免「sconfig=9 + guardEnabled=true + 服务未运行」的假状态
- **onDestroy 清除 guardEnabled**：Service 非正常停止时也清除全局开关，防止 `guardEnabled` 残留为 true
- **Toast 状态准确化**：根据 `boosted && guardEnabled` 组合显示三种提示（加速+守卫运行 / ARVR但守卫未运行 / 已恢复默认），修复关闭时 root 写 0 失败仍提示「场景被改走将自动拉回」的问题

### v1.1.8 — 前台定时刷新
- App 在前台时每 5 秒自动重读 sconfig + wireless_ctrl_limit 并刷新 UI，充电过程中限流值实时更新
- **合并读取优化**：一次 su 同时读取两个节点（cat sconfig; cat wireless_ctrl_limit），su 进程数从 24 次/分钟降到 12 次/分钟
- onResume 启动定时、onPause 停止，切到后台不耗电

### v1.1.9 — toggle 三态逻辑
- toggle() 判断条件从 `boosted` 改为 `boosted && guardEnabled`（真正的 ON）
- 三种状态分别处理：ON→关闭（停守卫+写0）；OFF→开启（写9+启守卫）；ARVR但守卫未运行→直接启动守卫（不先写0再写9）
- 修复 sconfig=9 残留状态被误判为 ON 的问题

## 构建

仓库通过 GitHub Actions 的 [Build APK 工作流](https://github.com/leowood2000/thermal-boost/actions/workflows/build-apk.yml) 构建，使用 JDK 17、Android SDK Platform 34 和 Build Tools 34.0.0。推送到 `feat/wired-charging-mode` 会自动构建；也可以在 Actions 页面手动运行 `Build APK`。

构建成功后，`thermal-boost-v1.2-debug` artifact 保留 14 天；正式下载请使用上面的 GitHub Release。CI 每次构建会生成新的临时调试密钥，因此该 APK 适合全新安装或测试，不适合连续版本原位升级。

## License

MIT
