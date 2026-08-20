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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * 排版规范：
 *   页面标题：22sp SemiBold（"设置"）
 *   卡片标题：17sp SemiBold（"Shizuku"、"关于"）
 *   正文/状态：14sp（动态信息）
 *   辅助标签：13sp（表格标签等）
 *   卡片内边距：16dp
 *   卡片间距：16dp
 *
 * 结构：
 *   Surface(background) → Column(verticalScroll)
 *     ├ 标题 "设置"（22sp SemiBold）
 *     ├ ShizukuCard：标题 + 状态行 + 启动行
 *     └ DeviceInfoCard：标题 + 表格
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 页面标题 "设置"
            Text(
                text = "设置",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            ShizukuCard(
                diagnosis = diagnosis,
                onAuthorizeClick = { vm.authorize() },
                onOpenShizukuClick = { vm.openShizukuApp() }
            )

            // VD 分辨率模式（仿 MAA-Meow 的 720P / 1080P）：放在 Shizuku 组件下方
            val vdMode by vm.vdResolutionMode.collectAsStateWithLifecycle()
            VirtualDisplayResolutionCard(
                selectedMode = vdMode,
                onModeSelected = { vm.setVdResolutionMode(it) }
            )

            DeviceInfoCard()
        }
    }
}

/**
 * Shizuku 状态卡片
 *
 * 布局：
 *   卡片标题 "Shizuku"（17sp SemiBold）
 *   Row(SpaceBetween)
 *     ├ 左侧：状态文字（纯黑色 14sp，动态切换）
 *     └ 右侧：Material3 Switch（已授权=开，未授权=关）
 *   Row(SpaceBetween)
 *     ├ 左侧："启动 Shizuku"（14sp）
 *     └ 右侧：蓝色 "打开" TextButton（14sp）
 */
@Composable
private fun ShizukuCard(
    diagnosis: ShizukuManager.ShizukuDiagnosis,
    onAuthorizeClick: () -> Unit,
    onOpenShizukuClick: () -> Unit
) {
    // 开关状态：OK 时为 true，其余状态为 false
    val isAuthorized = diagnosis == ShizukuManager.ShizukuDiagnosis.OK

    // 动态状态文字：纯黑色，已授权/未授权
    val statusText = if (isAuthorized) "Shizuku 已授权" else "Shizuku 未授权"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,  // 纯白底 #FFFFFF（覆盖 surface #F9F7F3）
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 卡片标题 "Shizuku"
            Text(
                text = "Shizuku",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 第一行：状态文字（纯黑） + Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = isAuthorized,
                    onCheckedChange = { onAuthorizeClick() },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }

            // 第二行：启动 Shizuku 文本 + 蓝色"打开"文本按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "启动 Shizuku",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onOpenShizukuClick) {
                    Text(
                        text = "打开",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * 虚拟显示器（VD）分辨率模式卡片 —— 仿 MAA-Meow 的 720P / 1080P 双档，默认 1080P。
 *
 * 布局（纯白卡片 12dp 圆角 0 阴影，与 Shizuku / 关于 风格统一）：
 *   标题行：
 *     左   Icons.Filled.DisplaySettings + "虚拟显示器分辨率" （17sp SemiBold）
 *     右   辅助提示 "下一次启动 VD 生效"
 *   选项卡（.selectableGroup，竖向 RadioButton + 文字 + 二级分辨率说明）：
 *     ◉ 720P（720×1280，省内存·流畅）
 *        ↳ 建议 2~4GB 小运存设备；预览 & 点击更快；淘宝/短视频主流脚本足够清晰
 *     ○ 1080P（1080×1920，清晰·耗内存）
 *        ↳ 建议 6GB+ 设备；小控件/OCR 等需要高像素密度场景；预览和 JPEG 传输延迟略高
 *
 * 选中变更会由 onModeSelected → SettingsViewModel 持久化，Toast 提示"下次启动 VD 生效"。
 */
@Composable
private fun VirtualDisplayResolutionCard(
    selectedMode: VdResolutionMode,
    onModeSelected: (VdResolutionMode) -> Unit
) {
    val options = VdResolutionMode.values().asList()

    // 每个档位下面一行二级说明（根据档位写死，避免塞进枚举带太多 UI 字段）
    fun subTitle(mode: VdResolutionMode): String = when (mode) {
        VdResolutionMode.VD_720P ->
            "建议 2~4GB 小运存设备 · 预览更流畅 · 淘宝/任务脚本足够清晰"
        VdResolutionMode.VD_1080P ->
            "建议 6GB+ 设备 · 小控件/OCR 更清晰 · JPEG 预览延迟略高"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 标题行：左（图标+文本） 右（辅助提示）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DisplaySettings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "虚拟显示器分辨率",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "下一次启动 VD 生效",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 选项组（竖向 RadioButton 单选，selectableGroup 语义无障碍）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                options.forEach { mode ->
                    VdModeOptionRow(
                        mode = mode,
                        subTitle = subTitle(mode),
                        selected = (mode == selectedMode),
                        onClick = { onModeSelected(mode) }
                    )
                }
            }
        }
    }
}

/** VD 档位单行：RadioButton ＋（档位名 ＋ 二级说明列） */
@Composable
private fun VdModeOptionRow(
    mode: VdResolutionMode,
    subTitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = androidx.compose.ui.semantics.Role.RadioButton
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = null, // 由 Row.selectable 统一接管点击
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary
            )
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = mode.displayName,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subTitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        color = Color.White,  // 纯白底 #FFFFFF（覆盖 surface #F9F7F3）
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 卡片标题 "关于"（17sp SemiBold，与 Shizuku 标题对齐）
            Text(
                text = "关于",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    start = 16.dp, end = 16.dp,
                    top = 16.dp, bottom = 12.dp
                )
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
 * 表格行：左侧标签（辅助灰 13sp）+ 右侧值（纯黑 14sp），SpaceBetween 布局，带底部横线
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
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
