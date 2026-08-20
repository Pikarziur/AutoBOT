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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autobot.app.manager.TaskManager
import com.autobot.app.service.TaskService
import com.autobot.app.ui.settings.SettingsScreen
import com.autobot.app.ui.tasks.MonitorViewModel
import com.autobot.app.ui.tasks.TasksScreen
import com.autobot.app.ui.theme.AutoBotTheme

class MainActivity : AppCompatActivity() {

    private val NOTIFICATION_REQUEST_CODE = 2001

    private val monitorViewModel: MonitorViewModel by viewModels()

    private val taskListener = object : TaskManager.TaskListener {
        override fun onTaskStarted(taskId: String, taskName: String) = ensureForegroundService()
        override fun onTaskOutput(taskId: String, line: String) {}
        override fun onTaskCompleted(taskId: String) = ensureForegroundService()
        override fun onTaskStopped(taskId: String, reason: String) = ensureForegroundService()
        override fun onTaskError(taskId: String, error: String) = ensureForegroundService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        monitorViewModel.refreshShizukuStatus()
        ensureForegroundService()
    }

    private fun ensureForegroundService() {
        if (TaskManager.hasRunningTasks()) {
            TaskService.start(this)
        }
    }

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

private enum class MainTab(val label: String, val icon: ImageVector) {
    TASKS("后台任务", Icons.Outlined.PlayCircle),
    SETTINGS("设置", Icons.Outlined.Settings)
}

@Composable
private fun MainScreen() {
    val vm: MonitorViewModel = viewModel()
    val isFullscreen by vm.isFullscreen.collectAsStateWithLifecycle()
    // 用 Int 索引保存 Tab 状态（rememberSaveable 对 enum 序列化兼容性不确定，Int 必定可保存）
    var currentTabIndex by rememberSaveable { mutableStateOf(1) }
    val currentTab = MainTab.entries.getOrElse(currentTabIndex) { MainTab.TASKS }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!isFullscreen) {
                BottomNavBar(
                    currentTab = currentTab,
                    onTabSelected = { currentTabIndex = it.ordinal }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            when (currentTab) {
                MainTab.TASKS -> TasksScreen()
                MainTab.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun BottomNavBar(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = Color.White,
        tonalElevation = 0.dp
    ) {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = {
                    Text(
                        text = tab.label,
                        textAlign = TextAlign.Center
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
