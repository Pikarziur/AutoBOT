package com.autobot.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TaskFile(
    val id: String,
    val name: String,
    val description: String,
    val filePath: String,
    val actions: List<TaskAction> = emptyList(),
    val program: ProgramTask? = null,
    val recognition: RecognitionTask? = null
) {
    companion object {
        fun fromJson(json: String, id: String, filePath: String): TaskFile? {
            return try {
                val root = JSONObject(json)
                val name = root.optString("name", id).ifBlank { id }
                val description = root.optString("description", "")

                val recognitionObj = root.optJSONObject("recognition")
                if (recognitionObj != null) {
                    val recognition = RecognitionTask.fromJson(recognitionObj)
                    if (recognition != null) {
                        return TaskFile(
                            id = id, name = name, description = description,
                            filePath = filePath, recognition = recognition
                        )
                    }
                }

                val programObj = root.optJSONObject("program")
                if (programObj != null) {
                    val program = ProgramTask.fromJson(programObj)
                    if (program != null) {
                        return TaskFile(
                            id = id, name = name, description = description,
                            filePath = filePath, program = program
                        )
                    }
                }

                val arr: JSONArray = root.optJSONArray("actions") ?: return null
                val actions = mutableListOf<TaskAction>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val action = TaskAction.fromJson(o) ?: continue
                    actions.add(action)
                }
                if (actions.isEmpty()) return null
                TaskFile(
                    id = id, name = name, description = description,
                    filePath = filePath, actions = actions
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class ProgramTask(
    val groups: Int = 1,
    val delayMinMs: Long = 3000L,
    val delayMaxMs: Long = 5000L,
    val coordRange: CoordRange = CoordRange(),
    val shuffleGroup: Boolean = true,
    val delayBetweenActions: Boolean = true,
    val actions: List<ProgramAction> = emptyList()
) {
    companion object {
        fun fromJson(o: JSONObject): ProgramTask? {
            val actionsArr = o.optJSONArray("actions") ?: return null
            val actions = mutableListOf<ProgramAction>()
            for (i in 0 until actionsArr.length()) {
                ProgramAction.fromJson(actionsArr.getJSONObject(i))?.let { actions.add(it) }
            }
            if (actions.isEmpty()) return null
            val rangeObj = o.optJSONObject("coordRange")
            val range = rangeObj?.let { CoordRange.fromJson(it) } ?: CoordRange()
            return ProgramTask(
                groups = o.optInt("groups", 1).coerceAtLeast(1),
                delayMinMs = o.optLong("delayMinMs", 3000L),
                delayMaxMs = o.optLong("delayMaxMs", 5000L),
                coordRange = range,
                shuffleGroup = o.optBoolean("shuffleGroup", true),
                delayBetweenActions = o.optBoolean("delayBetweenActions", true),
                actions = actions
            )
        }
    }
}

data class CoordRange(
    val xMinRatio: Double = 0.1,
    val yMinRatio: Double = 0.25,
    val xMaxRatio: Double = 0.9,
    val yMaxRatio: Double = 0.9
) {
    companion object {
        fun fromJson(o: JSONObject) = CoordRange(
            xMinRatio = o.optDouble("xMinRatio", 0.1),
            yMinRatio = o.optDouble("yMinRatio", 0.25),
            xMaxRatio = o.optDouble("xMaxRatio", 0.9),
            yMaxRatio = o.optDouble("yMaxRatio", 0.9)
        )
    }
}

enum class ProgramActionType {
    SWIPE_UP_FAST,
    SWIPE_UP_SLOW,
    ARC_SWIPE_LEFT_UP,
    ARC_SWIPE_RIGHT_UP;

    companion object {
        fun fromString(s: String?): ProgramActionType? {
            if (s.isNullOrBlank()) return null
            return ProgramActionType.values().firstOrNull { it.name.equals(s, ignoreCase = true) }
        }
    }
}

data class ProgramAction(
    val type: ProgramActionType,
    val durationMs: Long,
    val label: String = ""
) {
    companion object {
        fun fromJson(o: JSONObject): ProgramAction? {
            val type = ProgramActionType.fromString(o.optString("type")) ?: return null
            return ProgramAction(
                type = type,
                durationMs = o.optLong("durationMs", 300L),
                label = o.optString("label", "")
            )
        }
    }
}

enum class TaskActionType {
    TAP,
    SWIPE,
    WAIT,
    BACK;

    companion object {
        fun fromString(s: String?): TaskActionType? {
            if (s.isNullOrBlank()) return null
            // 不依赖 Kotlin 1.9 实验性 valueOfOrNull，用 values().firstOrNull 兼容所有版本
            val key = s.uppercase()
            return TaskActionType.values().firstOrNull { it.name == key }
        }
    }
}

data class TaskAction(
    val type: TaskActionType,
    val x: Int = 0,
    val y: Int = 0,
    val endX: Int = 0,
    val endY: Int = 0,
    val durationMs: Long = 0L,
    val ms: Long = 0L
) {
    companion object {
        fun fromJson(o: JSONObject): TaskAction? {
            val type = TaskActionType.fromString(o.optString("type")) ?: return null
            return when (type) {
                TaskActionType.TAP -> TaskAction(
                    type = type,
                    x = o.optInt("x", 0),
                    y = o.optInt("y", 0)
                )
                TaskActionType.SWIPE -> TaskAction(
                    type = type,
                    // startX/startY 缺失时退回 x/y（兼容简写）
                    x = o.optInt("startX", o.optInt("x", 0)),
                    y = o.optInt("startY", o.optInt("y", 0)),
                    endX = o.optInt("endX", 0),
                    endY = o.optInt("endY", 0),
                    durationMs = o.optLong("durationMs", 300L)
                )
                TaskActionType.WAIT -> TaskAction(
                    type = type,
                    ms = o.optLong("ms", 0L)
                )
                TaskActionType.BACK -> TaskAction(type = type)
            }
        }
    }
}

enum class RecognitionTaskMode {
    TEMPLATE,
    OCR,
    BOTH;

    companion object {
        fun fromString(s: String?): RecognitionTaskMode? {
            if (s.isNullOrBlank()) return null
            return RecognitionTaskMode.values().firstOrNull { it.name.equals(s, ignoreCase = true) }
        }
    }
}

data class RecognitionTask(
    val mode: RecognitionTaskMode,
    val targetText: String = "",
    val templatePath: String = "",
    val threshold: Double = 0.8,
    val timeoutMs: Long = 30_000L,
    val intervalMs: Long = 500L,
    val delayAfterSuccessMs: Long = 2000L
) {
    companion object {
        fun fromJson(o: JSONObject): RecognitionTask? {
            val mode = RecognitionTaskMode.fromString(o.optString("mode")) ?: return null
            return RecognitionTask(
                mode = mode,
                targetText = o.optString("targetText", ""),
                templatePath = o.optString("templatePath", ""),
                threshold = o.optDouble("threshold", 0.8),
                timeoutMs = o.optLong("timeoutMs", 30_000L),
                intervalMs = o.optLong("intervalMs", 500L),
                delayAfterSuccessMs = o.optLong("delayAfterSuccessMs", 2000L)
            )
        }
    }
}
