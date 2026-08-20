package com.autobot.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 任务文件数据模型
 *
 * 一个 TaskFile = 一个独立的 JSON 任务文件（filesDir/tasks/<name>.json），
 * 描述一组在虚拟显示器（VD）上要执行的 MotionEvent 动作。
 *
 * 支持两种模式：
 *   1. 静态模式（actions）：顺序执行的固定动作列表（坐标写死，适合精确点击）
 *   2. 程序化模式（program）：循环 + 随机 + 分组的动态任务（适合刷量类任务）
 *
 * 整套体系与 MAA-Meow 一致：不再走 adb shell / `input tap`，而是由
 * [com.autobot.app.manager.TaskExecutor] 在 App 进程内通过 CompositionService
 * 把动作下发到 server 进程，server 用 IInputManager.injectInputEvent + setDisplayId
 * 直接注入 MotionEvent 到虚拟显示器。
 *
 * 静态任务 JSON 结构示例（test.json）：
 * ```
 * {
 *   "name": "test",
 *   "actions": [
 *     { "type": "tap",    "x": 500, "y": 500 },
 *     { "type": "wait",   "ms": 5000 },
 *     { "type": "swipe",  "startX": 360, "startY": 640,
 *                         "endX":   360, "endY":   840, "durationMs": 500 },
 *     { "type": "back" }
 *   ]
 * }
 * ```
 *
 * 程序化任务 JSON 结构示例（tb_miandan.json）：
 * ```
 * {
 *   "name": "tb_miandan",
 *   "program": {
 *     "groups": 15,
 *     "delayMinMs": 3000,
 *     "delayMaxMs": 5000,
 *     "coordRange": { "xMinRatio": 0.1, "yMinRatio": 0.25,
 *                     "xMaxRatio": 0.9, "yMaxRatio": 0.9 },
 *     "shuffleGroup": true,
 *     "delayBetweenActions": true,
 *     "actions": [
 *       { "type": "swipe_up_fast",     "durationMs": 300, "label": "快速上滑" },
 *       { "type": "swipe_up_slow",     "durationMs": 700, "label": "慢速上滑" },
 *       { "type": "arc_swipe_left_up", "durationMs": 400, "label": "圆弧左上滑" },
 *       { "type": "arc_swipe_right_up","durationMs": 400, "label": "圆弧右上滑" }
 *     ]
 *   }
 * }
 * ```
 *
 * program.actions 支持的 type 见 [ProgramActionType]。
 */
