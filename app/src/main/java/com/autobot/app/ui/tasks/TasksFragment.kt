package com.autobot.app.ui.tasks

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.autobot.app.manager.TaskManager
import com.autobot.app.model.TaskInfo
import com.autobot.app.service.TaskService

/**
 * 后台任务 Fragment
 *
 * 改造为 ComposeView 承载 TasksScreen：
 *  - 上半部分虚拟显示器预览（小窗模式 weight=3）
 *  - App 启动功能
 *  - 模式选择区（SH-ADB / 截图识别）
 *  - 任务列表
 *
 * 仍保留 TaskManager 监听器以启动/停止前台服务
 *
 * SH 文件导入流程：
 *   TasksScreen 的「SH 文件」按钮 → onPickShFile → Fragment 启动 SAF OpenDocument →
 *   ActivityResult 回调 → vm.importScript(uri) → ScriptTaskManager 持久化到 app 内部
 */
class TasksFragment : Fragment() {

    companion object {
        private const val TAG = "TasksFragment"
    }

    // 与 TasksScreen 共享同一个 ViewModel（使用 Fragment 作为 owner）
    private val vm: MonitorViewModel by viewModels()

    // 任务监听器：用于触发前台服务启停
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

    /**
     * SAF OpenDocument Launcher：用于选择本地 .sh 脚本文件
     * 必须在 Fragment 初始化时注册（不能在点击回调中注册，否则抛 IllegalStateException）
     *
     * 选中文件后通过 vm.importScript(uri) 复制到 app 内部存储
     */
    private val pickShFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            Log.w(TAG, "SH file pick cancelled by user")
            return@registerForActivityResult
        }
        // 授予持久化读权限，避免后续访问时 SecurityException
        try {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            Log.w(TAG, "takePersistableUriPermission failed", e)
        }
        // 调用 ViewModel 导入并持久化到 app 内部
        vm.importScript(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            // Fragment 中使用 Compose 的标准策略：随 Fragment 生命周期销毁
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                // 传入 SAF 启动回调：Compose UI 点击「SH 文件」按钮时触发
                TasksScreen(
                    onPickShFile = { launchShFilePicker() }
                )
            }
        }
    }

    /**
     * 启动 SAF 文件选择器（仅显示 .sh 文件 + 任意类型作为兜底，避免某些设备 MIME 过滤过严）
     */
    private fun launchShFilePicker() {
        // 主选 .sh (application/octet-stream / text/plain 都可能命中)
        // 同时传 "*/*" 作为 fallback，让用户能选到任意文件
        val mimeTypes = arrayOf(
            "application/octet-stream",
            "text/plain",
            "application/x-sh",
            "text/x-shellscript",
            "*/*"
        )
        pickShFileLauncher.launch(mimeTypes)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        TaskManager.addListener(taskListener)
    }

    override fun onResume() {
        super.onResume()
        ensureForegroundService()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        TaskManager.removeListener(taskListener)
    }

    /**
     * 有运行中任务时确保前台服务运行
     */
    private fun ensureForegroundService() {
        if (TaskManager.hasRunningTasks()) {
            context?.let { TaskService.start(it) }
        }
    }
}
