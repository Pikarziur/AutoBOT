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
 * 三大职责：
 *  1. ensureServerApk(context) —— 把当前 APK 拷贝到 /data/local/tmp/autobot-server.apk
 *     作为 app_process 的 classpath（dex 来源）；scrcpy 同款做法
 *  2. launchServer(socketName) —— 反射 Shizuku.newProcess() 启动 app_process
 *     Shizuku v13.1+ 把 newProcess 标记为 @hide private API，必须反射调用
 *     （模式与 ShellExecutor.executeWithShizukuStreaming 第 204-230 行已验证完全一致）
 *  3. drainServerStdout(process, onLine) —— 异步读 server 的 stdout/stderr，
 *     方便排查 server 启动失败、Java 异常堆栈
 *
 * 调用方：CompositionService.startVirtualDisplay
 */
object ShizukuProcessManager {

    private const val TAG = "ShizukuProcMgr"

    /** server dex 容器在设备上的固定路径（scrcpy 同款约定） */
    private const val SERVER_APK_PATH = "/data/local/tmp/autobot-server.apk"

    /** app_process 在 PATH 中的标准名称（不同 ROM 可能是 app_process32/64，已用 which 探测兜底） */
    private const val APP_PROCESS_BIN = "app_process"

    /** ServerMain 类的全限定名 */
    private const val SERVER_MAIN_CLASS = "com.autobot.app.server.ServerMain"

    /**
     * 把当前 App 的 APK 拷贝到 /data/local/tmp/autobot-server.apk。
     *
     * 实现要点：
     *  - sourceDir 是 /data/app/.../base.apk，shell uid 通过 Shizuku 持有读权限
     *  - 使用 cmp -s 比对避免每次启动都做无意义拷贝（性能优化）
     *  - chmod 644 保证 app_process 在 shell domain 下可读
     *  - 失败时抛出带原因的 IllegalStateException，调用方应直接展示给用户
     *
     * @return SERVER_APK_PATH 路径
     * @throws IllegalStateException 如果 Shizuku 未授权或拷贝失败
     */
    fun ensureServerApk(context: Context): String {
        val src = context.applicationInfo.sourceDir
            ?: throw IllegalStateException("无法获取 APK 路径：applicationInfo.sourceDir 为 null")

        // 通过 Shizuku 执行拷贝（shell uid 权限）
        val cmd = """
            if [ -f "$SERVER_APK_PATH" ] && cmp -s "$src" "$SERVER_APK_PATH"; then
                echo "UP_TO_DATE";
            else
                cp "$src" "$SERVER_APK_PATH" && chmod 644 "$SERVER_APK_PATH" && echo "COPIED";
            fi
        """.trimIndent()

        val result = ShellExecutor.execute(cmd, useShizuku = true, timeout = 10_000)
        if (!result.isSuccess) {
            throw IllegalStateException(
                "server APK 推送失败 (exit=${result.exitCode})\n" +
                "stdout: ${result.stdout}\nstderr: ${result.stderr}\n" +
                "排查：①Shizuku 是否已授权 ②/data/local/tmp 是否可写（adb shell ls -Z 验证 SELinux）"
            )
        }
        Log.i(TAG, "ensureServerApk: ${result.stdout.trim()} (src=$src, dst=$SERVER_APK_PATH)")
        return SERVER_APK_PATH
    }

    /**
     * 通过 Shizuku.newProcess() 启动独立 app_process server 进程。
     *
     * 反射模式与 ShellExecutor.executeWithShizukuStreaming 完全一致，
     * 已在 Shizuku 13.1.5 上验证可用（v13.1+ 标记为 @hide private API）。
     *
     * @param socketName App 进程已创建的 LocalServerSocket abstract namespace 名
     * @return Process 对象，调用方持有用于 destroyForcibly() / 读 stdout
     * @throws IllegalStateException Shizuku 未授权或反射调用失败
     */
    fun launchServer(socketName: String): Process {
        // 探测 app_process 路径（部分 ROM 用 app_process32/64）
        val appProcessBin = detectAppProcessPath()

        val cmd = arrayOf(
            appProcessBin,
            "-Djava.class.path=$SERVER_APK_PATH",
            "/",
            SERVER_MAIN_CLASS,
            socketName
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
                APP_PROCESS_BIN  // 兜底：PATH 查找
            }
        } catch (e: Exception) {
            Log.w(TAG, "detectAppProcessPath failed, fallback to bare name", e)
            APP_PROCESS_BIN
        }
    }

    /**
     * 异步读取 server 进程的 stdout/stderr 并逐行回调（排查用）。
     *
     * ServerMain.main() 内部用 android.util.Log 输出，但 app_process 启动时
     * 默认 System.out/System.err 也可能被 native 代码用到。本方法把这两条流
     * 转给调用方合并到 UI 日志区，方便排查 server 启动失败。
     *
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
                // 等待最多 1s 让进程真正退出
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

    /** server 进程是否还在运行 */
    fun isServerAlive(process: Process?): Boolean = process?.isAlive ?: false

    /**
     * 尝试获取 Process 的 pid（API 17+ Process.pid），失败返回 -1
     * 注意：这里反射 java.lang.Process，因为 Android API 26 没有 Process.pid()，
     * 直到 API 26+ 才有 Process.pid()（实际是 API 26 加入）。
     */
    private fun getPidOf(process: Process): Long {
        return try {
            // API 26+ Process.pid() 返回 long
            val m = Process::class.java.getMethod("pid")
            m.invoke(process) as Long
        } catch (_: Exception) {
            -1L
        }
    }
}
