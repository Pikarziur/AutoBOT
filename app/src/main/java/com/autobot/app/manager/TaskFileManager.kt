package com.autobot.app.manager

import android.content.Context
import android.util.Log
import com.autobot.app.model.TaskFile
import java.io.File

object TaskFileManager {

    private const val TAG = "TaskFileManager"

    private const val TASKS_DIR_NAME = "tasks"

    private const val ASSETS_TASKS_DIR = "tasks"

    private const val FILE_EXT = ".json"

    private val cachedTasks = mutableListOf<TaskFile>()

    private val fileLock = Any()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureTasksDir()
        loadBundledTasks()
        loadFromDisk()
    }

    private fun ctx(): Context =
        appContext ?: throw IllegalStateException("TaskFileManager.init() not called")

    private fun tasksDirFile(): File = File(ctx().filesDir, TASKS_DIR_NAME)

    private fun ensureTasksDir() {
        tasksDirFile().mkdirs()
    }

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

    fun reload() {
        loadFromDisk()
    }

    fun getAllTasks(): List<TaskFile> = synchronized(fileLock) {
        cachedTasks.toList()
    }

    fun getTask(id: String): TaskFile? = synchronized(fileLock) {
        cachedTasks.find { it.id == id }
    }
}
