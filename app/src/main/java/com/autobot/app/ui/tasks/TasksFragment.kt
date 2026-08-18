package com.autobot.app.ui.tasks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.autobot.app.manager.TaskManager
import com.autobot.app.model.TaskInfo
import com.autobot.app.service.TaskService
import com.autobot.app.ui.theme.AutoBotTheme

/**
 * 后台任务 Fragment
 *
 * 承载 TasksScreen（MAA-Meow 风格）：
 *  - 顶部虚拟显示器预览（小窗模式 + 全屏模式切换）
 *  - App 下拉列表 + 三角形启动按钮
 *
 * 仍保留 TaskManager 监听器以启动/停止前台服务
 */
class TasksFragment : Fragment() {

    private val vm: MonitorViewModel by viewModels()

    private val taskListener = object : TaskManager.TaskListener {
        override fun onTaskStarted(task: TaskInfo) {
            ensureForegroundService()
        }

        override fun onTaskOutput(task: TaskInfo, line: String) {}

        override fun onTaskCompleted(task: TaskInfo) {
            ensureForegroundService()
        }

        override fun onTaskStopped(task: TaskInfo) {
            ensureForegroundService()
        }

        override fun onTaskError(task: TaskInfo, error: String) {
            ensureForegroundService()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                // MAA-Meow 风格主题（白色为主 + 蓝色强调 + 红色报错）
                AutoBotTheme {
                    TasksScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        TaskManager.addListener(taskListener)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshShizukuStatus()
        ensureForegroundService()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        TaskManager.removeListener(taskListener)
    }

    private fun ensureForegroundService() {
        if (TaskManager.hasRunningTasks()) {
            context?.let { TaskService.start(it) }
        }
    }
}
