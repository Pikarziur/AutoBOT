package com.autobot.app.manager

import android.content.Context
import android.util.Log
import com.autobot.app.util.ShellExecutor
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku.newProcess 进程管理器
 *
 * 负责通过 Shizuku 启动独立 shell uid 的 `app_process` 进程，让虚拟显示器创建代码
 * 在 server 进程内执行（与 scrcpy / MAA-Meow 架构对齐）。
 *
 * ★关键架构变更（修复 LocalSocket Permission denied）★：
 *   旧方案 ❌：App 创建 LocalServerSocket(abstract) → server 进程用 LocalSocket.connect()
 *              → Android 10+ SELinux 禁止 shell domain 连接 untrusted_app domain 的
 *                abstract namespace socket → IOException: Permission denied
 *   新方案 ✅：直接用 Shizuku.newProcess 返回的 Process 对象的 stdin/stdout pipe
 *              （App.process.outputStream ↔ server.System.in，
 *               App.process.inputStream  ↔ server.System.out）
 *              这是 scrcpy 同款架构，无需任何 socket，且 Android 唯一允许的
 *              shell↔untrusted_app 跨进程通信方式
 */
object ShizukuProcessManager {

    private const val TAG = "ShizukuProcMgr"

    private const val SERVER_APK_PATH = "/data/local/tmp/autobot-server.apk"

    private const val APP_PROCESS_BIN = "app_process"

    private const val SERVER_MAIN_CLASS = "com.autobot.app.server.ServerMain"

    /**
     * 把当前 App 的 APK 拷贝到 /data/local/tmp/autobot-server.apk。
     *
     * 实现要点：
     *  - **拆分为多条独立简单命令**而非单个 if/then/else/fi 脚本：
     *    MIUI/HyperOS 上 Shizuku.newProcess 对多行 sh 脚本解析有兼容性问题，
     *    单条简单命令更稳健 + 错误诊断更精确
     *
     * @return SERVER_APK_PATH 路径
     * @throws IllegalStateException 如果 Shizuku 未授权或拷贝失败
     */
    fun ensureServerApk(context: Context): String {
        val src = context.applicationInfo.sourceDir
            ?: throw IllegalStateException("无法获取 APK 路径：applicationInfo.sourceDir 为 null")

        // 单条独立命令：cmp 返回 0 表示内容相同，非 0 表示不同/不存在
        val cmpResult = ShellExecutor.execute(
            "cmp -s \"$src\" \"$SERVER_APK_PATH\"",
            useShizuku = true, timeout = 5_000
        )
        if (cmpResult.isSuccess) {
            Log.i(TAG, "ensureServerApk: APK already up-to-date (cmp match), skip copy. dst=$SERVER_APK_PATH")
            return SERVER_APK_PATH
        }

        // cmp 失败原因可能是：① 文件不存在 ② 内容不同 ③ 权限不足；这三种情况都需要继续走 cp 流程
        // 只有 Shizuku 调用本身失败才直接抛 (ShellExecutor 在 Shizuku 反射异常时返回 -1 + stderr 含 [EXCEPTION])
        if (cmpResult.exitCode == -1 && cmpResult.stderr.contains("[EXCEPTION]")) {
            throw IllegalStateException(
                "Shizuku 调用失败，无法检查 server APK：${cmpResult.stderr}\n" +
                "排查：①Shizuku 是否已授权 ②Shizuku 服务是否在运行"
            )
        }
        Log.i(TAG, "ensureServerApk: cmp mismatch (exit=${cmpResult.exitCode}), will copy. stderr=${cmpResult.stderr}")

        // 单条命令，避免 if/then 复杂脚本（MIUI/HyperOS 兼容性）
        val cpResult = ShellExecutor.execute(
            "cp \"$src\" \"$SERVER_APK_PATH\"",
            useShizuku = true, timeout = 10_000
        )
        if (!cpResult.isSuccess) {
            throw IllegalStateException(
                "cp 推送 server APK 失败 (exit=${cpResult.exitCode})\n" +
                "src=$src\n" +
                "dst=$SERVER_APK_PATH\n" +
                "stderr: ${cpResult.stderr}\n" +
                "排查：①/data/local/tmp 是否可写（adb shell ls -ld /data/local/tmp）\n" +
                "    ②SELinux 是否拦截（adb shell getenforce）\n" +
                "    ③源 APK 路径是否正确（${src}）"
            )
        }

        // app_process 在 shell domain 下读 APK 需要 644
        val chmodResult = ShellExecutor.execute(
            "chmod 644 \"$SERVER_APK_PATH\"",
            useShizuku = true, timeout = 3_000
        )
        if (!chmodResult.isSuccess) {
            Log.w(TAG, "chmod 644 失败 (exit=${chmodResult.exitCode}): ${chmodResult.stderr}")
        } else {
            Log.i(TAG, "ensureServerApk: cp + chmod OK, src=$src, dst=$SERVER_APK_PATH")
        }

        // 校验拷贝结果（防御性编程）
        val verifyResult = ShellExecutor.execute(
            "[ -f \"$SERVER_APK_PATH\" ] && [ -s \"$SERVER_APK_PATH\" ] && echo OK || echo FAIL",
            useShizuku = true, timeout = 3_000
        )
        if (!verifyResult.isSuccess || !verifyResult.stdout.contains("OK")) {
            throw IllegalStateException(
                "server APK 拷贝后校验失败: stdout=${verifyResult.stdout}, stderr=${verifyResult.stderr}"
            )
        }

        return SERVER_APK_PATH
    }

