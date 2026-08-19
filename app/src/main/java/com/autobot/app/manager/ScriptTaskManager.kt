package com.autobot.app.manager

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * SH 脚本任务管理器
 *
 * 职责：
 * 1. 维护"SH-ADB 模式"下用户导入的脚本任务列表（持久化到 SharedPreferences）
 * 2. 通过 SAF（Storage Access Framework）读取手机本地 .sh 文件，复制到 app 内部
 *    filesDir/scripts/ 目录，使 ShellExecutor.executeScript() 可直接以本地路径调用
 * 3. 提供查询/添加/删除接口供 UI 调用
 *
 * 数据模型：ScriptTask
 *   - id         唯一标识（UUID 前 8 位）
 *   - name       任务展示名（默认取文件名，可由用户编辑）
 *   - scriptPath 脚本在 app 内部的绝对路径（filesDir/scripts/xxx.sh）
 *   - originalName 用户导入时的原始文件名（含扩展名）
 *   - importedAt  导入时间戳
 */
object ScriptTaskManager {

    private const val TAG = "ScriptTaskManager"

    private const val PREFS_NAME = "autobot_script_tasks"
    private const val KEY_TASKS = "tasks_json"

    /** 脚本存储子目录（在 filesDir 下）
     *  命名为 Mode1_SH：与"模式一：adb shell"对应，便于区分后续模式（如 Mode2_截图识别） */
    private const val SCRIPTS_DIR = "Mode1_SH"

    /** 内存缓存：避免每次操作都反序列化 */
    private val cachedTasks = mutableListOf<ScriptTask>()

    /** App Context（由 init() 注入） */
    @Volatile private var appContext: Context? = null

    /**
     * SH 脚本任务数据模型
     */
    data class ScriptTask(
        val id: String = UUID.randomUUID().toString().substring(0, 8),
        val name: String,
        val scriptPath: String,
        val originalName: String,
        val importedAt: Long = System.currentTimeMillis()
    )

    /**
     * 初始化：必须在 Application.onCreate 中调用一次
     * 注入 ApplicationContext 并加载已持久化的任务列表
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        loadFromPrefs()
        ensureScriptsDir()
    }

    private fun ctx(): Context =
        appContext ?: throw IllegalStateException("ScriptTaskManager.init() not called")

    private fun ensureScriptsDir() {
        val dir = File(ctx().filesDir, SCRIPTS_DIR)
        if (!dir.exists()) dir.mkdirs()
    }

    private fun scriptsDirFile(): File = File(ctx().filesDir, SCRIPTS_DIR)

    private fun prefs() = ctx().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 从 SharedPreferences 反序列化任务列表到内存缓存
     */
    private fun loadFromPrefs() {
        cachedTasks.clear()
        val json = prefs().getString(KEY_TASKS, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                cachedTasks.add(
                    ScriptTask(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        scriptPath = o.getString("scriptPath"),
                        originalName = o.optString("originalName", ""),
                        importedAt = o.optLong("importedAt", 0L)
                    )
                )
            }
            Log.i(TAG, "Loaded ${cachedTasks.size} script tasks from prefs")
        } catch (e: Exception) {
            Log.e(TAG, "loadFromPrefs failed", e)
        }
    }

    /**
     * 序列化任务列表到 SharedPreferences
     */
    private fun saveToPrefs() {
        val arr = JSONArray()
        cachedTasks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("scriptPath", t.scriptPath)
                put("originalName", t.originalName)
                put("importedAt", t.importedAt)
            })
        }
        prefs().edit().putString(KEY_TASKS, arr.toString()).apply()
    }

    /**
     * 获取全部脚本任务
     */
    fun getAllTasks(): List<ScriptTask> = cachedTasks.toList()

    /**
     * 根据 id 查找
     */
    fun getTask(id: String): ScriptTask? = cachedTasks.find { it.id == id }

    /**
     * 从 SAF Uri 导入 SH 脚本文件
     * 1. 读取 Uri 流内容
     * 2. 写入 filesDir/scripts/<uuid>.sh（避免重名覆盖）
     * 3. 添加到任务列表并持久化
     *
     * @param uri        SAF 返回的文件 Uri
     * @param taskName   任务展示名（null 时取 Uri 末尾段）
     * @return 成功返回 ScriptTask；失败返回 null
     */
    fun importScript(uri: Uri, taskName: String? = null): ScriptTask? {
        val context = ctx()
        return try {
            // 解析显示名（取 Uri 末尾段，去掉 query/fragment）
            val originalName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.sh"
            val displayName = taskName?.takeIf { it.isNotBlank() }
                ?: originalName.removeSuffix(".sh")

            ensureScriptsDir()
            // 用 UUID 防止同名覆盖
            val targetFileName = "${UUID.randomUUID().toString().substring(0, 8)}_$originalName"
            val targetFile = File(scriptsDirFile(), targetFileName)

            // 复制 Uri 内容到内部存储
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    Log.e(TAG, "openInputStream returned null for uri=$uri")
                    return null
                }
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 设置可执行权限（chmod 700），ShellExecutor 走 sh 调用时其实不强依赖，但保留以防直接执行
            try {
                targetFile.setExecutable(true, true)
            } catch (_: Exception) { /* 忽略权限设置失败 */ }

            val task = ScriptTask(
                name = displayName,
                scriptPath = targetFile.absolutePath,
                originalName = originalName
            )
            cachedTasks.add(task)
            saveToPrefs()
            Log.i(TAG, "Imported script: ${task.name} -> ${task.scriptPath}")
            task
        } catch (e: Exception) {
            Log.e(TAG, "importScript failed", e)
            null
        }
    }

    /**
     * 删除脚本任务（同时删除内部存储的 .sh 文件）
     */
    fun deleteTask(id: String): Boolean {
        val idx = cachedTasks.indexOfFirst { it.id == id }
        if (idx < 0) return false

        val task = cachedTasks[idx]
        cachedTasks.removeAt(idx)
        saveToPrefs()

        // 清理脚本文件
        try {
            val f = File(task.scriptPath)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            Log.w(TAG, "delete script file failed: ${task.scriptPath}", e)
        }
        Log.i(TAG, "Deleted script task: ${task.name}")
        return true
    }

    /**
     * 清空所有任务（用于调试/重置）
     */
    fun clearAll() {
        cachedTasks.forEach { t ->
            try { File(t.scriptPath).delete() } catch (_: Exception) {}
        }
        cachedTasks.clear()
        saveToPrefs()
    }
}
