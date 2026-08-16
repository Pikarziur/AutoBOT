package com.autobot.app.util

import android.util.Log
import com.autobot.app.manager.ShizukuManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Shell 执行工具类
 * 支持通过 Shizuku 或普通方式执行 shell 命令和 sh 脚本文件
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
     * 执行 shell 命令
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
        return try {
            if (useShizuku && ShizukuManager.isShizukuGranted()) {
                executeWithShizuku(command, timeout)
            } else {
                executeWithNormal(command, timeout)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execute command error: $command", e)
            ShellResult(-1, "", e.message ?: "Unknown error")
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
     * 使用 Shizuku 执行命令
     *
     * Shizuku v13.1+ 将 Shizuku.newProcess() 标记为 private(@hide)，无法直接调用。
     * 采用反射方式强制访问：getDeclaredMethod + isAccessible = true
     * ProGuard 已 keep rikka.shizuku.**，所以方法名不会混淆。
     */
    private fun executeWithShizuku(command: String, timeout: Long): ShellResult {
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

            // 读取输出
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdoutBuilder.append(line).append("\n")
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
                            stderrBuilder.append(line).append("\n")
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
                return ShellResult(-2, stdoutBuilder.toString().trim(), "Command timeout")
            }

            stdoutThread.join(timeout)
            stderrThread.join(timeout)

            ShellResult(
                exitCode,
                stdoutBuilder.toString().trim(),
                stderrBuilder.toString().trim()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku execute error: $command", e)
            ShellResult(-1, "", e.message ?: "Shizuku execute error")
        }
    }

    /**
     * 使用普通方式执行命令（无 root/shizuku 权限）
     */
    private fun executeWithNormal(command: String, timeout: Long): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdoutBuilder.append(line).append("\n")
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
                            stderrBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Read stderr error", e)
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val startTime = System.currentTimeMillis()
            var exitCode = -1
            var finished = false

            while (!finished && (System.currentTimeMillis() - startTime) < timeout) {
                try {
                    exitCode = process.exitValue()
                    finished = true
                } catch (e: IllegalThreadStateException) {
                    Thread.sleep(100)
                }
            }

            if (!finished) {
                process.destroy()
                stdoutThread.join(1000)
                stderrThread.join(1000)
                return ShellResult(-2, stdoutBuilder.toString().trim(), "Command timeout")
            }

            stdoutThread.join(timeout)
            stderrThread.join(timeout)

            ShellResult(
                exitCode,
                stdoutBuilder.toString().trim(),
                stderrBuilder.toString().trim()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Normal execute error: $command", e)
            ShellResult(-1, "", e.message ?: "Normal execute error")
        }
    }
}
