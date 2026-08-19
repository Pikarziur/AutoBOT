package com.autobot.app.manager

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * SH 脚本任务管理器（Mode1：adb shell 模式专用）
 *
 * 职责：
 * 1. 维护"SH-ADB 模式"（Mode1）下用户导入的脚本任务列表
 * 2. 通过 SAF / Shizuku / assets 读取 .sh 文件，复制到 app 内部
 *    filesDir/Mode1/scripts/ 目录，使 ShellExecutor.executeScript() 可直接以本地路径调用
 * 3. 任务元数据持久化到 filesDir/Mode1/tasks.json（文件存储，不再用 SharedPreferences）
 * 4. 提供查询/添加/删除接口供 UI 调用
 *
 * ★Mode1 模块文件存放点（app 内部存储，无需运行时权限）★：
 *   filesDir/Mode1/                    ← Mode1 模块根目录
 *     ├ scripts/                       ← .sh 脚本存放点
 *     │   ├ <uuid>_<原文件名>.sh
 *     │   └ ...
 *     └ tasks.json                     ← 任务元数据（id/name/scriptPath/originalName/importedAt）
 *
 * 数据模型：ScriptTask
 *   - id         唯一标识（UUID 前 8 位）
 *   - name       任务展示名（默认取文件名，可由用户编辑）
 *   - scriptPath 脚本在 app 内部的绝对路径（filesDir/Mode1/scripts/xxx.sh）
 *   - originalName 用户导入时的原始文件名（含扩展名）
 *   - importedAt  导入时间戳
 *
 * 历史兼容：从旧版 filesDir/Mode1_SH/ + SharedPreferences 自动迁移到新结构（幂等）
 */
object ScriptTaskManager {

    private const val TAG = "ScriptTaskManager"

    /** Mode1 模块根目录名（在 filesDir 下） */
    private const val MODULE_DIR = "Mode1"

    /** .sh 脚本存放子目录（在 MODULE_DIR 下） */
    private const val SCRIPTS_SUBDIR = "scripts"

    /** 任务元数据文件名（在 MODULE_DIR 下） */
    private const val TASKS_FILENAME = "tasks.json"

    /** 旧版 SharedPreferences 名（仅用于迁移） */
    private const val LEGACY_PREFS_NAME = "autobot_script_tasks"
    private const val LEGACY_KEY_TASKS = "tasks_json"

    /** 旧版 .sh 脚本目录名（仅用于迁移） */
    private const val LEGACY_SCRIPTS_DIR = "Mode1_SH"

    /** 内存缓存：避免每次操作都反序列化 */
    private val cachedTasks = mutableListOf<ScriptTask>()

