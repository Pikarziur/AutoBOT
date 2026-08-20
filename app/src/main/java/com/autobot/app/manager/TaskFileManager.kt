package com.autobot.app.manager

import android.content.Context
import android.util.Log
import com.autobot.app.model.TaskFile
import java.io.File

/**
 * 任务文件管理器
 *
 * 取代旧版 ScriptTaskManager（adb shell 模式，已删除）。
 *
 * 任务文件 = 一份 JSON 描述的 MotionEvent 动作序列（tap/swipe/wait/back），
 * 完全通过 server 进程的 IInputManager.injectInputEvent 注入到 VD，
 * 与 adb shell / `input tap` 完全解耦。
 *
 * ★文件存放点（app 内部存储，无需运行时权限）★：
 *   filesDir/tasks/             ← 任务文件目录
 *     ├ test.json               ← 占位测试任务（assets 预置，启动时装载）
 *     ├ <user-imported>.json    ← 用户后续自定义任务
 *     └ ...
 *
 * 预置任务来源：assets 下的 tasks 目录中的 .json 文件 —— 启动时若 filesDir/tasks/ 不存在
 * 或对应文件缺失/内容不一致，会用 assets 最新内容覆盖落盘，保证
 * "改了 assets 任务文件 → 下次启动 app 即生效"，无需清数据。
 *
 * 数据模型：[TaskFile]（id 取文件名去掉 .json，作为下拉列表 key）
 */
object TaskFileManager {

    private const val TAG = "TaskFileManager"

    /** 任务文件目录名（在 filesDir 下） */
    private const val TASKS_DIR_NAME = "tasks"

    /** 预置任务在 assets 中的目录名 */
    private const val ASSETS_TASKS_DIR = "tasks"

    /** 任务文件扩展名 */
    private const val FILE_EXT = ".json"

    /** 内存缓存：避免每次操作都重新解析 */
    private val cachedTasks = mutableListOf<TaskFile>()

    /** 文件读写锁 */
    private val fileLock = Any()

    /** App Context（由 init() 注入） */
    @Volatile
    private var appContext: Context? = null

    /**
     * 初始化：必须在 Application.onCreate 中调用一次
     *
     * 流程：
     *   1. 注入 ApplicationContext
     *   2. 确保 filesDir/tasks/ 目录存在
     *   3. 从 assets/tasks/ 装载预置任务（覆盖式更新）
     *   4. 扫描 filesDir/tasks/ 全部 .json 文件解析到内存
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        ensureTasksDir()
        loadBundledTasks()
        loadFromDisk()
    }

    private fun ctx(): Context =
        appContext ?: throw IllegalStateException("TaskFileManager.init() not called")

    /** 任务文件目录：filesDir/tasks/ */
    private fun tasksDirFile(): File = File(ctx().filesDir, TASKS_DIR_NAME)

    /** 确保 filesDir/tasks/ 目录存在 */
    private fun ensureTasksDir() {
        tasksDirFile().mkdirs()
    }

    /**
     * 从 assets/tasks/ 装载预置任务文件
     *
     * 策略（与旧版脚本装载逻辑一致，保证幂等）：
     *   - 扫描 assets/tasks/ 下所有 .json 文件
     *   - 对每个文件，若 filesDir/tasks/<同名>.json 不存在或内容不同，
     *     用 assets 内容覆盖落盘 → 保证改 assets 即生效
     *   - 已存在且内容一致则跳过
     */
    private fun loadBundledTasks() {
        val context = ctx()
        val assetFiles: Array<String> = try {
            context.assets.list(ASSETS_TASKS_DIR) ?: emptyArray()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list assets/$ASSETS_TASKS_DIR/", e)
            return
        }
        if (assetFiles.isEmpty()) {
            Log.i(TAG, "No bundled .json tasks in assets/$ASSETS_TASKS_DIR/")
            return
        }

        var updated = 0
        var skipped = 0
        for (filename in assetFiles) {
            // 仅处理 .json 文件
            if (!filename.endsWith(FILE_EXT, ignoreCase = true)) continue

            val content = try {
                context.assets.open("$ASSETS_TASKS_DIR/$filename").use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read assets/$ASSETS_TASKS_DIR/$filename", e)
                continue
            }

            val target = File(tasksDirFile(), filename)
            val needWrite = !target.exists() ||
                target.readText() != content
            if (needWrite) {
                try {
                    target.writeText(content)
                    updated++
                    Log.i(TAG, "Loaded bundled task: $filename -> ${target.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write bundled task $filename", e)
                }
            } else {
                skipped++
            }
        }
        Log.i(TAG, "loadBundledTasks done: updated=$updated, skipped=$skipped")
    }

    /**
     * 扫描 filesDir/tasks/ 全部 .json 文件解析到内存缓存
     *
     * 解析失败的文件会被跳过（不会影响其他任务加载）。
     */
    private fun loadFromDisk() {
        synchronized(fileLock) {
            cachedTasks.clear()
            val dir = tasksDirFile()
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(FILE_EXT, ignoreCase = true) }
                ?: emptyArray()
            for (file in files.sortedBy { it.name }) {
                val id = file.name.removeSuffix(FILE_EXT)
                val json = try {
                    file.readText()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read ${file.absolutePath}", e)
                    continue
                }
                val task = TaskFile.fromJson(json, id, file.absolutePath)
                if (task == null) {
                    Log.w(TAG, "Failed to parse task file: ${file.name}, skipped")
                    continue
                }
                cachedTasks.add(task)
            }
            Log.i(TAG, "Loaded ${cachedTasks.size} task files from ${dir.absolutePath}")
        }
    }

    /**
     * 重新从磁盘加载任务列表（外部编辑/新增文件后调用）
     */
    fun reload() {
        loadFromDisk()
    }

    /**
     * 获取全部任务文件（不可变副本）
     */
    fun getAllTasks(): List<TaskFile> = synchronized(fileLock) {
        cachedTasks.toList()
    }

    /**
     * 根据 id 查找任务
     */
    fun getTask(id: String): TaskFile? = synchronized(fileLock) {
        cachedTasks.find { it.id == id }
    }
}
