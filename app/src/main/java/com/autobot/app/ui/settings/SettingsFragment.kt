package com.autobot.app.ui.settings

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.autobot.app.databinding.FragmentSettingsBinding
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.ui.theme.AutoBotTheme
import rikka.shizuku.Shizuku

/**
 * 设置 Fragment
 *
 * 改造为 ComposeView 承载 MAA-Meow 风格 Compose UI：
 *   - 标题栏 "设置"（22sp SemiBold）
 *   - Shizuku 状态卡片（0 阴影 12dp 圆角 16dp 内边距）
 *       - 状态文字（灰色 bodySmall 13sp，错误时红色）
 *       - 授权按钮（蓝色填充 Button）
 *       - 打开 Shizuku App 按钮（描边 OutlinedButton）
 *   - 关于卡片：应用名称 + 版本号
 *
 * 仍保留 Fragment 导航架构不变；Shizuku 权限监听器与 onResume 状态刷新保持原有逻辑。
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 1001
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // 当前 Shizuku 诊断状态：通过 mutableState 让 Compose 自动 recompose
    private var shizukuDiagnosis by mutableStateOf(ShizukuManager.ShizukuDiagnosis.NOT_INSTALLED)

    // Shizuku 授权结果监听器
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(requireContext(), "Shizuku 授权成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
                }
                refreshShizukuStatus()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        binding.composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeView.setContent {
            AutoBotTheme {
                SettingsScreen(
                    diagnosis = shizukuDiagnosis,
                    onAuthorizeClick = { handleAuthorizeClick() },
                    onOpenShizukuClick = { handleOpenShizukuClick() }
                )
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 首次进入也刷新一次状态，避免初始 NOT_INSTALLED 占位闪烁
        refreshShizukuStatus()
    }

    override fun onStart() {
        super.onStart()
        // 注册 Shizuku 权限结果监听（Shizuku v13+ 要求）
        try {
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {
            // Shizuku 未安装时注册会抛异常，忽略
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        // 切回设置页刷新 Shizuku 状态（用户可能在 Shizuku App 中授权后切回）
        refreshShizukuStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 刷新 Shizuku 诊断状态：写入 mutableState 后 Compose 自动 recompose
     */
    private fun refreshShizukuStatus() {
        shizukuDiagnosis = ShizukuManager.diagnoseShizuku(requireContext())
    }

    /**
     * 点击授权按钮：根据当前诊断状态分支处理
     *   - 未安装 → Toast 提示
     *   - 未连接 → Toast 提示并跳转 Shizuku App 启动服务
     *   - 已连接未授权 → 请求权限（弹出 Shizuku 对话框）
     *   - 已授权 → Toast 提示
     *   - 未知异常 → Toast 提示
     */
    private fun handleAuthorizeClick() {
        val ctx = requireContext()
        when (shizukuDiagnosis) {
            ShizukuManager.ShizukuDiagnosis.NOT_INSTALLED ->
                Toast.makeText(ctx, "Shizuku 未安装，请先安装", Toast.LENGTH_SHORT).show()
            ShizukuManager.ShizukuDiagnosis.NOT_CONNECTED -> {
                Toast.makeText(ctx, "请先打开 Shizuku App 启动服务", Toast.LENGTH_SHORT).show()
                ShizukuManager.openShizukuApp(ctx)
            }
            ShizukuManager.ShizukuDiagnosis.NOT_GRANTED -> {
                try {
                    ShizukuManager.requestShizukuPermission(SHIZUKU_REQUEST_CODE)
                } catch (e: Exception) {
                    Toast.makeText(ctx, "请求授权失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            ShizukuManager.ShizukuDiagnosis.OK ->
                Toast.makeText(ctx, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
            ShizukuManager.ShizukuDiagnosis.UNKNOWN_ERROR ->
                Toast.makeText(ctx, "Shizuku 状态异常，请重启 Shizuku 服务后重试", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 点击「打开 Shizuku App」按钮
     */
    private fun handleOpenShizukuClick() {
        val ctx = requireContext()
        val ok = ShizukuManager.openShizukuApp(ctx)
        if (!ok) {
            Toast.makeText(ctx, "未找到 Shizuku App", Toast.LENGTH_SHORT).show()
        }
    }
}

// ============================================================
// MAA-Meow 风格 Compose 组件
// ============================================================

/**
 * 设置页面根 Composable
 *
 * 颜色全部从 MaterialTheme.colorScheme 取，由 AutoBotTheme 统一注入
 */
@Composable
private fun SettingsScreen(
    diagnosis: ShizukuManager.ShizukuDiagnosis,
    onAuthorizeClick: () -> Unit,
    onOpenShizukuClick: () -> Unit
) {
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                onAuthorizeClick = onAuthorizeClick,
                onOpenShizukuClick = onOpenShizukuClick
            )

            AboutCard(versionName = versionName)
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
 * 关于卡片：应用名称 + 版本号
 */
@Composable
private fun AboutCard(versionName: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用名称：黑色 17sp
            Text(
                text = "AutoBOT",
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            // 版本号：灰色 13sp
            if (versionName.isNotEmpty()) {
                Text(
                    text = "v$versionName",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