    /** 持久化文件读写锁（tasks.json 多线程访问保护） */
    private val fileLock = Any()

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
     *
     * 流程：
     *   1. 注入 ApplicationContext
     *   2. 确保 Mode1 模块目录与 scripts/ 子目录存在
     *   3. 从旧版存储迁移（Mode1_SH/ + SharedPreferences → Mode1/scripts/ + tasks.json）
     *   4. 加载 tasks.json 到内存缓存
     *   5. 从 assets/scripts/ 装载预置 .sh 脚本（新增的会被自动导入）
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        ensureModuleDirs()
        migrateFromLegacyStorage()
        loadFromFile()
        loadBundledScripts()
    }

    private fun ctx(): Context =
        appContext ?: throw IllegalStateException("ScriptTaskManager.init() not called")

    /** Mode1 模块根目录：filesDir/Mode1/ */
    private fun moduleDirFile(): File = File(ctx().filesDir, MODULE_DIR)

    /** .sh 脚本存放目录：filesDir/Mode1/scripts/ */
    private fun scriptsDirFile(): File = File(moduleDirFile(), SCRIPTS_SUBDIR)

    /** 任务元数据文件：filesDir/Mode1/tasks.json */
    private fun tasksFile(): File = File(moduleDirFile(), TASKS_FILENAME)

    /** 确保 Mode1 模块目录与 scripts/ 子目录存在 */
    private fun ensureModuleDirs() {
        moduleDirFile().mkdirs()
        scriptsDirFile().mkdirs()
    }

    /**
     * 历史数据迁移（幂等，可安全多次执行）
     *
     * 迁移项：
     *   1. 旧 .sh 目录 filesDir/Mode1_SH/ → filesDir/Mode1/scripts/
     *   2. 旧 SharedPreferences autobot_script_tasks.tasks_json → filesDir/Mode1/tasks.json
     *
     * 注：旧 scriptPath 路径修正（指向 Mode1_SH 的，更新为 Mode1/scripts）
     *     在 [loadFromFile] 中读取后即时修正并回写
     */
    private fun migrateFromLegacyStorage() {
        val ctx = ctx()
        val legacyScriptsDir = File(ctx.filesDir, LEGACY_SCRIPTS_DIR)
        val targetScriptsDir = scriptsDirFile()

        // ---------- 1. 迁移 .sh 文件 ----------
        if (legacyScriptsDir.exists()) {
            legacyScriptsDir.listFiles()?.forEach { srcFile ->
                if (!srcFile.isFile) return@forEach
                val dstFile = File(targetScriptsDir, srcFile.name)
                if (dstFile.exists()) {
                    // 目标已存在（可能上次已迁过），删除源文件避免重复
                    srcFile.delete()
                    Log.i(TAG, "Migrate skip (target exists): ${srcFile.name}")
                    return@forEach
                }
                // 优先用 renameTo（同分区原子操作）；失败则 fallback 到 copy+delete
                if (srcFile.renameTo(dstFile)) {
                    Log.i(TAG, "Migrated script: ${srcFile.name} -> ${dstFile.absolutePath}")
                } else {
                    try {
                        srcFile.copyTo(dstFile, overwrite = false)
                        srcFile.delete()
                        Log.i(TAG, "Migrated script (copy): ${srcFile.name} -> ${dstFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Migrate failed for ${srcFile.name}", e)
                    }
                }
            }
            // 删除空旧目录（非空保留，避免误删）
            if (legacyScriptsDir.listFiles()?.isEmpty() == true) {
                legacyScriptsDir.delete()
                Log.i(TAG, "Removed legacy empty dir: ${legacyScriptsDir.absolutePath}")
            }
        }

        // ---------- 2. 迁移 SharedPreferences → tasks.json ----------
        val legacyPrefs = ctx.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyJson = legacyPrefs.getString(LEGACY_KEY_TASKS, null)
        if (legacyJson != null) {
            val tasksFile = tasksFile()
            if (!tasksFile.exists()) {
                try {
                    tasksFile.writeText(legacyJson)
                    Log.i(TAG, "Migrated tasks metadata: SharedPreferences -> ${tasksFile.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write migrated tasks.json", e)
                }
            } else {
                Log.i(TAG, "tasks.json already exists, skip SharedPreferences migration")
            }
            // 清理旧 prefs（已迁到文件，不再需要）
            legacyPrefs.edit().clear().apply()
        }
    }

    /**
     * 从 tasks.json 反序列化任务列表到内存缓存
     * 同时修正 scriptPath（指向旧 Mode1_SH 路径的，更新为 Mode1/scripts）
     */
    private fun loadFromFile() {
        synchronized(fileLock) {
            cachedTasks.clear()
            val file = tasksFile()
            if (!file.exists()) {
                Log.i(TAG, "tasks.json not found, starting with empty list")
                return
            }
            try {
                val json = file.readText()
                if (json.isBlank()) return
                val arr = JSONArray(json)
                var pathFixed = false
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    var scriptPath = o.getString("scriptPath")
                    // 修正历史路径：/Mode1_SH/ → /Mode1/scripts/
                    if (scriptPath.contains("/$LEGACY_SCRIPTS_DIR/")) {
                        scriptPath = scriptPath.replace(
                            "/$LEGACY_SCRIPTS_DIR/",
                            "/$MODULE_DIR/$SCRIPTS_SUBDIR/"
                        )
                        pathFixed = true
                    }
                    cachedTasks.add(
                        ScriptTask(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            scriptPath = scriptPath,
                            originalName = o.optString("originalName", ""),
                            importedAt = o.optLong("importedAt", 0L)
                        )
                    )
                }
                Log.i(TAG, "Loaded ${cachedTasks.size} script tasks from ${file.absolutePath}")
                // 路径有修正时立即回写，保证 tasks.json 反映最新路径
                if (pathFixed) {
                    saveToFileInternal()
                    Log.i(TAG, "Rewrote tasks.json with fixed scriptPath")
                }
                // 显式 Unit：避免把上面的 if 当作 try 块的最后表达式
                // （Kotlin 中 try/catch 是表达式，if 作为表达式时必须配 else 分支）
                Unit
            } catch (e: Exception) {
                Log.e(TAG, "loadFromFile failed", e)
            }
        }
    }

    /**
     * 从 app 内部 assets/scripts/ 装载预置的 .sh 脚本
     *
     * ★脚本装载策略★（启动时检查并恢复，幂等）：
     *   - 扫描 assets/scripts/ 下所有 .sh 文件
     *   - 对每个文件，若 originalName 已在 cachedTasks 中，跳过（不重复导入）
     *   - 不存在则读取内容 → 落盘到 filesDir/Mode1/scripts/ → 注册到 tasks.json
     *   - 用户在 UI 内无法删除脚本（无 +/- 按钮），但若手动清空 tasks.json，
     *     下次启动会自动恢复预置脚本
     *
     * 必须在 [loadFromFile] 之后调用（依赖 cachedTasks 已填充以判断是否已存在）
     */
    fun loadBundledScripts() {
        val context = ctx()
        val assetManager = context.assets

        // 扫描 assets/scripts/ 目录（不存在时 list 返回 null）
        val assetFiles: Array<String> = try {
            assetManager.list("scripts") ?: emptyArray()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list assets/scripts/", e)
            return
        }
        if (assetFiles.isEmpty()) {
            Log.i(TAG, "No bundled .sh scripts in assets/scripts/")
            return
        }

        var imported = 0
        var skipped = 0
        for (filename in assetFiles) {
            // 仅处理 .sh 文件（忽略 .gitkeep 等占位文件）
            if (!filename.endsWith(".sh", ignoreCase = true)) continue

            // 幂等去重：按 originalName 检查是否已导入
            val alreadyImported = cachedTasks.any { it.originalName == filename }
            if (alreadyImported) {
                skipped++
                continue
            }

            // 读 assets/scripts/<filename> 全部内容
            val content = try {
                assetManager.open("scripts/$filename").use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read assets/scripts/$filename", e)
                continue
            }

            // 落盘 + 注册到 tasks.json（复用已有逻辑）
            val displayName = filename.removeSuffix(".sh")
            importScriptFromContent(displayName, content, filename)?.let {
                imported++
                Log.i(TAG, "Loaded bundled script: ${it.name} (${filename})")
            }
        }
        Log.i(TAG, "loadBundledScripts done: imported=$imported, skipped=$skipped, total=${cachedTasks.size}")
    }

    /**
     * 序列化任务列表到 tasks.json
     */
    private fun saveToFile() {
        synchronized(fileLock) {
            saveToFileInternal()
        }
    }

    /** 内部写入实现（调用方需已持有 fileLock） */
    private fun saveToFileInternal() {
        try {
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
            tasksFile().writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "saveToFile failed", e)
        }
    }

    /**
     * 获取全部脚本任务（不可变副本，外部修改不影响内部缓存）
     */
    fun getAllTasks(): List<ScriptTask> = cachedTasks.toList()

    /**
     * 根据 id 查找
     */
    fun getTask(id: String): ScriptTask? = cachedTasks.find { it.id == id }

    /**
     * 从 SAF Uri 导入 SH 脚本文件
     * 1. 读取 Uri 流内容
     * 2. 写入 filesDir/Mode1/scripts/<uuid>_<原文件名>.sh（避免重名覆盖）
     * 3. 添加到任务列表并持久化到 tasks.json
     *
     * @param uri        SAF 返回的文件 Uri
     * @param taskName   任务展示名（null 时取 Uri 末尾段去掉 .sh）
     * @return 成功返回 ScriptTask；失败返回 null
     */
    fun importScript(uri: Uri, taskName: String? = null): ScriptTask? {
        val context = ctx()
        return try {
            val originalName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.sh"
            val displayName = taskName?.takeIf { it.isNotBlank() }
                ?: originalName.removeSuffix(".sh")

            ensureModuleDirs()
            val targetFileName = "${UUID.randomUUID().toString().substring(0, 8)}_$originalName"
            val targetFile = File(scriptsDirFile(), targetFileName)

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
            saveToFile()
            Log.i(TAG, "Imported script: ${task.name} -> ${task.scriptPath}")
            task
        } catch (e: Exception) {
            Log.e(TAG, "importScript failed", e)
            null
        }
    }

    /**
     * 从原始内容导入 SH 脚本（用于 Shizuku `cat` / assets 读取的字符串内容）
     *
     * 与 [importScript] 的差异：内容来自 String 而非 SAF Uri，**绕开"安全浏览"过滤**。
     * Shizuku 走 shell uid 直接 `cat` 文件，不经过 SAF picker，能读到被系统
     * 安全浏览隐藏/拦截的 .sh 文件。
     *
     * @param name         任务展示名（一般取文件名去掉 .sh 后缀）
     * @param content      脚本文件完整内容
     * @param originalName 原始文件名（含 .sh 扩展名，仅用于内部存储文件命名）
     * @return 成功返回 ScriptTask；失败返回 null
     */
    fun importScriptFromContent(name: String, content: String, originalName: String): ScriptTask? {
        return try {
            // 清洗文件名：替换 / 和空格，避免内部存储路径异常
            val safeName = originalName.ifBlank { "imported.sh" }
                .replace("/", "_")
                .replace(" ", "_")
            ensureModuleDirs()
            val targetFileName = "${UUID.randomUUID().toString().substring(0, 8)}_$safeName"
            val targetFile = File(scriptsDirFile(), targetFileName)
            targetFile.writeText(content)

            try {
                targetFile.setExecutable(true, true)
            } catch (_: Exception) { /* 忽略权限设置失败 */ }

            val displayName = name.takeIf { it.isNotBlank() }
                ?: safeName.removeSuffix(".sh")
            val task = ScriptTask(
                name = displayName,
                scriptPath = targetFile.absolutePath,
                originalName = safeName
            )
            cachedTasks.add(task)
            saveToFile()
            Log.i(TAG, "Imported script from content: ${task.name} -> ${task.scriptPath}")
            task
        } catch (e: Exception) {
            Log.e(TAG, "importScriptFromContent failed", e)
            null
        }
    }

    /**
     * 删除脚本任务（同时删除内部存储的 .sh 文件 + 更新 tasks.json）
     */
    fun deleteTask(id: String): Boolean {
        val idx = cachedTasks.indexOfFirst { it.id == id }
        if (idx < 0) return false

        val task = cachedTasks[idx]
        cachedTasks.removeAt(idx)
        saveToFile()

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
     * 同时删除 .sh 文件、tasks.json、scripts/ 目录内容
     */
    fun clearAll() {
        synchronized(fileLock) {
            cachedTasks.forEach { t ->
                try { File(t.scriptPath).delete() } catch (_: Exception) {}
            }
            cachedTasks.clear()
            try { tasksFile().delete() } catch (_: Exception) {}
        }
        Log.i(TAG, "Cleared all script tasks")
    }
}
