package com.autobot.app.ui.settings

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import com.autobot.app.manager.ShizukuManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * 设置页面 ViewModel
 *
 * 替代原 SettingsFragment 的职责（纯 Compose 架构下 Shizuku 逻辑下沉到此处）：
 *  - 持有 Shizuku 诊断状态（StateFlow，Compose 自动 recompose）
 *  - 注册 / 注销 Shizuku 权限结果监听器（v13+ 要求显式注册才能收到回调）
 *  - 处理授权点击分支逻辑（未安装 / 未连接 / 未授权 / 已授权 / 异常）
 *  - 通过 toast SharedFlow 通知 UI 显示 Snackbar（ViewModel 不直接显示，避免持有 Activity context）
 *
 * 生命周期：viewModel() 绑定到 Activity，切换 Tab 不重建，
 *           因此 Shizuku 监听器在整个 Activity 生命周期内有效。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 1001
    }

    /** 当前 Shizuku 诊断状态 */
    private val _diagnosis = MutableStateFlow(ShizukuManager.ShizukuDiagnosis.NOT_INSTALLED)
    val diagnosis: StateFlow<ShizukuManager.ShizukuDiagnosis> = _diagnosis.asStateFlow()

    /** 一次性 Snackbar 事件流（extraBufferCapacity 防背压丢消息） */
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    /**
     * Shizuku 授权结果监听器
     * 注意：Shizuku v13+ 已移除 checkCallingPermission()，回调签名仅 grantResult
     */
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                _toast.tryEmit("授权成功")
            } else {
                _toast.tryEmit("授权被拒绝")
            }
            refreshShizukuStatus()
        }

    init {
        // Shizuku 未安装时 addRequestPermissionResultListener 会抛异常，需 try-catch
        try {
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {
            // Shizuku 不可用，忽略；UI 会显示 NOT_INSTALLED
        }
        refreshShizukuStatus()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {
        }
    }

    /**
     * 刷新 Shizuku 诊断状态
     * 用 Application context 调用 diagnoseShizuku，避免泄漏 Activity
     */
    fun refreshShizukuStatus() {
        _diagnosis.value = ShizukuManager.diagnoseShizuku(getApplication())
    }

    /**
     * 点击授权按钮：根据当前诊断状态分支处理
     *   - 未安装 → Snackbar 提示
     *   - 未连接 → Snackbar 提示并跳转 Shizuku App 启动服务
     *   - 已连接未授权 → 请求权限（弹出 Shizuku 对话框）
     *   - 已授权 → Snackbar 提示
     *   - 未知异常 → Snackbar 提示
     */
    fun authorize() {
        val ctx = getApplication<Application>()
        when (_diagnosis.value) {
            ShizukuManager.ShizukuDiagnosis.NOT_INSTALLED ->
                _toast.tryEmit("未安装 Shizuku")
            ShizukuManager.ShizukuDiagnosis.NOT_CONNECTED -> {
                _toast.tryEmit("请先启动 Shizuku 服务")
                ShizukuManager.openShizukuApp(ctx)
            }
            ShizukuManager.ShizukuDiagnosis.NOT_GRANTED -> {
                try {
                    ShizukuManager.requestShizukuPermission(SHIZUKU_REQUEST_CODE)
                } catch (e: Exception) {
                    _toast.tryEmit("请求授权失败")
                }
            }
            ShizukuManager.ShizukuDiagnosis.OK ->
                _toast.tryEmit("已授权")
            ShizukuManager.ShizukuDiagnosis.UNKNOWN_ERROR ->
                _toast.tryEmit("Shizuku 异常")
        }
    }

    /**
     * 点击「打开 Shizuku App」按钮
     */
    fun openShizukuApp() {
        if (!ShizukuManager.openShizukuApp(getApplication())) {
            _toast.tryEmit("未找到 Shizuku")
        }
    }
}
