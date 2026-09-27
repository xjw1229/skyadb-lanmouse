# SkyADB 飞鼠版 v1.0.35 构建说明

这份源码对应 `skyadb 飞鼠版` v1.0.35(在 v34 基础上增加无输入法文字注入与旧系统光标层兼容)。项目包含手机端主应用、电视端 `skyadb 飞鼠服务端` 和电视端飞鼠核心服务，构建手机端 APK 时会自动把电视端产物一起打包进去。

## 模块结构

- `app/`：手机端主应用，应用名 `skyadb 飞鼠版`，包名 `com.fs.skyadb.lanmouse`。
- `lanmouse-server/`：电视端服务端 APK，应用名 `skyadb 飞鼠服务端`，包名 `com.server.skyadb.lanmouse`。
- `lanmouse-core-server/`：电视端飞鼠核心服务，入口 `com.server.skyadb.core.Main`。

## 飞鼠实现

局域网飞鼠功能由本项目源码内的三个模块共同实现：

- 手机端 `app/` 负责 ADB 连接、一键部署、服务恢复、WebSocket 连接、触控板操作和陀螺仪飞鼠控制。
- 电视端 `lanmouse-server/` 负责按需输入法、Provider 调用、BootReceiver 辅助恢复和 root 自启脚本写入。
- 电视端 `lanmouse-core-server/` 通过 `app_process` 运行，监听 `19870` 端口，负责 WebSocket 协议、`SurfaceControl` 光标绘制、触摸/按键注入、剪贴板和文本粘贴。

核心服务运行后会监听：

```text
0.0.0.0:19870
```

WebSocket 握手包含：

```text
X-SkyADB-Core: 7
```

## 输入法策略

电视端服务端带有输入法能力，但不长期占用输入法：

- 手机端只有进入文字输入状态时，才临时切换到 `skyadb 飞鼠服务端` 输入法。
- 输入完成、取消输入或退出输入状态后，会恢复电视原来的输入法。
- 手机端退出飞鼠页面不会主动断开局域网飞鼠连接，只有用户点击断开时才断开。

## 自启和恢复

v1.0.35 使用 root 优先、ADB 兜底的双方案。

### root 方案

电视端已 root 且用户授权时，`skyadb 飞鼠服务端` 会通过自身 Provider 写入：

```text
/data/adb/service.d/skyadb-lanmouse.sh
```

这个脚本会在电视开机后启动：

```text
CLASSPATH=/data/local/tmp/skyadb-lanmouse/skyadb-lanmouse-core.jar /system/bin/app_process / com.server.skyadb.core.Main
```

root 方案成功后，电视重启后应自动恢复 `19870` 服务。

### ADB 兜底方案

没有 root、root 环境不可用或 root 授权被拒绝时，手机端会使用已有 ADB 连接恢复服务：

- 如果电视端 APK 和核心 JAR 已存在，只执行轻量启动。
- 如果服务端文件缺失或版本不匹配，再执行完整一键部署。
- 无 root 的电视重启后不能完全脱离 ADB 真自启，需要手机端重新建立或保持 ADB 连接后触发恢复。

## 远端文件

一键部署会在电视端使用以下路径：

```text
/data/local/tmp/skyadb-lanmouse/
├── skyadb-lanmouse-core.jar
├── skyadb-lanmouse.log
├── skyadb-lanmouse.pid
├── start-core.sh
├── autostart.enabled
└── root-autostart.enabled
```

电视端服务端 APK 包名：

```text
com.server.skyadb.lanmouse
```

电视端输入法组件：

```text
com.server.skyadb.lanmouse/.SkyAdbInputMethodService
```

## 构建命令

在源码根目录打开 PowerShell：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

构建 `app` 时会自动执行：

1. 编译 `lanmouse-core-server`。
2. 使用 D8 生成 DEX 格式的 `skyadb-lanmouse-core.jar`。
3. 编译并签名 `lanmouse-server` debug APK。
4. 将电视端核心 JAR 和服务端 APK 嵌入手机端 APK 的 `assets/lanmouse/`。

## 构建产物

手机端 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

电视端核心 JAR：

```text
lanmouse-core-server/build/outputs/lanmouse-core/skyadb-lanmouse-core.jar
```

电视端服务端 APK：

```text
lanmouse-server/build/outputs/apk/debug/lanmouse-server-debug.apk
```

手机端 APK 内置资产：

```text
assets/lanmouse/skyadb-lanmouse-core.jar
assets/lanmouse/skyadb-lanmouse-server.apk
```

## 版本信息

- 手机端版本：`1.0.35`
- 手机端 `versionCode`：`1035`
- 电视端服务端版本：`1.0.35`
- 电视端服务端 `versionCode`：`1035`
- 飞鼠服务端口：`19870`
- 核心协议标识：`X-SkyADB-Core: 7`

## 说明

- 干净源码目录不应包含 `.gradle/`、`.kotlin/`、`build/`、`local.properties`、APK/AAB 等本机缓存或编译产物。
- 屏幕镜像功能使用 `app/src/main/assets/scrcpy/scrcpy-server-v4.0`，它只服务于屏幕镜像，不参与局域网飞鼠控制。
- 首次从旧版本升级时，一键部署会清理电视 `/data/local/tmp` 中旧飞鼠相关残留文件，然后部署本项目自己的核心服务。
