package com.autobot.app.ui.settings

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autobot.app.manager.ShizukuManager

/**
 * 设置页面 - Compose 实现（MAA-Meow 风格）
 *
 * 替代原 SettingsFragment，由 MainActivity 的 Scaffold 直接承载。
 *
 * 结构：
 *   Surface(background) → Column(verticalScroll)
 *     ├ 标题 "设置"（22sp SemiBold）
 *     ├ ShizukuCard：状态文字 + 授权按钮 + 打开 Shizuku 按钮
 *     └ DeviceInfoCard：关于（设备型号 / 系统版本 / 屏幕分辨率 / 应用版本 横线表格）
 *
 * Shizuku 状态与授权逻辑下沉到 SettingsViewModel，UI 仅渲染 + 转发点击。
 * 颜色全部从 MaterialTheme.colorScheme 取，由 AutoBotTheme 统一注入。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = viewModel()
    val context = LocalContext.current
    val diagnosis by vm.diagnosis.collectAsStateWithLifecycle()

    // 收集一次性 Toast 事件并显示
    LaunchedEffect(Unit) {
        vm.toast.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 每次进入组合（切回设置页 / 首次进入）刷新一次状态
    LaunchedEffect(Unit) {
        vm.refreshShizukuStatus()
    }

    // Activity 级 onResume 刷新（用户在 Shizuku App 中授权后切回）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshShizukuStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题栏 "设置"
            Text(
                text = "设置",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))

            ShizukuCard(
                diagnosis = diagnosis,
                onAuthorizeClick = { vm.authorize() },
                onOpenShizukuClick = { vm.openShizukuApp() }
            )

            DeviceInfoCard()
        }
    }
}

/**
 * Shizuku 状态卡片（InfoCard 样式：0 阴影 12dp 圆角 16dp 内边距）
 */
@Composable
private fun ShizukuCard(
    diagnosis: ShizukuManager.ShizukuDiagnosis,
    onAuthorizeClick: () -> Unit,
    onOpenShizukuClick: () -> Unit
) {
    val (statusText, isError) = when (diagnosis) {
        ShizukuManager.ShizukuDiagnosis.OK -> "Shizuku 已授权" to false
        ShizukuManager.ShizukuDiagnosis.NOT_INSTALLED -> "Shizuku 未安装" to true
        ShizukuManager.ShizukuDiagnosis.NOT_CONNECTED -> "Shizuku 服务未启动" to true
        ShizukuManager.ShizukuDiagnosis.NOT_GRANTED -> "Shizuku 已连接，但未授权" to true
        ShizukuManager.ShizukuDiagnosis.UNKNOWN_ERROR -> "Shizuku 状态异常" to true
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 状态文字：灰色 13sp，错误时红色
            Text(
                text = statusText,
                fontSize = 13.sp,
                color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 授权按钮：蓝色填充
            Button(
                onClick = onAuthorizeClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(text = "授权")
            }

            // 打开 Shizuku App 按钮：描边样式
            OutlinedButton(
                onClick = onOpenShizukuClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(text = "打开 Shizuku App")
            }
        }
    }
}

/**
 * 关于卡片：设备型号 / 系统版本 / 屏幕分辨率 / 应用版本（横线表格）
 *
 * 自动读取 Build 与 WindowManager 真实分辨率；用横向分隔线模拟表格行边界。
 * 与 ShizukuCard 样式保持一致（0 阴影 12dp 圆角 surface 底色）。
 */
@Composable
private fun DeviceInfoCard() {
    val context = LocalContext.current

    // 应用版本名：读 PackageInfo（应用版本信息纳入到[关于]模块）
    val versionName = remember {
        try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // 设备型号：厂商 + 型号（厂商首字母大写，避免小写 "xiaomi" 看着突兀）
    val deviceModel = remember {
        val manufacturer = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        if (manufacturer.isBlank()) {
            model
        } else {
            // 厂商首字母大写，避免小写 "xiaomi" 看着突兀
            val cap = manufacturer.replaceFirstChar {
                if (it.isLowerCase()) it.uppercaseChar() else it
            }
            // 去重：部分机型 model 已包含厂商名（如 "Xiaomi 23049PCD"），避免 "Xiaomi Xiaomi..."
            if (model.equals(manufacturer, ignoreCase = true) || model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$cap $model"
            }
        }
    }

    // 系统版本：Android {release} (API {sdk})
    val osVersion = remember {
        val release = Build.VERSION.RELEASE ?: "未知"
        val sdk = Build.VERSION.SDK_INT
        "Android $release (API $sdk)"
    }

    // 屏幕分辨率：取真实物理像素（getRealMetrics 包含状态栏/导航栏区域）
    val screenResolution = remember {
        var w = 0
        var h = 0
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.let {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                it.defaultDisplay.getRealMetrics(metrics)
                w = metrics.widthPixels
                h = metrics.heightPixels
            }
        } catch (_: Exception) {
            // 极个别 ROM 取不到真实分辨率时，回退到资源 displayMetrics
            val dm = context.resources.displayMetrics
            w = dm.widthPixels
            h = dm.heightPixels
        }
        if (w > 0 && h > 0) "$w × $h" else "未知"
    }

    // 分隔线颜色：与 outline 资源 (#C9C4BE) 对齐，但用 colorScheme 适配主题
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
        .copy(alpha = 0.6f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 表格标题：关于（黑色 17sp SemiBold）
            Text(
                text = "关于",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)
            )
            TableDivider(color = dividerColor)

            // 表格行：设备型号 / 系统版本 / 屏幕分辨率 / 应用版本
            TableRow(label = "设备型号", value = deviceModel, dividerColor = dividerColor)
            TableRow(label = "系统版本", value = osVersion, dividerColor = dividerColor)
            TableRow(label = "屏幕分辨率", value = screenResolution, dividerColor = dividerColor)
            TableRow(label = "应用版本", value = if (versionName.isNotEmpty()) "v$versionName" else "未知", isLast = true)
        }
    }
}

/**
 * 表格行：左侧标签（灰色 13sp）+ 右侧值（黑色 14sp），SpaceBetween 布局，带底部横线
 *
 * @param isLast 最后一行不绘制底部分隔线，避免与卡片底边形成双线
 */
@Composable
private fun TableRow(
    label: String,
    value: String,
    dividerColor: Color = Color.Transparent,
    isLast: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        if (!isLast) {
            TableDivider(color = dividerColor)
        }
    }
}

/**
 * 表格横线：1dp 高，左右各留 16dp 内边距，避免触到卡片边缘
 */
@Composable
private fun TableDivider(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(color = color)
    )
}
