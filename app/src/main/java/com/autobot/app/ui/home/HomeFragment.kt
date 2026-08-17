package com.autobot.app.ui.home

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.autobot.app.R
import com.autobot.app.databinding.FragmentHomeBinding
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.util.DeviceInfoUtil
import rikka.shizuku.Shizuku

/**
 * 首页 Fragment
 * 展示设备信息（屏幕分辨率、APP版本）+ Shizuku授权卡片
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // Shizuku 权限请求码
    private val SHIZUKU_REQUEST_CODE = 1001

    // Shizuku 权限请求回调
    private val shizukuPermissionRequestListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                // 回调时 Fragment 可能已 detached，先检查 isAdded
                if (!isAdded || view == null) return@OnRequestPermissionResultListener
                val ctx = context ?: return@OnRequestPermissionResultListener
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(ctx, "Shizuku 授权成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "Shizuku 授权失败", Toast.LENGTH_SHORT).show()
                }
                updateShizukuUI()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 修复 MaterialSwitch NPE：即使 XML 已设置 textOn/textOff/showText，
        // 某些设备/版本下 SwitchCompat.makeLayout 仍可能因 null 崩，代码层再强制设一次
        binding.switchShizuku.apply {
            textOn = ""
            textOff = ""
            isShowText = false
            text = ""
        }

        // 注册 Shizuku 权限回调
        // 注意：Shizuku 未安装/未启动时 addRequestPermissionResultListener 会抛异常
        //       必须套 try-catch；ShizukuManager.isShizukuConnected 内部也会 pingBinder 抛异常捕获
        try {
            if (ShizukuManager.isShizukuInstalled(requireContext()) &&
                ShizukuManager.isShizukuConnected()) {
                Shizuku.addRequestPermissionResultListener(shizukuPermissionRequestListener)
            }
        } catch (e: Throwable) {
            android.util.Log.w("HomeFragment", "Shizuku addListener skipped", e)
        }

        initDeviceInfo()
        initShizukuCard()
    }

    override fun onResume() {
        super.onResume()
        updateShizukuUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            // 只有已注册过才能安全 remove；Shizuku 未连接时 remove 同样会崩
            if (ShizukuManager.isShizukuConnected()) {
                Shizuku.removeRequestPermissionResultListener(shizukuPermissionRequestListener)
            }
        } catch (e: Throwable) {
            android.util.Log.w("HomeFragment", "Shizuku removeListener skipped", e)
        }
        _binding = null
    }

    /**
     * 初始化设备信息显示
     */
    private fun initDeviceInfo() {
        // 屏幕分辨率 - 自动获取
        val resolutionText = DeviceInfoUtil.getScreenResolutionText(requireContext())
        binding.tvResolution.text = resolutionText

        // APP版本 - 默认0.01
        val versionName = DeviceInfoUtil.getAppVersionName(requireContext())
        binding.tvVersion.text = versionName
    }

    /**
     * 初始化 Shizuku 授权卡片
     */
    private fun initShizukuCard() {
        updateShizukuUI()

        // 点击卡片或开关都触发跳转/授权逻辑
        val clickListener = View.OnClickListener {
            handleShizukuClick()
        }
        binding.cardShizuku.setOnClickListener(clickListener)
        binding.switchShizuku.setOnClickListener {
            handleShizukuClick()
        }
    }

    /**
     * 处理 Shizuku 卡片点击事件
     * 优先级：
     * 1. 未安装 -> 提示
     * 2. 未连接 -> 跳转 Shizuku App
     * 3. 已连接未授权 -> 请求权限
     * 4. 已授权 -> 跳转 Shizuku App 管理
     */
    private fun handleShizukuClick() {
        val context = requireContext()
        when (ShizukuManager.getShizukuStatusCode(context)) {
            0 -> {
                // 未安装
                Toast.makeText(context, R.string.shizuku_not_installed, Toast.LENGTH_LONG).show()
                binding.switchShizuku.isChecked = false
            }
            1 -> {
                // 未连接 - 跳转 Shizuku App 启动服务
                val opened = ShizukuManager.openShizukuApp(context)
                if (!opened) {
                    Toast.makeText(context, "无法打开 Shizuku", Toast.LENGTH_SHORT).show()
                }
                binding.switchShizuku.isChecked = false
            }
            2 -> {
                // 已连接未授权 - 请求权限
                ShizukuManager.requestShizukuPermission(SHIZUKU_REQUEST_CODE)
            }
            3 -> {
                // 已授权 - 跳转 Shizuku App 管理
                val opened = ShizukuManager.openShizukuApp(context)
                if (!opened) {
                    Toast.makeText(context, "无法打开 Shizuku", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 更新 Shizuku UI 状态
     */
    private fun updateShizukuUI() {
        val context = requireContext()
        val statusCode = ShizukuManager.getShizukuStatusCode(context)
        val statusText = ShizukuManager.getShizukuStatusText(context)

        binding.tvShizukuStatus.text = statusText
        binding.switchShizuku.isChecked = statusCode == 3

        // 根据状态设置文字颜色
        val textColor = when (statusCode) {
            3 -> ContextCompat.getColor(context, R.color.success)
            2 -> ContextCompat.getColor(context, R.color.warning)
            else -> ContextCompat.getColor(context, R.color.text_secondary)
        }
        binding.tvShizukuStatus.setTextColor(textColor)
    }
}