data class TaskFile(
    /** 任务 ID：来自文件名（不带扩展名），稳定可读 */
    val id: String,
    /** 任务展示名（JSON 内 name 字段；缺失时回退到 id） */
    val name: String,
    /** 任务描述（JSON 内 description 字段；可空） */
    val description: String,
    /** 任务文件绝对路径（filesDir/tasks/<id>.json），用于编辑/删除 */
    val filePath: String,
    /** 静态模式：顺序执行的 action 列表（与 program 互斥，program 优先） */
    val actions: List<TaskAction> = emptyList(),
    /** 程序化模式：循环 + 随机 + 分组任务（存在则优先于 actions） */
    val program: ProgramTask? = null
) {
    companion object {
        /**
         * 从 JSON 字符串解析任务文件
         *
         * 优先解析 program（程序化任务）；若无 program，再解析 actions（静态任务）。
         * 两者都没有则返回 null。
         *
         * @param json       文件完整内容
         * @param id         任务 ID（一般取文件名去掉 .json）
         * @param filePath   文件绝对路径（仅用于回写）
         * @return 解析失败返回 null（调用方按"跳过该任务"处理）
         */
        fun fromJson(json: String, id: String, filePath: String): TaskFile? {
            return try {
                val root = JSONObject(json)
                val name = root.optString("name", id).ifBlank { id }
                val description = root.optString("description", "")

                // 优先程序化任务
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

                // 回退静态 actions
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

/**
 * 程序化任务配置（循环 + 随机 + 分组）
 *
 * 执行语义（由 [com.autobot.app.manager.TaskExecutor.executeProgram] 实现）：
 *   1. 外层循环 [groups] 次，每次为"一组"
 *   2. 每组从 [actions] 复制一份，若 [shuffleGroup] 则组内随机排序
 *   3. 组内逐个执行 [ProgramAction]，坐标按 [coordRange] 随机生成
 *   4. 每个动作之间若 [delayBetweenActions]，随机等待 [delayMinMs]~[delayMaxMs]
 */
data class ProgramTask(
    /** 循环组数（一组 = actions 列表执行一遍） */
    val groups: Int = 1,
    /** 动作间随机延迟下限（毫秒） */
    val delayMinMs: Long = 3000L,
    /** 动作间随机延迟上限（毫秒） */
    val delayMaxMs: Long = 5000L,
    /** 坐标随机范围（比例值 0~1，运行时按 VD 宽高换算成像素） */
    val coordRange: CoordRange = CoordRange(),
    /** 组内动作是否随机排序 */
    val shuffleGroup: Boolean = true,
    /** 动作之间是否插入随机延迟 */
    val delayBetweenActions: Boolean = true,
    /** 组内动作模板（4 种滑动等） */
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

/** 坐标随机范围（比例值，0~1） */
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

/**
 * 程序化任务动作类型
 *
 * 坐标在运行时按 [CoordRange] 随机生成，这里只定义"动作形态 + 时长"。
 * - SWIPE_UP_FAST/SLOW：直线向上滑动（页面下移），起点在下、终点在上
 * - ARC_SWIPE_LEFT_UP：从右下到左上的圆弧（左凸）
 * - ARC_SWIPE_RIGHT_UP：从左下到右上的圆弧（右凸）
 */
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

/** 程序化任务的单个动作模板 */
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

/**
 * 任务动作类型枚举
 *
 * - TAP   ：单击坐标 (x, y)，等同 MotionEvent ACTION_DOWN → ACTION_UP
 * - SWIPE ：滑动 (startX, startY) → (endX, endY)，按 durationMs 插值多帧 MOVE
 * - WAIT  ：等待 ms 毫秒（不注入任何事件，用于动作之间的间隔）
 * - BACK  ：注入 KEYCODE_BACK（让 VD 中的 App 返回上一层）
 */
enum class TaskActionType {
    TAP,
    SWIPE,
    WAIT,
    BACK;

    companion object {
        /** 字符串 → 枚举，未知类型返回 null（调用方跳过该 action） */
        fun fromString(s: String?): TaskActionType? {
            if (s.isNullOrBlank()) return null
            // 不依赖 Kotlin 1.9 实验性 valueOfOrNull，用 values().firstOrNull 兼容所有版本
            val key = s.uppercase()
            return TaskActionType.values().firstOrNull { it.name == key }
        }
    }
}

/**
 * 单个任务动作
 *
 * 字段含义按 [type] 不同而不同：
 * - TAP   ：x, y 必填；其余字段忽略
 * - SWIPE ：startX, startY, endX, endY, durationMs 必填
 * - WAIT  ：ms 必填
 * - BACK  ：无字段
 */
data class TaskAction(
    val type: TaskActionType,
    /** TAP 的 X / SWIPE 的 startX */
    val x: Int = 0,
    /** TAP 的 Y / SWIPE 的 startY */
    val y: Int = 0,
    /** SWIPE 的 endX */
    val endX: Int = 0,
    /** SWIPE 的 endY */
    val endY: Int = 0,
    /** SWIPE 持续时长（毫秒） */
    val durationMs: Long = 0L,
    /** WAIT 等待时长（毫秒） */
    val ms: Long = 0L
) {
    companion object {
        /**
         * 从 JSONObject 解析单个 action
         *
         * JSON 字段命名约定（与 assets/tasks/test.json 一致）：
         *   - type      ：动作类型字符串（tap/swipe/wait/back）
         *   - x, y      ：TAP 的坐标（也是 SWIPE 的 startX/startY 别名，向后兼容）
         *   - startX, startY, endX, endY, durationMs ：SWIPE 专用
         *   - ms        ：WAIT 等待时长
         */
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
