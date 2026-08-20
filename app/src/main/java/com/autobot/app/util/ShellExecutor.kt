package com.autobot.app.util

import android.util.Log
import com.autobot.app.manager.ShizukuManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Shell 执行工具类。
 *
 * 重要变更：旧版还执行 .sh 脚本并注入 `--display` / 坐标缩放；新版"触摸模拟"已改走
 * App 进程内 MotionEvent 注入（TaskExecutor + CompositionService），本类回归"纯命令执行器"职责：
 *   - execute() / executeStreaming()：通过 Shizuku 或 Runtime 执行任意 shell 命令
 *   - 供 AppManager（am start --display）、TaskIsolator（dumpsys activity activities）、
 *     ShizukuProcessManager（cp/chmod/which）等使用
 *   - 不再处理 .sh 脚本文件，不再注入 --display，不再做坐标缩放
 */
object ShellExecutor {

    private const val TAG = "ShellExecutor"

    /** 执行结果数据类。 */
    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /** 执行 shell 命令（一次性模式：命令结束才返回结果）。 */
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
     * 流式命令执行的外部取消句柄。
     *
     * 踩坑：仅 cancelAndJoin 协程 Job 并不足以杀掉 Shizuku/Android 下的 RemoteProcess
     * （sh 还会继续跑），必须显式 process.destroy()。drainProcessStreams 检测到
     * cancelled=true 后会立即 destroy 子进程退出。
     */
    class CancelHandle {
        private val requested = AtomicBoolean(false)
        private val processRef = AtomicReference<Process?>(null)

        /** 标记"取消请求"，下次 drainProcessStreams 轮询时会 destroy 进程。 */
        fun cancel() {
            requested.set(true)
            processRef.get()?.let { p ->
                runCatching { p.destroy() }
            }
        }

        internal fun isRequested(): Boolean = requested.get()
        internal fun bindProcess(p: Process) { processRef.set(p) }
        internal fun unbindProcess() { processRef.set(null) }
    }

    fun executeStreaming(
        command: String,
        useShizuku: Boolean = true,
        env: Map<String, String> = emptyMap(),
        timeout: Long = 600_000L,
        cancel: CancelHandle? = null,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        return try {
            executeStreamingInternal(command, useShizuku, env, timeout, cancel, onStdoutLine, onStderrLine)
        } catch (e: Exception) {
            Log.e(TAG, "Streaming execute error: $command", e)
            -1
        }
    }

    /** 流式执行核心（被 `execute` 和 `executeStreaming` 共同复用）。 */
    private fun executeStreamingInternal(
        command: String,
        useShizuku: Boolean,
        env: Map<String, String>,
        timeout: Long,
        cancel: CancelHandle? = null,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        return if (useShizuku && ShizukuManager.isShizukuGranted()) {
            executeWithShizukuStreaming(command, env, timeout, cancel, onStdoutLine, onStderrLine)
        } else {
            executeWithNormalStreaming(command, env, timeout, cancel, onStdoutLine, onStderrLine)
        }
    }

    /** 执行 ADB 命令（通过无线 ADB）；需先 `adb tcpip 5555` 开启无线调试。 */
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
     * 使用 Shizuku 执行命令（流式：逐行回调 stdout/stderr）。
     *
     * 踩坑 1：Shizuku v13.1+ 将 Shizuku.newProcess() 标记为 private(@hide)，无法直接调用，
     * 采用反射 getDeclaredMethod + isAccessible=true 强制访问；ProGuard 已 keep rikka.shizuku.**，
     * 方法名不会混淆。
     *
     * 踩坑 2：环境变量采用"命令前缀 export"方式注入（而不是 newProcess 的 env 参数），
     * 因为 Shizuku RemoteProcess 对 env=array 参数的处理因版本而异，
     * 在命令前拼 `export K1=V1; export K2=V2; <cmd>` 则 100% 可靠。
     */
    private fun executeWithShizukuStreaming(
        command: String,
        env: Map<String, String>,
        timeout: Long,
        cancel: CancelHandle?,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        return try {
            val fullCmd = buildEnvPrefix(env) + command
            val cmdArray = arrayOf("sh", "-c", fullCmd)

            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, cmdArray, null, null) as Process
            cancel?.bindProcess(process)
            try {
                drainProcessStreams(process, timeout, cancel, onStdoutLine, onStderrLine)
            } finally {
                cancel?.unbindProcess()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku streaming execute error: $command", e)
            onStderrLine("[EXCEPTION] ${e.message ?: "Shizuku execute error"}")
            -1
        }
    }

    /** 使用普通方式执行命令（无 root/shizuku 权限，流式：逐行回调 stdout/stderr）。 */
    private fun executeWithNormalStreaming(
        command: String,
        env: Map<String, String>,
        timeout: Long,
        cancel: CancelHandle?,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        return try {
            val fullCmd = buildEnvPrefix(env) + command
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCmd))
            cancel?.bindProcess(process)
            try {
                drainProcessStreams(process, timeout, cancel, onStdoutLine, onStderrLine)
            } finally {
                cancel?.unbindProcess()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Normal streaming execute error: $command", e)
            onStderrLine("[EXCEPTION] ${e.message ?: "Normal execute error"}")
            -1
        }
    }

    /**
     * 将 env Map 转成 "export K=V; " 前缀。
     * value 单引号转义：' → '\''；结果保证可拼接在 sh -c 命令之前。
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
     * 读取 Process 的 stdout/stderr 流并逐行回调；内部在独立线程读取两条流避免死锁。
     *
     * @return 进程退出码：0 成功；-2 超时；-3 外部取消；其他负数 执行异常
     */
    private fun drainProcessStreams(
        process: Process,
        timeout: Long,
        cancel: CancelHandle?,
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

        // ★关键修复★：catch 范围扩大到 RuntimeException。
        //   老实现用 process.exitValue() 轮询并只 catch IllegalThreadStateException，
        //   但 Shizuku RemoteProcess / Android ProcessManager$ProcessImpl.exitValue()
        //   在 MIUI/HyperOS 上抛 IllegalStateException("process hasn't exited")（不是
        //   IllegalThreadStateException），异常从循环逃逸到外层 catch，
        //   导致 stderr 输出 "[EXCEPTION] process hasn't exited"，exit=-1，
        //   实际 sh 进程根本没机会执行完命令。
        val startTime = System.currentTimeMillis()
        var exitCode = -1
        var finished = false
        val alreadyCancelled = cancel?.isRequested() == true

        if (alreadyCancelled) {
            try { process.destroy() } catch (_: Exception) {}
            try { stdoutThread.join(1000) } catch (_: InterruptedException) {}
            try { stderrThread.join(1000) } catch (_: InterruptedException) {}
            return -3
        }

        while (!finished && (System.currentTimeMillis() - startTime) < timeout) {
            if (cancel?.isRequested() == true) {
                try { process.destroy() } catch (_: Exception) {}
                try { stdoutThread.join(1000) } catch (_: InterruptedException) {}
                try { stderrThread.join(1000) } catch (_: InterruptedException) {}
                return -3
            }
            try {
                exitCode = process.exitValue()
                finished = true
            } catch (e: RuntimeException) {
                // 涵盖 IllegalThreadStateException + IllegalStateException 及其他 ROM 自定义"进程未退出"异常
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        if (!finished) {
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
