package com.autobot.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 任务文件数据模型
 *
 * 一个 TaskFile = 一个独立的 JSON 任务文件（filesDir/tasks/<name>.json），
 * 描述一组在虚拟显示器（VD）上要顺序执行的 MotionEvent 动作。
 *
 * 整套体系与 MAA-Meow 一致：不再走 adb shell / `input tap`，而是由
 * [com.autobot.app.manager.TaskExecutor] 在 App 进程内通过 CompositionService
 * 把动作下发到 server 进程，server 用 IInputManager.injectInputEvent + setDisplayId
 * 直接注入 MotionEvent 到虚拟显示器。
 *
 * 任务文件 JSON 结构示例（test.json）：
 * ```
 * {
 *   "name": "test",
 *   "description": "占位测试任务",
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
 * 支持的 action 类型见 [TaskActionType]。
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
    /** 顺序执行的 action 列表 */
    val actions: List<TaskAction>
) {
    companion object {
        /**
         * 从 JSON 字符串解析任务文件
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
                val arr: JSONArray = root.optJSONArray("actions") ?: return null
                val actions = mutableListOf<TaskAction>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val action = TaskAction.fromJson(o) ?: continue
                    actions.add(action)
                }
                if (actions.isEmpty()) return null
                TaskFile(
                    id = id,
                    name = name,
                    description = description,
                    filePath = filePath,
                    actions = actions
                )
            } catch (e: Exception) {
                null
            }
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
        fun fromString(s: String?): TaskActionType? =
            s?.let { valueOfOrNull(it.uppercase()) }
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
