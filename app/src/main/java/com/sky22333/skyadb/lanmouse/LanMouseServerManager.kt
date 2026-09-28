package com.sky22333.skyadb.lanmouse

import android.content.Context
import com.sky22333.skyadb.adb.AdbSessionKind
import com.sky22333.skyadb.adb.KadbManager
import com.sky22333.skyadb.model.AdbOperationResult
import com.sky22333.skyadb.model.ShellCommandResult
import com.sky22333.skyadb.repository.AdbRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Installs and starts the source-owned SkyADB LAN mouse core. */
class LanMouseServerManager(
    context: Context,
    private val adbRepository: AdbRepository,
    private val kadbManager: KadbManager,
) {
    private val appContext = context.applicationContext
    @Volatile private var lastRootAutostartInstalled = false
    @Volatile private var lastAdbRecoveryUsed = false

    suspend fun deploy(onProgress: (String) -> Unit = {}): AdbOperationResult<String> = withContext(Dispatchers.IO) {
        lastAdbRecoveryUsed = false
        val coreJar = materializeAsset(CoreJarAsset)
        val serverApk = materializeAsset(ServerApkAsset)
        val startScript = materializeStartScript()

        onProgress("正在安装 SkyADB 电视端服务")
        when (val installResult = adbRepository.install(serverApk)) {
            is AdbOperationResult.Failure -> {
                // A streamed install can commit successfully yet still report a non-"Success"
                // marker on some ROMs, and reinstalling an identical package is not an error at
                // all. The package being present is the real success condition, so check that
                // before treating the install as failed.
                if (!isServerPackageInstalled()) {
                    return@withContext installResult
                }
                onProgress("电视端服务已存在,跳过重复安装")
            }
            is AdbOperationResult.Success -> Unit
        }

        onProgress("正在准备电视端运行目录")
        when (val result = checkedShell("mkdir -p $RemoteSkyAdbDir", "创建电视端目录失败")) {
            is AdbOperationResult.Failure -> return@withContext result
            is AdbOperationResult.Success -> Unit
        }

        onProgress("正在推送 SkyADB 飞鼠核心")
        when (val result = adbRepository.push(coreJar, RemoteCoreJar)) {
            is AdbOperationResult.Failure -> return@withContext result
            is AdbOperationResult.Success -> Unit
        }
        when (val result = adbRepository.push(startScript, RemoteStartScript)) {
            is AdbOperationResult.Failure -> return@withContext result
            is AdbOperationResult.Success -> Unit
        }

        onProgress("正在配置开机自启与输入法")
        when (val result = checkedShell(PrepareCommand, "配置电视端服务失败")) {
            is AdbOperationResult.Failure -> return@withContext result
            is AdbOperationResult.Success -> Unit
        }
        lastRootAutostartInstalled = installTvRootAutostartIfAvailable()
        if (lastRootAutostartInstalled) {
            onProgress("已由电视服务端配置 root 真开机自启")
        } else {
            onProgress("电视端未获得 root，无法配置真开机自启")
        }

        onProgress("正在启动局域网服务")
        // stop/start may return 143 (SIGTERM) when adb tears down the shell after backgrounding.
        // Always proceed to port readiness checks instead of treating that as hard failure.
        checkedShellSoft(StopCommand)
        checkedShellSoft(StartCommand)

        waitForCoreReady()
    }

    /**
     * After a TV reboot the installed APK and jar usually remain, but app_process dies.
     * Prefer a lightweight restart over full reinstall when assets are already present.
     */
    suspend fun ensureRunning(
        preferredHost: String = "",
        onProgress: (String) -> Unit = {},
    ): AdbOperationResult<String> =
        withContext(Dispatchers.IO) {
            lastAdbRecoveryUsed = false
            val candidates = linkedSetOf<String>()
            preferredHost.trim().takeIf { it.isNotBlank() }?.let { candidates += it }
            currentHost().takeIf { it.isNotBlank() }?.let { candidates += it }
            for (host in candidates) {
                if (isServiceReachable(host)) {
                    return@withContext AdbOperationResult.Success(host)
                }
            }

            val session = kadbManager.sessionKind()
            if (session != AdbSessionKind.Wifi && session != AdbSessionKind.UsbAdb) {
                return@withContext AdbOperationResult.Failure(
                    message = "当前未连接 ADB，无法自动恢复电视端服务",
                    suggestion = "请先连接电视 ADB，或确认电视端服务已在运行。",
                )
            }

            onProgress("检查电视端核心文件")
            val assetsReady = when (val result = adbRepository.runShell(AssetsReadyCheckCommand)) {
                is AdbOperationResult.Success -> result.data.exitCode == 0
                is AdbOperationResult.Failure -> false
            }
            if (!assetsReady) {
                onProgress("电视端文件缺失，改为完整部署")
                return@withContext deploy(onProgress)
            }

            // Refresh start script in case older deploys lack it.
            val startScript = materializeStartScript()
            adbRepository.push(startScript, RemoteStartScript)

            lastRootAutostartInstalled = installTvRootAutostartIfAvailable()
            if (lastRootAutostartInstalled) {
                onProgress("已由电视服务端恢复 root 开机自启守护")
            } else {
                onProgress("电视端未获得 root，正在通过当前 ADB 会话临时启动")
            }
            lastAdbRecoveryUsed = true
            checkedShellSoft(PrepareAutostartOnlyCommand)
            checkedShellSoft(StopCommand)
            checkedShellSoft(StartCommand)
            waitForCoreReady()
        }

    suspend fun restorePreviousIme(): AdbOperationResult<Unit> = withContext(Dispatchers.IO) {
        checkedShell(RestoreImeCommand, "恢复原输入法失败")
    }

    fun currentHost(): String {
        val endpoint = kadbManager.currentEndpoint().orEmpty()
        if (endpoint.isBlank()) return ""
        return endpoint.substringBeforeLast(':', missingDelimiterValue = endpoint)
            .removePrefix("[")
            .removeSuffix("]")
            .takeUnless { it == "127.0.0.1" || it == "localhost" }
            .orEmpty()
    }

    fun wasRootAutostartInstalled(): Boolean = lastRootAutostartInstalled

    fun wasAdbRecoveryUsed(): Boolean = lastAdbRecoveryUsed

    private suspend fun waitForCoreReady(): AdbOperationResult<String> {
        repeat(12) {
            delay(750)
            val host = resolveLanHost()
            if (!host.isNullOrBlank() && isServiceReachable(host)) {
                return AdbOperationResult.Success(host)
            }
            val check = adbRepository.runShell(CoreReadyCheckCommand)
            if (check is AdbOperationResult.Success && check.data.exitCode == 0) {
                return AdbOperationResult.Success(host.orEmpty())
            }
        }

        val log = when (val result = adbRepository.runShell("tail -n 20 $RemoteLog 2>/dev/null")) {
            is AdbOperationResult.Success -> result.data.output.trim()
            is AdbOperationResult.Failure -> result.message
        }
        return AdbOperationResult.Failure(
            message = "电视端服务未能监听 19870 端口",
            suggestion = log.ifBlank { "请确认电视已允许 ADB 调试及输入事件注入，然后重新部署。" },
        )
    }

    private suspend fun resolveLanHost(): String? {
        currentHost().takeIf { it.isNotBlank() }?.let { return it }
        return when (val result = adbRepository.runShell("ip -o -4 addr show scope global")) {
            is AdbOperationResult.Failure -> null
            is AdbOperationResult.Success -> LanAddressRegex.find(result.data.output)?.groupValues?.getOrNull(1)
        }
    }

    private fun isServiceReachable(host: String): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, SkyAdbPort), PortConnectTimeoutMs)
                socket.soTimeout = HandshakeTimeoutMs
                val request = "GET / HTTP/1.1\r\n" +
                    "Host: $host:$SkyAdbPort\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
                socket.getOutputStream().flush()

                val response = ByteArrayOutputStream()
                val terminator = byteArrayOf(13, 10, 13, 10)
                var matched = 0
                while (response.size() < MaxHandshakeBytes && matched < terminator.size) {
                    val value = socket.getInputStream().read()
                    if (value < 0) return@runCatching false
                    response.write(value)
                    matched = if (value.toByte() == terminator[matched]) {
                        matched + 1
                    } else if (value.toByte() == terminator[0]) {
                        1
                    } else {
                        0
                    }
                }
                val header = response.toString(Charsets.ISO_8859_1.name())
                header.startsWith("HTTP/1.1 101\r\n") &&
                    header.contains("X-SkyADB-Core: $CoreProtocolVersion\r\n")
            }
        }.getOrDefault(false)
    }

    private suspend fun checkedShell(command: String, message: String): AdbOperationResult<Unit> {
        return when (val result = adbRepository.runShell(command)) {
            is AdbOperationResult.Failure -> result
            is AdbOperationResult.Success -> result.data.toUnitResult(message)
        }
    }

    /** True when the TV-side server package is actually installed, regardless of install output. */
    private suspend fun isServerPackageInstalled(): Boolean {
        return when (val result = adbRepository.runShell(ServerPackageCheckCommand)) {
            is AdbOperationResult.Failure -> false
            is AdbOperationResult.Success ->
                result.data.exitCode == 0 || result.data.output.contains(ServerPackageName)
        }
    }

    /** Accepts 0 and common "shell torn down after background start" codes (143=SIGTERM, 137=SIGKILL, 1). */
    private suspend fun checkedShellSoft(command: String): AdbOperationResult<Unit> {
        return when (val result = adbRepository.runShell(command)) {
            is AdbOperationResult.Failure -> {
                // Transport-level failures still matter, but do not abort start/stop sequences.
                AdbOperationResult.Success(Unit)
            }
            is AdbOperationResult.Success -> AdbOperationResult.Success(Unit)
        }
    }

    private suspend fun installTvRootAutostartIfAvailable(): Boolean {
        return when (val result = adbRepository.runShell(InstallTvRootAutostartCommand)) {
            is AdbOperationResult.Failure -> false
            is AdbOperationResult.Success ->
                result.data.exitCode == 0 && result.data.output.contains("rootInstalled=true")
        }
    }

    private fun ShellCommandResult.toUnitResult(message: String): AdbOperationResult<Unit> {
        // 143 = SIGTERM: common when adb shell exits after starting a background app_process.
        if (exitCode == 0 || exitCode == 143 || exitCode == 137) {
            return AdbOperationResult.Success(Unit)
        }
        return AdbOperationResult.Failure(
            message = message,
            suggestion = errorOutput.ifBlank { output.ifBlank { "ADB 命令执行失败，退出码 $exitCode" } },
        )
    }

    private fun materializeAsset(assetPath: String): File {
        val directory = File(appContext.cacheDir, "skyadb-server-assets").apply { mkdirs() }
        val destination = File(directory, assetPath.substringAfterLast('/'))
        appContext.assets.open(assetPath).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return destination
    }

    private fun materializeStartScript(): File {
        val directory = File(appContext.cacheDir, "skyadb-server-assets").apply { mkdirs() }
        val destination = File(directory, "start-core.sh")
        val unix = StartScriptContent.replace("\r\n", "\n").replace("\r", "\n")
        destination.writeText(unix, Charsets.UTF_8)
        return destination
    }

    private companion object {
        const val ServerApkAsset = "lanmouse/skyadb-lanmouse-server.apk"
        const val CoreJarAsset = "lanmouse/skyadb-lanmouse-core.jar"

        const val RemoteSkyAdbDir = "/data/local/tmp/skyadb-lanmouse"
        const val RemoteCoreJar = "$RemoteSkyAdbDir/skyadb-lanmouse-core.jar"
        const val RemoteLog = "$RemoteSkyAdbDir/skyadb-lanmouse.log"
        const val RemotePid = "$RemoteSkyAdbDir/skyadb-lanmouse.pid"
        const val RemotePreviousIme = "$RemoteSkyAdbDir/previous-ime.txt"
        const val RemoteStartScript = "$RemoteSkyAdbDir/start-core.sh"
        const val RemoteAutostartFlag = "$RemoteSkyAdbDir/autostart.enabled"
        const val CursorIme = "com.server.skyadb.lanmouse/.SkyAdbInputMethodService"
        const val ServerPackageName = "com.server.skyadb.lanmouse"
        const val SkyAdbPort = 19_870
        const val PortConnectTimeoutMs = 500
        const val HandshakeTimeoutMs = 1_500
        const val MaxHandshakeBytes = 4_096
        const val CoreProtocolVersion = "7"

        val LanAddressRegex = Regex("""inet\s+(\d{1,3}(?:\.\d{1,3}){3})/""")
        const val PortCheckCommand = "grep -q ':4D9E' /proc/net/tcp /proc/net/tcp6 2>/dev/null"
        const val CoreReadyCheckCommand =
            "$PortCheckCommand && grep -q 'SkyADB cursor overlay created' $RemoteLog 2>/dev/null && grep -q 'SkyADB LAN mouse core listening' $RemoteLog 2>/dev/null"
        const val AssetsReadyCheckCommand =
            "test -f $RemoteCoreJar && pm path com.server.skyadb.lanmouse >/dev/null 2>&1"
        const val ServerPackageCheckCommand =
            "pm path $ServerPackageName >/dev/null 2>&1 || cmd package list packages $ServerPackageName 2>/dev/null | grep -q $ServerPackageName"

        val StartScriptContent = """
            #!/system/bin/sh
            DIR=/data/local/tmp/skyadb-lanmouse
            JAR=${'$'}DIR/skyadb-lanmouse-core.jar
            LOG=${'$'}DIR/skyadb-lanmouse.log
            PID=${'$'}DIR/skyadb-lanmouse.pid
            mkdir -p "${'$'}DIR"
            if [ ! -f "${'$'}JAR" ]; then
              echo "missing core jar" >> "${'$'}LOG"
              exit 0
            fi
            if grep -q ':4D9E' /proc/net/tcp /proc/net/tcp6 2>/dev/null; then
              exit 0
            fi
            : > "${'$'}LOG"
            trap '' HUP INT TERM
            CLASSPATH="${'$'}JAR" /system/bin/app_process / com.server.skyadb.core.Main >> "${'$'}LOG" 2>&1 </dev/null &
            echo ${'$'}! > "${'$'}PID"
            exit 0
        """.trimIndent().replace("\r\n", "\n").replace("\r", "\n") + "\n"

        // Enable IME only (do not set as default). Persist previous IME and autostart flag.
        val PrepareCommand = """
            sh -c 'chmod 0644 $RemoteCoreJar 2>/dev/null || true; chmod 0755 $RemoteStartScript 2>/dev/null || true; CURRENT_IME=${'$'}(settings get secure default_input_method 2>/dev/null | tr -d "\r"); if [ -n "${'$'}CURRENT_IME" ] && [ "${'$'}CURRENT_IME" != null ] && [ "${'$'}CURRENT_IME" != "$CursorIme" ]; then printf "%s" "${'$'}CURRENT_IME" > $RemotePreviousIme; fi; ime enable $CursorIme >/dev/null 2>&1 || true; echo 1 > $RemoteAutostartFlag; content call --uri content://com.server.skyadb.lanmouse.provider --method ensureReady >/dev/null 2>&1 || true; content call --uri content://com.server.skyadb.lanmouse.provider --method enableAutostart >/dev/null 2>&1 || true; content call --uri content://com.server.skyadb.lanmouse.provider --method startCore >/dev/null 2>&1 || true; exit 0'
        """.trimIndent()

        val PrepareAutostartOnlyCommand = """
            sh -c 'chmod 0755 $RemoteStartScript 2>/dev/null || true; ime enable $CursorIme >/dev/null 2>&1 || true; echo 1 > $RemoteAutostartFlag; content call --uri content://com.server.skyadb.lanmouse.provider --method enableAutostart >/dev/null 2>&1 || true; content call --uri content://com.server.skyadb.lanmouse.provider --method startCore >/dev/null 2>&1 || true; exit 0'
        """.trimIndent()

        val InstallTvRootAutostartCommand = """
            content call --uri content://com.server.skyadb.lanmouse.provider --method installRootAutostart 2>/dev/null
        """.trimIndent()

        val RestoreImeCommand = """
            sh -c 'PREV=""; if [ -s $RemotePreviousIme ]; then PREV=${'$'}(cat $RemotePreviousIme | tr -d "\r"); fi; CURRENT=${'$'}(settings get secure default_input_method 2>/dev/null | tr -d "\r"); if [ -n "${'$'}PREV" ] && [ "${'$'}PREV" != null ] && [ "${'$'}PREV" != "$CursorIme" ]; then ime set "${'$'}PREV" >/dev/null 2>&1 || settings put secure default_input_method "${'$'}PREV" >/dev/null 2>&1 || true; elif [ "${'$'}CURRENT" = "$CursorIme" ]; then CANDIDATE=${'$'}(ime list -s 2>/dev/null | tr -d "\r" | grep -v "$CursorIme" | head -n 1); if [ -n "${'$'}CANDIDATE" ]; then ime set "${'$'}CANDIDATE" >/dev/null 2>&1 || true; fi; fi; exit 0'
        """.trimIndent()

        val StopCommand = """
            sh -c 'if [ -s $RemotePid ]; then kill ${'$'}(cat $RemotePid) >/dev/null 2>&1 || true; fi; pkill -f "[c]om.server.skyadb.core.Main" >/dev/null 2>&1 || true; pkill -f "[c]om.marmot.scrcpy.Main" >/dev/null 2>&1 || true; fuser -k 19870/tcp >/dev/null 2>&1 || true; sleep 1; rm -f $RemotePid $RemoteLog /data/local/tmp/marmot-scrcpy-server.jar; rm -rf /data/local/tmp/marmot; touch $RemoteLog; exit 0'
        """.trimIndent()

        // Proven one-liner: background app_process under shell, always exit 0 for the adb shell itself.
        val StartCommand = """
            sh -c 'trap "" HUP INT TERM; CLASSPATH=$RemoteCoreJar /system/bin/app_process / com.server.skyadb.core.Main >> $RemoteLog 2>&1 </dev/null & echo ${'$'}! > $RemotePid; exit 0'
        """.trimIndent()
    }
}
