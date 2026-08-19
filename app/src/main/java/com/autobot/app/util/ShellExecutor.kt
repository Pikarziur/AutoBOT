package com.autobot.app.util

import android.util.Log
import com.autobot.app.manager.ShizukuManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

/**
 * Shell 执行工具类
 * 支持通过 Shizuku 或普通方式执行 shell 命令和 sh 脚本文件。
 *
 * 两种模式：
 * - 一次性 `execute()` / `executeScript()`：命令执行完才返回 stdout/stderr
 * - 流式 `executeStreaming()`：stdout/stderr 逐行回调 onLine，适合任务日志实时显示
 */
object ShellExecutor {

    private const val TAG = "ShellExecutor"

    /**
     * 执行结果数据类
     */
    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * 执行 shell 命令（一次性模式：命令结束才返回结果）
     * @param command 要执行的命令
     * @param useShizuku 是否使用 Shizuku 权限执行（默认 true）
     * @param env 附加环境变量（key=value 形式传入进程环境），可覆盖/追加系统原有 env
     * @param timeout 超时时间（毫秒），默认 30 秒
     * @return 执行结果
     */
    fun execute(
        command: String,
        useShizuku: Boolean = true,
        env: Map<String, String> = emptyMap(),
        timeout: Long = 30000
    ): ShellResult {
        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()
        val exitCode = executeStreamingInternal(
            command, useShizuku, env, timeout,
            onStdoutLine = { line ->
                stdoutBuilder.append(line).append("\n")
            },
            onStderrLine = { line ->
                stderrBuilder.append(line).append("\n")
            }
        )
        return ShellResult(
            exitCode,
            stdoutBuilder.toString().trim(),
            stderrBuilder.toString().trim()
        )
    }

    /**
     * 执行 shell 命令（流式模式：stdout/stderr 逐行推给 onLine 回调）
     *
     * 适用于任务日志实时打印场景，`onLine` 会在 IO 线程逐行回调，
     * 调用方可以将日志追加到 UI StateFlow 实现实时显示。
     *
     * @param command   要执行的命令
     * @param useShizuku 是否使用 Shizuku 权限执行（默认 true）
     * @param env 附加环境变量（注入到 sh -c 之前，.sh 脚本中可通过 $KEY 读取）
     *            例如：AUTOBOT_VD_DISPLAY_ID=11 让 am/tap 等命令走虚拟显示器
     * @param timeout   超时时间（毫秒），默认 10 分钟（脚本任务通常比较长）
     * @param onStdoutLine stdout 每一行的回调（参数为单行，不含换行符）；IO 线程调用
     * @param onStderrLine stderr 每一行的回调（参数为单行，不含换行符）；IO 线程调用
     * @return 进程退出码：0 成功，-2 超时，其他负数 执行异常
     */
    fun executeStreaming(
        command: String,
        useShizuku: Boolean = true,
        env: Map<String, String> = emptyMap(),
        timeout: Long = 600_000L,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        return try {
            executeStreamingInternal(command, useShizuku, env, timeout, onStdoutLine, onStderrLine)
        } catch (e: Exception) {
            Log.e(TAG, "Streaming execute error: $command", e)
            -1
        }
    }

    /**
     * 流式执行核心（被 `execute` 和 `executeStreaming` 共同复用）
     */
    private fun executeStreamingInternal(
        command: String,
        useShizuku: Boolean,
        env: Map<String, String>,
        timeout: Long,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        return if (useShizuku && ShizukuManager.isShizukuGranted()) {
            executeWithShizukuStreaming(command, env, timeout, onStdoutLine, onStderrLine)
        } else {
            executeWithNormalStreaming(command, env, timeout, onStdoutLine, onStderrLine)
        }
    }

