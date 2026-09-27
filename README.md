# skyadb 飞鼠版 v1.0.35

`skyadb 飞鼠版` 是运行在 Android 手机上的 ADB 管理和电视飞鼠控制工具。它可以通过 WiFi ADB、Wireless Debugging 或 USB OTG 连接手机、平板、电视和盒子，并在电视端部署 `skyadb 飞鼠服务端`，实现局域网飞鼠、遥控器、屏幕镜像、应用管理、文件管理和 Shell 等功能。

## 主要功能

- ADB 连接管理：支持手动输入 IP/端口、Android 11+ 无线调试配对、USB OTG ADB/Fastboot、最近设备记录。
- 局域网发现：通过 mDNS/NSD 和网段扫描发现可连接的 ADB 设备。
- 设备信息：查看目标设备基础信息、连接状态、截图和系统日志。
- 应用管理：查看目标设备应用列表，支持搜索、分类、启动、停止、卸载和 APK 安装。
- 本机应用处理：导出手机本机用户应用，并安装到目标设备。
- 文件管理：浏览目标设备目录，上传本地文件，下载设备文件，在线下载文件后推送到目标设备。
- Shell 命令：在目标设备执行 ADB Shell 命令并查看输出。
- 虚拟遥控器：模拟方向键、确认、返回、主页、音量等常用实体按键。
- 屏幕镜像：基于 `scrcpy-server-v4.0` 显示目标设备画面，并支持远程触控。
- 局域网飞鼠：手机作为触控板/陀螺仪飞鼠，控制电视端可见光标，支持移动、点击、滚动、返回、主页、音量和文本输入。

## 飞鼠和电视服务端

飞鼠功能由三部分组成：

- `app/`：手机控制端，应用名 `skyadb 飞鼠版`，包名 `com.fs.skyadb.lanmouse`。
- `lanmouse-server/`：电视端 APK，应用名 `skyadb 飞鼠服务端`，包名 `com.server.skyadb.lanmouse`。
- `lanmouse-core-server/`：电视端核心服务，入口 `com.server.skyadb.core.Main`，负责监听 `19870` 端口、绘制光标、注入触摸/按键、处理剪贴板和文本粘贴。

手机端 APK 构建时会自动把电视端服务端 APK 和核心 JAR 打进 `assets/lanmouse/`，一键部署时会安装 `skyadb 飞鼠服务端`，推送核心文件，并启动电视端 `19870` 服务。

## 输入法策略

`skyadb 飞鼠服务端` 带有电视端输入法能力，但不会长期占用输入法。

- 只有手机端进入需要输入文字的状态时，才临时唤起飞鼠输入功能。
- 输入完成、取消输入或退出输入状态后，会自动切回电视原来的输入法。
- 手机端退出飞鼠页面不会主动断开已建立的局域网连接，只有点击断开时才断开，便于长期使用。

## 自启和恢复策略

v1.0.35 使用 root 优先、ADB 兜底的双方案。

- 有 root 并授权时：电视端 `skyadb 飞鼠服务端` 会通过自身 Provider 请求 root，写入 `/data/adb/service.d/skyadb-lanmouse.sh` 自启脚本。电视重启后，服务端应自动启动并监听 `19870` 端口。
- 无 root、没有 root 环境或 root 被拒绝时：手机端使用已有 ADB 连接进行恢复。进入飞鼠连接时，如果发现电视端 `19870` 未启动，会优先做轻量恢复；只有服务端文件缺失或版本不匹配时才需要重新一键部署。
- 没有 root 的电视重启后，无法做到完全脱离 ADB 的真自启，需要手机端重新建立或保持 ADB 连接后触发恢复。

## 使用流程

1. 在电视或盒子上开启开发者选项和无线调试。
2. 在手机端 `skyadb 飞鼠版` 中连接电视 ADB，Android 11+ 可先完成无线调试配对。
3. 进入局域网飞鼠页面，执行一次一键部署。
4. 电视端弹出 root 授权时选择允许，可获得真开机自启；没有 root 时会自动使用 ADB 方案。
5. 部署成功后输入电视 IP 连接飞鼠服务，端口默认为 `19870`。
6. 后续使用时，root 方案会随电视开机自启；无 root 方案在服务未运行时由手机端通过 ADB 恢复。

## 项目结构

```text
app/                    手机端主应用
lanmouse-server/        电视端 skyadb 飞鼠服务端 APK
lanmouse-core-server/   电视端飞鼠核心服务 JAR
docs/                   项目文档和截图
BUILD-SKYADB.md         构建和部署说明
LANMOUSE_ARCHITECTURE.md 飞鼠架构说明
```

## 构建

在源码根目录打开 PowerShell：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

构建手机端时会自动生成并嵌入：

```text
assets/lanmouse/skyadb-lanmouse-core.jar
assets/lanmouse/skyadb-lanmouse-server.apk
```

手机端 APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 版本信息

- 当前源码版本：v1.0.35
- 手机端版本：`1.0.35` / `versionCode 1035`
- 电视端服务端版本：`1.0.35` / `versionCode 1035`
- 飞鼠服务端口：`19870`
- 核心协议标识：`X-SkyADB-Core: 7`

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- Kadb 2.1.3
- OkHttp / WebSocket
- Coroutines / Flow
- DataStore
- scrcpy-server 4.0

## 说明

- 本源码目录不包含 `build/`、`.gradle/`、`.kotlin/`、`local.properties` 等本机缓存或编译产物。
- 局域网飞鼠功能依赖本源码内置的 `lanmouse-core-server` 和 `lanmouse-server`。
- 屏幕镜像功能使用 `app/src/main/assets/scrcpy/scrcpy-server-v4.0`。

## 致谢

- [Kadb](https://github.com/flyfishxu/Kadb)
- [scrcpy](https://github.com/Genymobile/scrcpy)
- [skyadb](https://github.com/sky22333/skyadb)

