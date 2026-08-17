package com.autobot.app.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.autobot.app.databinding.FragmentSettingsBinding
import com.autobot.app.util.DeviceInfoUtil

/**
 * 设置 Fragment
 * 管理通用设置、ADB设置、版本信息等
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // SharedPreferences 相关键名
    private val PREFS_NAME = "autobot_prefs"
    private val KEY_AUTO_CONNECT_SHIZUKU = "auto_connect_shizuku"
    private val KEY_ADB_IP = "adb_ip"
    private val KEY_ADB_PORT = "adb_port"

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

        // 修复 MaterialSwitch NPE：即使 XML 已设置 textOn/textOff/showText，
        // 某些设备/版本下 SwitchCompat.makeLayout 仍可能因 null 崩，代码层再强制设一次
        binding.switchAutoConnect.apply {
            textOn = ""
            textOff = ""
            isShowText = false
            text = ""
        }

        loadSavedSettings()
        initAboutSection()
        initSaveButton()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 加载已保存的设置
     */
    private fun loadSavedSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 自动连接 Shizuku
        binding.switchAutoConnect.isChecked =
            prefs.getBoolean(KEY_AUTO_CONNECT_SHIZUKU, true)

        // ADB IP
        val savedIp = prefs.getString(KEY_ADB_IP, "127.0.0.1")
        binding.etAdbIp.setText(savedIp)

        // ADB 端口
        val savedPort = prefs.getInt(KEY_ADB_PORT, 5555)
        binding.etAdbPort.setText(savedPort.toString())
    }

    /**
     * 初始化关于区域
     */
    private fun initAboutSection() {
        // 版本号
        binding.tvSettingsVersion.text = DeviceInfoUtil.getAppVersionName(requireContext())
        // 设备型号
        binding.tvDeviceModel.text = DeviceInfoUtil.getDeviceModel()
        // 系统版本
        binding.tvAndroidVersion.text = DeviceInfoUtil.getAndroidVersion()
    }

    /**
     * 初始化保存按钮
     */
    private fun initSaveButton() {
        binding.btnSaveAdb.setOnClickListener {
            saveSettings()
        }
    }

    /**
     * 保存设置
     */
    private fun saveSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // 自动连接
        editor.putBoolean(KEY_AUTO_CONNECT_SHIZUKU, binding.switchAutoConnect.isChecked)

        // ADB IP
        val ip = binding.etAdbIp.text?.toString()?.trim()
        if (!ip.isNullOrEmpty()) {
            editor.putString(KEY_ADB_IP, ip)
        } else {
            Toast.makeText(requireContext(), "IP地址不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        // ADB 端口
        val portStr = binding.etAdbPort.text?.toString()?.trim()
        val port = portStr?.toIntOrNull()
        if (port != null && port in 1..65535) {
            editor.putInt(KEY_ADB_PORT, port)
        } else {
            Toast.makeText(requireContext(), "端口范围无效 (1-65535)", Toast.LENGTH_SHORT).show()
            return
        }

        editor.apply()
        Toast.makeText(requireContext(), "设置已保存", Toast.LENGTH_SHORT).show()
    }
}
