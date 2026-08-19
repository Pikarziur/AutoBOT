package com.autobot.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autobot.app.manager.TaskManager
import com.autobot.app.model.TaskInfo
import com.autobot.app.service.TaskService
import com.autobot.app.ui.settings.SettingsScreen
import com.autobot.app.ui.tasks.MonitorViewModel
import com.autobot.app.ui.tasks.TasksScreen
import com.autobot.app.ui.theme.AutoBotTheme

/**
 * 主 Activity（纯 Compose 架构，对齐 MAA-Meow）
 *
 * 取消 Fragment + XML 布局，改用 setContent 直接承载 Scaffold：
 *   - Scaffold.bottomBar = NavigationBar（纯文字 Tab：后台任务 / 设置）
 *   - Scaffold.content 根据 Tab 显示 TasksScreen / SettingsScreen
 *   - 全屏模式下 bottomBar 不渲染（返回空），content 自动填满整个窗口，
 *     彻底解决原 Fragment 架构中 BottomNavigationView 作为兄弟节点
 *     无法被 FullscreenMonitor 覆盖的问题。
 *
 * 原 Fragment 的职责迁移：
 *   - TasksFragment 的 TaskManager 监听器 + onResume 刷新 → 移到 MainActivity
 *   - SettingsFragment 的 Shizuku 授权逻辑 → 移到 SettingsViewModel
 */
class MainActivity : AppCompatActivity() {

    private val NOTIFICATION_REQUEST_CODE = 2001

    private val monitorViewModel: MonitorViewModel by viewModels()

    // 任务监听器：任务状态变化时确保前台服务运行（原 TasksFragment 职责）
    private val taskListener = object : TaskManager.TaskListener {
        override fun onTaskStarted(task: TaskInfo) = ensureForegroundService()
        override fun onTaskOutput(task: TaskInfo, line: String) {}
        override fun onTaskCompleted(task: TaskInfo) = ensureForegroundService()
        override fun onTaskStopped(task: TaskInfo) = ensureForegroundService()
        override fun onTaskError(task: TaskInfo, error: String) = ensureForegroundService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 关键：窗口创建时就禁用系统栏 inset 自动 padding
        // 必须在 setContent 之前调用，否则窗口已测量过一次，
        // 全屏模式下 BoxWithConstraints 拿到的尺寸会小于真实屏幕高度，
        // 导致等比缩放后顶部黑边大于底部。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        checkNotificationPermission()
        setContent {
            AutoBotTheme {
                MainScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TaskManager.addListener(taskListener)
    }

    override fun onStop() {
        super.onStop()
        TaskManager.removeListener(taskListener)
    }

    override fun onResume() {
        super.onResume()
        // 刷新 Shizuku 状态（原 TasksFragment.onResume 职责）
        monitorViewModel.refreshShizukuStatus()
        ensureForegroundService()
    }

    /**
     * 确保前台服务运行：有运行中任务时启动 TaskService
     */
    private fun ensureForegroundService() {
        if (TaskManager.hasRunningTasks()) {
            TaskService.start(this)
        }
    }

    /**
     * 检查并请求通知权限（Android 13+）
     */
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_REQUEST_CODE
                )
            }
        }
    }
}

/**
 * 底部导航 Tab（纯文字，无图标，对齐 MAA-Meow）
 */
private enum class MainTab(val label: String) {
    TASKS("后台任务"),
    SETTINGS("设置")
}

/**
 * 主屏：Scaffold + 底部 NavigationBar + 内容区
 *
 * 全屏模式：bottomBar 不渲染，Scaffold 的 content 区域自动填满整个屏幕，
 * FullscreenMonitor 的 fillMaxSize 可覆盖整个窗口。
 */
@Composable
private fun MainScreen() {
    val vm: MonitorViewModel = viewModel()
    val isFullscreen by vm.isFullscreen.collectAsStateWithLifecycle()
    // 用 Int 索引保存 Tab 状态（rememberSaveable 对 enum 序列化兼容性不确定，Int 必定可保存）
    var currentTabIndex by rememberSaveable { mutableStateOf(0) }
    val currentTab = MainTab.entries.getOrElse(currentTabIndex) { MainTab.TASKS }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // onCreate 已 setDecorFitsSystemWindows(false)，窗口不会自动加 inset padding
        // Scaffold 也不消费 inset（始终 WindowInsets(0)）：
        //   - 全屏：content 真正占满整屏 → BoxWithConstraints 拿到真实尺寸 → 黑边严格等距居中
        //   - 非全屏：由各页面自己用 statusBarsPadding 处理状态栏（避免与 Scaffold 双重 padding）
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // 全屏时隐藏底部导航栏（不渲染，content 区域自动扩展到全屏）
            if (!isFullscreen) {
                BottomNavBar(
                    currentTab = currentTab,
                    onTabSelected = { currentTabIndex = it.ordinal }
                )
            }
        }
    ) { padding ->
        // padding 此时为 0（contentWindowInsets=WindowInsets(0)），无需应用
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (currentTab) {
                MainTab.TASKS -> TasksScreen()
                MainTab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

/**
 * 底部导航栏（纯文字 Tab，无图标，去掉选中色块）
 *
 * 复刻原 activity_main.xml 的 BottomNavigationView 样式：
 *   - itemIconSize=0dp → icon 传 0dp Box 占位
 *   - itemActiveIndicatorStyle 透明 → indicatorColor = Color.Transparent
 *   - 文字水平居中 → Text.fillMaxWidth + textAlign Center
 *   - 选中文字蓝色 / 未选中文字灰色 → selectedTextColor / unselectedTextColor
 */
@Composable
private fun BottomNavBar(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                // 无图标：传 0dp 的 Box 占位，让 label 居中显示
                icon = { Box(Modifier.size(0.dp)) },
                label = {
                    Text(
                        text = tab.label,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = Color.Transparent  // 去掉选中背景色块
                )
            )
        }
    }
}
