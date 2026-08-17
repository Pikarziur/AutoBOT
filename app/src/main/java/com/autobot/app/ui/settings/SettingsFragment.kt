package com.autobot.app.ui.settings

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.autobot.app.databinding.FragmentSettingsBinding
import com.autobot.app.manager.ShizukuManager
import rikka.shizuku.Shizuku

/**
 * 设置 Fragment
 *
 * 仅提供 Shizuku 授权入口：
 *   - 显示当前 Shizuku 状态（未安装/未连接/未授权/已授权）
 *   - 授权按钮：调用 Shizuku.requestPermission() 弹出授权对话框
 *   - 打开 Shizuku App：跳转到 Shizuku 管理界面
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 1001
    }

    // Shizuku 授权结果监听器
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(requireContext(), "Shizuku 授权成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
                }
                updateShizukuUI()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnAuthorize.setOnClickListener { handleAuthorizeClick() }
        binding.btnOpenShizuku.setOnClickListener {
            val ok = ShizukuManager.openShizukuApp(requireContext())
            if (!ok) {
                Toast.makeText(requireContext(), "未找到 Shizuku App", Toast.LENGTH_SHORT).show()
            }
        }
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
        updateShizukuUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 点击授权按钮：根据当前状态分支处理
     *   - 未安装 → Toast 提示
     *   - 未连接 → 跳转 Shizuku App 启动服务
     *   - 已连接未授权 → 调用 requestPermission 弹出授权对话框
     *   - 已授权 → Toast 提示已授权
     */
    private fun handleAuthorizeClick() {
        val code = ShizukuManager.getShizukuStatusCode(requireContext())
        when (code) {
            0 -> Toast.makeText(requireContext(), "Shizuku 未安装，请先安装", Toast.LENGTH_SHORT).show()
            1 -> {
                Toast.makeText(requireContext(), "请先打开 Shizuku App 启动服务", Toast.LENGTH_SHORT).show()
                ShizukuManager.openShizukuApp(requireContext())
            }
            2 -> {
                // 已连接但未授权 → 请求权限
                try {
                    ShizukuManager.requestShizukuPermission(SHIZUKU_REQUEST_CODE)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "请求授权失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            3 -> Toast.makeText(requireContext(), "Shizuku 已授权", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 刷新 Shizuku 状态 UI
     */
    private fun updateShizukuUI() {
        if (_binding == null) return
        val ctx = requireContext()
        val statusText = ShizukuManager.getShizukuStatusText(ctx)
        binding.tvShizukuStatus.text = statusText
    }
}