    /**
     * 执行本地 sh 脚本文件
     *
     * ★Shizuku 模式访问权限问题修复★
     *   Shizuku 走 shell uid(2000)，应用 filesDir 是 app uid 私有目录(0700)，
     *   shell uid 无法直接读取 filesDir 下的 .sh → Permission denied。
     *   解决方案：当 useShizuku=true 时，先将脚本内容复制到 shell uid 可读写的
     *   /data/local/tmp/autobot_scripts/<random>_xxx.sh，再执行临时文件。
     *   普通模式（app uid）直接执行原路径。
     *
     * @param scriptPath sh 脚本文件路径
     * @param useShizuku 是否使用 Shizuku 权限执行
     * @param env 附加环境变量（key=value），.sh 脚本中可 $KEY 读取
     * @param timeout 超时（毫秒），默认 10 分钟
     * @param args 传递给脚本的位置参数（$1 $2 ...）
     * @return 执行结果
     */
    fun executeScript(
        scriptPath: String,
        useShizuku: Boolean = true,
        env: Map<String, String> = emptyMap(),
        timeout: Long = 60_000L,
        vararg args: String
    ): ShellResult {
        val effectivePath = maybeCopyScriptToTmpForShizuku(scriptPath, useShizuku)
        if (effectivePath == null) {
            return ShellResult(-1, "", "Script file not found or cannot be staged: $scriptPath")
        }

        // ★VD 二次保险 wrapper：与 executeScriptStreaming 一致
        val finalCmd = buildWrappedScriptCommand(effectivePath, args, env)
        val tmpWrapper = finalCmd.second

        try {
            return execute(finalCmd.first, useShizuku, env, timeout)
        } finally {
            maybeCleanupTmpScript(effectivePath, scriptPath, useShizuku)
            tmpWrapper?.let {
                try { File(it).delete() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 执行本地 sh 脚本文件（流式模式：stdout/stderr 逐行回调）
     *
     * @param scriptPath sh 脚本文件路径
     * @param useShizuku 是否使用 Shizuku 权限执行
     * @param env 附加环境变量（AUTOBOT_VD_DISPLAY_ID 等）
     * @param args 传递给脚本的参数
     * @param timeout 超时（毫秒），默认 10 分钟
     * @param onStdoutLine stdout 每一行的回调；IO 线程调用
     * @param onStderrLine stderr 每一行的回调；IO 线程调用
     * @return 进程退出码
     */
    fun executeScriptStreaming(
        scriptPath: String,
        useShizuku: Boolean = true,
        env: Map<String, String> = emptyMap(),
        vararg args: String,
        timeout: Long = 600_000L,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        val effectivePath = maybeCopyScriptToTmpForShizuku(scriptPath, useShizuku)
        if (effectivePath == null) {
            onStderrLine("[ERROR] Script file not found or cannot be staged: $scriptPath")
            return -1
        }

        // ★VD 操作二次保险（AutoBOT-specific wrapper）★：
        // 当 env 中存在 AUTOBOT_VD_DISPLAY_ID 时，生成 wrapper 脚本，
        // 为用户脚本中每行 `am start` / `am instrument` / `input` / `uiautomator` 等
        // 没有显式带 `--display` 的命令自动插入 --display。
        // 这样即使脚本作者忘记写 `--display $VDID`，也不会误点主屏 AutoBOT。
        val finalCmd = buildWrappedScriptCommand(effectivePath, args, env)
        val tmpWrapper = finalCmd.second  // 非 null 时需 finally 清理

        val exit = try {
            executeStreaming(
                finalCmd.first, useShizuku, env, timeout,
                onStdoutLine = onStdoutLine,
                onStderrLine = onStderrLine
            )
        } finally {
            maybeCleanupTmpScript(effectivePath, scriptPath, useShizuku)
            tmpWrapper?.let {
                try { File(it).delete() } catch (_: Exception) {}
            }
        }
        return exit
    }

    /**
     * /data/local/tmp/autobot_scripts —— Shizuku shell uid 可读写的临时脚本目录
     *   - 使用 /data/local/tmp 而非 /sdcard：避免安全浏览过滤 + 不经过 scoped storage
     *   - 单次脚本复制：保证脚本执行时 shell uid 能读到内容
     */
    private const val TMP_SCRIPT_DIR = "/data/local/tmp/autobot_scripts"

    /**
     * 当 useShizuku=true 时，将 scriptPath（app filesDir）的脚本内容复制到 TMP_SCRIPT_DIR
     * 下的临时文件，返回临时文件绝对路径；useShizuku=false 或复制失败返回原路径/报错返回 null。
     *
     * 注：不通过 Shizuku `cat` 来写（路径间可直接 java.io.File 读写，因为当前 app 能
     * 读 filesDir、adb shell 经 java.io 写 /data/local/tmp 不一定有写入权限——所以这里
     * 用 app 自身写，app 对 /data/local/tmp 一般是可写的（tmp 粘滞位放开 app 写入）。
     * 若 app 也无法写入，退化使用 Shizuku `tee` 落盘到 TMP_SCRIPT_DIR。
     */
    private fun maybeCopyScriptToTmpForShizuku(scriptPath: String, useShizuku: Boolean): String? {
        if (!useShizuku) {
            // 普通模式：app uid 自己执行自己 filesDir，不需要重定位
            return if (File(scriptPath).exists()) scriptPath else null
        }
        val src = File(scriptPath)
        if (!src.exists()) return null

        val tmpDir = File(TMP_SCRIPT_DIR)
        val safeName = "${UUID.randomUUID().toString().substring(0, 8)}_${src.name}"
        val tmpFile = File(tmpDir, safeName)

        // 尝试路径1：app 直接写入 /data/local/tmp（大部分 ROM 允许 app 创建自己的文件）
        val writeAppOk = try {
            if (!tmpDir.exists()) tmpDir.mkdirs()
            src.copyTo(tmpFile, overwrite = true)
            tmpFile.setExecutable(true, false)  // 所有用户可执行，保证 shell uid 能跑
            tmpFile.canRead()
        } catch (e: Exception) {
            Log.w(TAG, "App cannot stage script to /data/local/tmp, fallback Shizuku tee", e)
            false
        }
        if (writeAppOk) {
            return tmpFile.absolutePath
        }

        // 尝试路径2：用 Shizuku 权限创建目录 + 写内容（mkdir -p + tee）
        return try {
            val cmd = buildString {
                append("mkdir -p '$TMP_SCRIPT_DIR' && ")
                // 把脚本内容通过 base64 传避免特殊字符问题
                // (简单方式：cat src via shizuku is not readable, so use java.io read + Shizuku's sh -c echo)
                val contentBase64 = android.util.Base64.encodeToString(src.readBytes(), android.util.Base64.NO_WRAP)
                append("echo -n '$contentBase64' | base64 -d > '${tmpFile.absolutePath}' && ")
                append("chmod 0755 '${tmpFile.absolutePath}'")
            }
            val res = rawOneShotShizuku(cmd)
            if (res == 0 && tmpFile.exists() && tmpFile.canRead()) {
                tmpFile.absolutePath
            } else {
                Log.e(TAG, "Shizuku tee stage failed exit=$res. tmpPath=${tmpFile.absolutePath}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku tee stage exception", e)
            null
        }
    }

    /** 清理 Shizuku 模式下使用的临时脚本（不清理普通模式原路径） */
    private fun maybeCleanupTmpScript(effectivePath: String, originalPath: String, useShizuku: Boolean) {
        if (!useShizuku) return
        if (effectivePath == originalPath) return // 没使用临时文件
        try {
            val f = File(effectivePath)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup tmp script failed: $effectivePath", e)
        }
    }

    /** 一次性 Shizuku 执行（不经过主线程，内部使用，仅用于落盘辅助） */
    private fun rawOneShotShizuku(command: String): Int {
        return try {
            val cmdArray = arrayOf("sh", "-c", command)
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, cmdArray, null, null) as Process
            process.inputStream.bufferedReader().forEachLine { /* drain */ }
            process.errorStream.bufferedReader().forEachLine { /* drain */ }
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 15000) {
                try { return process.exitValue() } catch (_: RuntimeException) { Thread.sleep(80) }
            }
            try { process.destroy() } catch (_: Exception) {}
            -2
        } catch (e: Exception) {
            Log.e(TAG, "rawOneShotShizuku failed", e)
            -1
        }
    }

    // 包装脚本中，需要自动注入 --display 的命令前缀（行首关键字）
    private val VD_DISPLAY_CMDS = arrayOf(
        "am start",
        "am instrument",
        "am start-foreground-service",
        "am startservice",
        "am broadcast",
        "input ",
        "input\t",
        "uiautomator"
    )

    /**
     * 把 sh 脚本改写成"自动带 --display $AUTOBOT_VD_DISPLAY_ID"的 wrapper 版本。
     *
     * 返回值 Pair<执行命令字符串, 可选的临时 wrapper 路径>：
     *   - 若 env 中没有 AUTOBOT_VD_DISPLAY_ID：返回 `sh <path> <args>`，wrapper=null
     *   - 若有：在 tmp 目录生成包装脚本，把原始脚本按行处理，命中 [VD_DISPLAY_CMDS] 且
     *           行内未出现 `--display` 时自动插入 --display $AUTOBOT_VD_DISPLAY_ID；
     *           返回 `sh <wrapper> <args>` 和 wrapper 文件路径（供 finally 清理）
     */
    private fun buildWrappedScriptCommand(
        scriptPath: String,
        args: Array<out String>,
        env: Map<String, String>
    ): Pair<String, String?> {
        val vdId = env["AUTOBOT_VD_DISPLAY_ID"]
        if (vdId.isNullOrBlank()) {
            val cmd = buildString {
                append("sh ")
                append(scriptPath)
                if (args.isNotEmpty()) {
                    append(" ")
                    append(args.joinToString(" "))
                }
            }
            return cmd to null
        }

        // 生成 wrapper
        val src = File(scriptPath)
        if (!src.exists()) {
            return "sh $scriptPath" to null
        }

        val wrapperDir = File(TMP_SCRIPT_DIR)
        val wrapperName =
            "wrapper_${UUID.randomUUID().toString().substring(0, 8)}_${src.name}"
        val wrapperFile = File(wrapperDir, wrapperName)

        // 写 wrapper：逐行处理
        val wrapped = try {
            val safeVdId = vdId
            val lines = src.readLines()
            val sb = StringBuilder(lines.size * 80 + 256)
            // 首行保留 shebang / 或 #!/system/bin/sh，否则写默认 sh
            val first = lines.firstOrNull()
            if (first?.startsWith("#!") == true) {
                sb.append(first).append('\n')
                lines.drop(1)
            } else {
                sb.append("#!/system/bin/sh\n")
                lines
            }.forEach { rawLine ->
                val line = appendDisplayToCommandLine(rawLine, safeVdId)
                sb.append(line).append('\n')
            }
            sb.toString()
        } catch (e: Exception) {
            Log.w(TAG, "build wrapper failed, fallback original script", e)
            val cmd = buildString {
                append("sh ")
                append(scriptPath)
                if (args.isNotEmpty()) {
                    append(" ")
                    append(args.joinToString(" "))
                }
            }
            return cmd to null
        }

        // 写 wrapper 到 wrapperFile（优先 app 直接写；失败则 fallback Shizuku base64，
        // 与 maybeCopyScriptToTmpForShizuku 的路径2 一致）
        val writeOk = try {
            if (!wrapperDir.exists()) wrapperDir.mkdirs()
            wrapperFile.writeText(wrapped)
            wrapperFile.setExecutable(true, false)
            wrapperFile.canRead()
        } catch (_: Exception) { false }

        val finalWrapperPath = if (writeOk) {
            wrapperFile.absolutePath
        } else {
            try {
                val encoded = android.util.Base64.encodeToString(
                    wrapped.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
                )
                val cmd =
                    "mkdir -p '$TMP_SCRIPT_DIR' && echo -n '$encoded' | base64 -d > '${wrapperFile.absolutePath}' && chmod 0755 '${wrapperFile.absolutePath}'"
                val res = rawOneShotShizuku(cmd)
                if (res == 0 && wrapperFile.exists() && wrapperFile.canRead()) {
                    wrapperFile.absolutePath
                } else {
                    Log.e(TAG, "Shizuku write wrapper failed exit=$res")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku wrapper write exception", e)
                null
            }
        }

        if (finalWrapperPath == null) {
            // wrapper 写失败，回退到原始脚本（损失自动 --display，但至少能跑）
            val cmd = buildString {
                append("sh ")
                append(scriptPath)
                if (args.isNotEmpty()) {
                    append(" ")
                    append(args.joinToString(" "))
                }
            }
            return cmd to null
        }

        val cmd = buildString {
            append("sh ")
            append(finalWrapperPath)
            if (args.isNotEmpty()) {
                append(" ")
                append(args.joinToString(" "))
            }
        }
        return cmd to finalWrapperPath
    }

    /**
     * 单行命令 --display 注入：
     *   1. 空行、注释行、连续空白直接返回
     *   2. 命中 [VD_DISPLAY_CMDS] 的前缀且不包含 `--display ` 则插入
     *   3. 插入位置：
     *        - `input `              → `input --display $vdId <rest>`
     *        - `uiautomator`        → `uiautomator --display $vdId <rest>`
     *        - `am start`           → `am start --display $vdId <rest>`
     *        - `am instrument` 等 am 子命令：`am <subcmd>` 后面插
     */
    internal fun appendDisplayToCommandLine(line: String, vdId: String): String {
        if (line.isBlank()) return line
        val trimmed = line.trimStart()
        // 跳过纯注释
        if (trimmed.startsWith('#')) return line
        // 命令前可能有前导空格（保持原状）
        val leading = line.substring(0, line.length - trimmed.length)
        // 命中候选命令（trimmed 以 VD_DISPLAY_CMDS[i] 开头）
        val prefix = VD_DISPLAY_CMDS.firstOrNull { trimmed.startsWith(it) } ?: return line
        // 整行已经有 --display 了就别再加（包含单字符 --display）
        if (trimmed.contains("--display")) return line

        val restAfterPrefix: String
        val displayInsertionPrefix: String
        val cmd = trimmed
        when {
            cmd.startsWith("input ") || cmd.startsWith("input\t") -> {
                restAfterPrefix = cmd.substring("input".length)
                displayInsertionPrefix = "input --display $vdId"
            }
            cmd.startsWith("uiautomator") -> {
                restAfterPrefix = cmd.substring("uiautomator".length)
                displayInsertionPrefix = "uiautomator --display $vdId"
            }
            else -> {
                // am start / am instrument / am startservice / am broadcast / am start-foreground-service
                val parts = prefix.trim()
                restAfterPrefix = cmd.substring(parts.length)
                displayInsertionPrefix = "$parts --display $vdId"
            }
        }

        // 拼接：确保 restAfterPrefix 首字符为空格以避免粘连
        val sep = if (restAfterPrefix.isEmpty() || restAfterPrefix.first() == ' ' || restAfterPrefix.first() == '\t') "" else " "
        return leading + displayInsertionPrefix + sep + restAfterPrefix
    }

    /**
     * 执行 ADB 命令（通过无线 ADB）
     * 注意：需要先通过 adb tcpip 5555 开启无线调试
     * @param command ADB 命令（不含 adb 前缀）
     * @param host 目标主机 IP，默认 localhost
     * @param port ADB 端口，默认 5555
     * @param useShizuku 是否使用 Shizuku 执行
     * @param env 附加环境变量
     * @return 执行结果
     */
    fun executeAdbCommand(
        command: String,
        host: String = "127.0.0.1",
        port: Int = 5555,
        useShizuku: Boolean = true,
        env: Map<String, String> = emptyMap()
    ): ShellResult {
        val fullCommand = "adb -s $host:$port $command"
        return execute(fullCommand, useShizuku, env)
    }

    /**
     * 使用 Shizuku 执行命令（流式：逐行回调 stdout/stderr）
     *
     * Shizuku v13.1+ 将 Shizuku.newProcess() 标记为 private(@hide)，无法直接调用。
     * 采用反射方式强制访问：getDeclaredMethod + isAccessible = true
     * ProGuard 已 keep rikka.shizuku.**，所以方法名不会混淆。
     *
     * 环境变量采用"命令前缀 export"方式注入（而不是 newProcess 的 env 参数），
     * 因为 Shizuku RemoteProcess 对 env=array 参数的处理因版本而异，
     * 在命令前拼 `export K1=V1; export K2=V2; <cmd>` 则 100% 可靠。
     */
    private fun executeWithShizukuStreaming(
        command: String,
        env: Map<String, String>,
        timeout: Long,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        return try {
            val fullCmd = buildEnvPrefix(env) + command
            val cmdArray = arrayOf("sh", "-c", fullCmd)

            // 通过反射调用 Shizuku.newProcess(cmdArray, env, dir)
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, cmdArray, null, null) as Process

            drainProcessStreams(process, timeout, onStdoutLine, onStderrLine)
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku streaming execute error: $command", e)
            onStderrLine("[EXCEPTION] ${e.message ?: "Shizuku execute error"}")
            -1
        }
    }

    /**
     * 使用普通方式执行命令（无 root/shizuku 权限，流式：逐行回调 stdout/stderr）
     */
    private fun executeWithNormalStreaming(
        command: String,
        env: Map<String, String>,
        timeout: Long,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        return try {
            val fullCmd = buildEnvPrefix(env) + command
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCmd))
            drainProcessStreams(process, timeout, onStdoutLine, onStderrLine)
        } catch (e: Exception) {
            Log.e(TAG, "Normal streaming execute error: $command", e)
            onStderrLine("[EXCEPTION] ${e.message ?: "Normal execute error"}")
            -1
        }
    }

    /**
     * 将 env Map 转成 "export K=V; " 前缀。
     *   - value 单引号转义：' → '\''
     *   - 结果保证可拼接在 sh -c 命令之前
     */
    private fun buildEnvPrefix(env: Map<String, String>): String {
        if (env.isEmpty()) return ""
        val sb = StringBuilder()
        for ((k, v) in env) {
            val safe = v.replace("'", "'\\''")
            sb.append("export ").append(k).append("='").append(safe).append("'; ")
        }
        return sb.toString()
    }

    /**
     * 读取 Process 的 stdout/stderr 流，并逐行回调给 onStdoutLine / onStderrLine。
     * 内部在独立线程读取两条流，避免死锁（Process 输出缓冲区满时阻塞）。
     *
     * @return 进程退出码：0 成功；-2 超时；其他负数 执行异常
     */
    private fun drainProcessStreams(
        process: Process,
        timeout: Long,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        val stdoutThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        try {
                            onStdoutLine(line!!)
                        } catch (t: Throwable) {
                            Log.w(TAG, "onStdoutLine callback exception", t)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read stdout error", e)
            }
        }

        val stderrThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        try {
                            onStderrLine(line!!)
                        } catch (t: Throwable) {
                            Log.w(TAG, "onStderrLine callback exception", t)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read stderr error", e)
            }
        }