    /**
     * 通过 Shizuku.newProcess() 启动独立 app_process server 进程。
     *
     * Shizuku v13.1+ 把 newProcess 标记为 @hide private API，必须反射调用。
     * 已在 Shizuku 13.1.5 上验证可用。
     *
     * @return Process 对象，调用方持有用于：
     *   - process.outputStream 写 MSG_CREATE_VD / PING / FRAME_ACK / RELEASE_VD
     *   - process.inputStream  读 MSG_CREATE_VD_RESP / MSG_FRAME / MSG_PONG / MSG_RELEASE_VD_RESP
     *   - process.errorStream  需要 drain，否则 server 写 stderr 满会阻塞
     *   - process.destroyForcibly() 销毁 server 进程
     * @throws IllegalStateException Shizuku 未授权或反射调用失败
     */
    fun launchServer(): Process {
        val appProcessBin = detectAppProcessPath()

        // stdin/stdout pipe 是 Shizuku.newProcess 内部自动建立的，不需要 socket name
        val cmd = arrayOf(
            appProcessBin,
            "-Djava.class.path=$SERVER_APK_PATH",
            "/",
            SERVER_MAIN_CLASS
        )

        return try {
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, cmd, null, null) as Process
            Log.i(TAG, "launchServer: app_process started, cmd=${cmd.joinToString(" ")}, pid=${getPidOf(process)}")
            process
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("Shizuku 类未找到：${e.message}（请检查 dev.rikka.shizuku:api 依赖）", e)
        } catch (e: NoSuchMethodException) {
            throw IllegalStateException(
                "Shizuku.newProcess 方法未找到：${e.message}\n" +
                "排查：Shizuku 版本不兼容（当前依赖 13.1.5），尝试升级到更高版本", e
            )
        } catch (e: Exception) {
            throw IllegalStateException("Shizuku.newProcess 反射调用失败：${e.message}", e)
        }
    }

    /**
     * 探测 app_process 二进制路径。
     * 大多数 ROM 直接用 app_process（在 PATH 中），少数 ROM 用 app_process32/64。
     */
    private fun detectAppProcessPath(): String {
        return try {
            // 用 which 探测（通过 Shizuku 在 shell uid 下执行）
            val result = ShellExecutor.execute("which $APP_PROCESS_BIN", useShizuku = true, timeout = 3_000)
            if (result.isSuccess && result.stdout.isNotBlank()) {
                val path = result.stdout.trim().lines().first()
                Log.i(TAG, "detectAppProcessPath: which → $path")
                path
            } else {
                APP_PROCESS_BIN
            }
        } catch (e: Exception) {
            Log.w(TAG, "detectAppProcessPath failed, fallback to bare name", e)
            APP_PROCESS_BIN
        }
    }

    /**
     * 异步读取 server 进程的 stdout/stderr 并逐行回调（排查用）。
     * 返回两个 Thread（stdoutThread, stderrThread），调用方可在 destroy 时 interrupt。
     */
    fun drainServerStdout(process: Process, onLine: (String) -> Unit): Pair<Thread, Thread> {
        val stdoutThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        try { onLine("[server.out] $line") } catch (_: Throwable) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Read server stdout EOF/exception: ${e.message}")
            }
        }

        val stderrThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        try { onLine("[server.err] $line") } catch (_: Throwable) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Read server stderr EOF/exception: ${e.message}")
            }
        }

        stdoutThread.isDaemon = true
        stderrThread.isDaemon = true
        stdoutThread.name = "autobot-server-stdout"
        stderrThread.name = "autobot-server-stderr"
        stdoutThread.start()
        stderrThread.start()
        return stdoutThread to stderrThread
    }

    /**
     * 销毁 server 进程。
     * destroyForcibly 比 destroy 更暴力（SIGKILL 而非 SIGTERM），保证 server 真正退出。
     */
    fun destroyServer(process: Process?) {
        process ?: return
        try {
            if (process.isAlive) {
                process.destroyForcibly()
                val deadline = System.currentTimeMillis() + 1000
                while (process.isAlive && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
                Log.i(TAG, "destroyServer: alive=${process.isAlive}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "destroyServer exception", e)
        }
    }

    fun isServerAlive(process: Process?): Boolean = process?.isAlive ?: false

    /**
     * 尝试获取 Process 的 pid（API 26+ Process.pid），失败返回 -1。
     * 这里反射 java.lang.Process，因为 Android API 26 之前没有 Process.pid()。
     */
    private fun getPidOf(process: Process): Long {
        return try {
            val m = Process::class.java.getMethod("pid")
            m.invoke(process) as Long
        } catch (_: Exception) {
            -1L
        }
    }
}
