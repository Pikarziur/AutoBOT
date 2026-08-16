package com.autobot.app.util

import android.util.Log
import com.autobot.app.manager.ShizukuManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

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
     * @param timeout 超时时间（毫秒），默认 30 秒
     * @return 执行结果
     */
    fun execute(
        command: String,
        useShizuku: Boolean = true,
        timeout: Long = 30000
    ): ShellResult {
        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()
        val exitCode = executeStreamingInternal(
            command, useShizuku, timeout,
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
     * @param timeout   超时时间（毫秒），默认 10 分钟（脚本任务通常比较长）
     * @param onStdoutLine stdout 每一行的回调（参数为单行，不含换行符）；IO 线程调用
     * @param onStderrLine stderr 每一行的回调（参数为单行，不含换行符）；IO 线程调用
     * @return 进程退出码：0 成功，-2 超时，其他负数 执行异常
     */
    fun executeStreaming(
        command: String,
        useShizuku: Boolean = true,
        timeout: Long = 600_000L,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        return try {
            executeStreamingInternal(command, useShizuku, timeout, onStdoutLine, onStderrLine)
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
        timeout: Long,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        return if (useShizuku && ShizukuManager.isShizukuGranted()) {
            executeWithShizukuStreaming(command, timeout, onStdoutLine, onStderrLine)
        } else {
            executeWithNormalStreaming(command, timeout, onStdoutLine, onStderrLine)
        }
    }

    /**
     * 执行本地 sh 脚本文件
     * @param scriptPath sh 脚本文件路径
     * @param useShizuku 是否使用 Shizuku 权限执行
     * @param args 传递给脚本的参数
     * @return 执行结果
     */
    fun executeScript(
        scriptPath: String,
        useShizuku: Boolean = true,
        vararg args: String
    ): ShellResult {
        val scriptFile = File(scriptPath)
        if (!scriptFile.exists()) {
            return ShellResult(-1, "", "Script file not found: $scriptPath")
        }

        // 组装命令：sh scriptPath args...
        val command = buildString {
            append("sh ")
            append(scriptPath)
            if (args.isNotEmpty()) {
                append(" ")
                append(args.joinToString(" "))
            }
        }

        return execute(command, useShizuku)
    }

    /**
     * 执行本地 sh 脚本文件（流式模式：stdout/stderr 逐行回调）
     *
     * @param scriptPath sh 脚本文件路径
     * @param useShizuku 是否使用 Shizuku 权限执行
     * @param args 传递给脚本的参数
     * @param timeout 超时（毫秒），默认 10 分钟
     * @param onStdoutLine stdout 每一行的回调；IO 线程调用
     * @param onStderrLine stderr 每一行的回调；IO 线程调用
     * @return 进程退出码
     */
    fun executeScriptStreaming(
        scriptPath: String,
        useShizuku: Boolean = true,
        vararg args: String,
        timeout: Long = 600_000L,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        val scriptFile = File(scriptPath)
        if (!scriptFile.exists()) {
            onStderrLine("[ERROR] Script file not found: $scriptPath")
            return -1
        }

        val command = buildString {
            append("sh ")
            append(scriptPath)
            if (args.isNotEmpty()) {
                append(" ")
                append(args.joinToString(" "))
            }
        }

        return executeStreaming(
            command, useShizuku, timeout,
            onStdoutLine = onStdoutLine,
            onStderrLine = onStderrLine
        )
    }

    /**
     * 执行 ADB 命令（通过无线 ADB）
     * 注意：需要先通过 adb tcpip 5555 开启无线调试
     * @param command ADB 命令（不含 adb 前缀）
     * @param host 目标主机 IP，默认 localhost
     * @param port ADB 端口，默认 5555
     * @param useShizuku 是否使用 Shizuku 执行
     * @return 执行结果
     */
    fun executeAdbCommand(
        command: String,
        host: String = "127.0.0.1",
        port: Int = 5555,
        useShizuku: Boolean = true
    ): ShellResult {
        val fullCommand = "adb -s $host:$port $command"
        return execute(fullCommand, useShizuku)
    }

    /**
     * 使用 Shizuku 执行命令（流式：逐行回调 stdout/stderr）
     *
     * Shizuku v13.1+ 将 Shizuku.newProcess() 标记为 private(@hide)，无法直接调用。
     * 采用反射方式强制访问：getDeclaredMethod + isAccessible = true
     * ProGuard 已 keep rikka.shizuku.**，所以方法名不会混淆。
     */
    private fun executeWithShizukuStreaming(
        command: String,
        timeout: Long,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        return try {
            val cmdArray = arrayOf("sh", "-c", command)

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
        timeout: Long,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            drainProcessStreams(process, timeout, onStdoutLine, onStderrLine)
        } catch (e: Exception) {
            Log.e(TAG, "Normal streaming execute error: $command", e)
            onStderrLine("[EXCEPTION] ${e.message ?: "Normal execute error"}")
            -1
        }
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
        val startTime = System.currentTimeMillis()
        var exitCode = -1
        var finished = false

        while (!finished && (System.currentTimeMillis() - startTime) < timeout) {
            try {
                exitCode = process.exitValue()
                finished = true
            } catch (e: IllegalThreadStateException) {
                // 进程仍在运行，等待一小会儿
                Thread.sleep(100)
            }
        }

        if (!finished) {
            // 超时，销毁进程
            process.destroy()
            stdoutThread.join(1000)
            stderrThread.join(1000)
            return -2
        }

        stdoutThread.join(timeout)
        stderrThread.join(timeout)

        return exitCode
    }
}