        stdoutThread.start()
        stderrThread.start()

        // 等待进程结束或超时
        // ★关键修复★：
        //   老实现用 process.exitValue() 轮询并只 catch IllegalThreadStateException。
        //   但 Shizuku RemoteProcess / Android ProcessManager$ProcessImpl.exitValue()
        //   在 MIUI/HyperOS 上抛 IllegalStateException("process hasn't exited")（不是
        //   IllegalThreadStateException），异常从循环逃逸到外层 catch，
        //   导致 stderr 输出 "[EXCEPTION] process hasn't exited"，exit=-1，
        //   实际 sh 进程根本没机会执行完命令。
        //   修复：catch 范围扩大到 RuntimeException，覆盖所有"进程未退出"类异常。
        val startTime = System.currentTimeMillis()
        var exitCode = -1
        var finished = false

        while (!finished && (System.currentTimeMillis() - startTime) < timeout) {
            try {
                exitCode = process.exitValue()
                finished = true
            } catch (e: RuntimeException) {
                // 涵盖 IllegalThreadStateException + IllegalStateException
                // 及其他 ROM 自定义"进程未退出"异常
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        if (!finished) {
            // 超时，销毁进程
            try { process.destroy() } catch (_: Exception) {}
            try { stdoutThread.join(1000) } catch (_: InterruptedException) {}
            try { stderrThread.join(1000) } catch (_: InterruptedException) {}
            return -2
        }

        try { stdoutThread.join(timeout) } catch (_: InterruptedException) {}
        try { stderrThread.join(timeout) } catch (_: InterruptedException) {}

        return exitCode
    }
}
