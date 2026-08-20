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

/** */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = viewModel()
    val context = LocalContext.current
    val diagnosis by vm.diagnosis.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.toast.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshShizukuStatus()
    }

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

            val vdMode by vm.vdResolutionMode.collectAsStateWithLifecycle()
            VirtualDisplayResolutionCard(
                selectedMode = vdMode,
                onModeSelected = { vm.setVdResolutionMode(it) }
            )

            DeviceInfoCard()
        }
    }
}

/** */
@Composable
private fun ShizukuCard(
    diagnosis: ShizukuManager.ShizukuDiagnosis,
    onAuthorizeClick: () -> Unit,
    onOpenShizukuClick: () -> Unit
) {
    val isAuthorized = diagnosis == ShizukuManager.ShizukuDiagnosis.OK

    val statusText = if (isAuthorized) "Shizuku 已授权" else "Shizuku 未授权"

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
            Text(
                text = "Shizuku",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

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

/** */
@Composable
private fun VirtualDisplayResolutionCard(
    selectedMode: VdResolutionMode,
    onModeSelected: (VdResolutionMode) -> Unit
) {
    val options = VdResolutionMode.values().asList()

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
            onClick = null,
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

/** */
@Composable
private fun DeviceInfoCard() {
    val context = LocalContext.current

    val versionName = remember {
        try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    val deviceModel = remember {
        val manufacturer = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        if (manufacturer.isBlank()) {
            model
        } else {
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

    val osVersion = remember {
        val release = Build.VERSION.RELEASE ?: "未知"
        val sdk = Build.VERSION.SDK_INT
        "Android $release (API $sdk)"
    }

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

    val dividerColor = MaterialTheme.colorScheme.outlineVariant
        .copy(alpha = 0.6f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
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

            TableRow(label = "设备型号", value = deviceModel, dividerColor = dividerColor)
            TableRow(label = "系统版本", value = osVersion, dividerColor = dividerColor)
            TableRow(label = "屏幕分辨率", value = screenResolution, dividerColor = dividerColor)
            TableRow(label = "应用版本", value = if (versionName.isNotEmpty()) "v$versionName" else "未知", isLast = true)
        }
    }
}

/** */
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

/** */
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
