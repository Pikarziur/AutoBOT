package com.autobot.app.ui.settings

import android.app.Application
import android.content.Context
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
 * 虚拟显示器分辨率模式（仿 MAA-Meow 的 720P / 1080P 两档）
 *
 *   VD_720P  → 720 × 1280，DPI=320（竖屏，默认档，省内存 + 预览更流畅）
 *   VD_1080P → 1080 × 1920，DPI=420（竖屏，小控件/OCR 更清晰）
 *
 * 数据三重冗余：
 *   枚举名         → SharedPreferences 持久化
 *   width / height → CompositionService 调 createVirtualDisplay 时直接使用
 *   dpi            → 用于 VD 内 App 的密度桶（和大多数主流手机匹配）
 */
enum class VdResolutionMode(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val displayName: String
) {
    VD_720P(720, 1280, 320, "720P（720×1280，省内存·流畅）"),
    VD_1080P(1080, 1920, 420, "1080P（1080×1920，清晰·耗内存）");

    companion object {
        const val PREFS_NAME = "autobot_settings"
        const val KEY_VD_RESOLUTION = "vd_resolution_mode"

        /** 默认模式：720P，与 MAA-Meow 默认一致 */
        val DEFAULT = VD_720P

        /** 从共享参数读取，找不到 / 非法值 一律回落 DEFAULT，保证任何版本启动不会崩 */
        fun readFromPrefs(ctx: Context): VdResolutionMode = runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_VD_RESOLUTION, DEFAULT.name)
            valueOf(name ?: DEFAULT.name)
        }.getOrElse { DEFAULT }

        fun saveToPrefs(ctx: Context, mode: VdResolutionMode) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VD_RESOLUTION, mode.name)
                .apply()
        }
    }
}

/**
 * 设置页面 ViewModel
 *
 * 替代原 SettingsFragment 的职责（纯 Compose 架构下 Shizuku 逻辑下沉到此处）：
 *  - 持有 Shizuku 诊断状态（StateFlow，Compose 自动 recompose）
 *  - 注册 / 注销 Shizuku 权限结果监听器（v13+ 要求显式注册才能收到回调）
 *  - 处理授权点击分支逻辑（未安装 / 未连接 / 未授权 / 已授权 / 异常）
 *  - 通过 toast SharedFlow 通知 UI 显示 Toast（ViewModel 不直接 Toast，避免持有 Activity context）
 *  - 管理持久化设置项：vdResolutionMode（VD 分辨率 720P/1080P，默认 720P）
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

    /** 当前 VD 分辨率模式（从 SharedPreferences 加载，UI 可切换，持久化） */
    private val _vdResolutionMode = MutableStateFlow(VdResolutionMode.readFromPrefs(application))
    val vdResolutionMode: StateFlow<VdResolutionMode> = _vdResolutionMode.asStateFlow()

    /** 一次性 Toast 事件流（extraBufferCapacity 防背压丢消息） */
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    /**
     * Shizuku 授权结果监听器
     * 注意：Shizuku v13+ 已移除 checkCallingPermission()，回调签名仅 grantResult
     */
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                _toast.tryEmit("Shizuku 授权成功")
            } else {
                _toast.tryEmit("Shizuku 授权被拒绝")
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
     *   - 未安装 → Toast 提示
     *   - 未连接 → Toast 提示并跳转 Shizuku App 启动服务
     *   - 已连接未授权 → 请求权限（弹出 Shizuku 对话框）
     *   - 已授权 → Toast 提示
     *   - 未知异常 → Toast 提示
     */
    fun authorize() {
        val ctx = getApplication<Application>()
        when (_diagnosis.value) {
            ShizukuManager.ShizukuDiagnosis.NOT_INSTALLED ->
                _toast.tryEmit("Shizuku 未安装，请先安装")
            ShizukuManager.ShizukuDiagnosis.NOT_CONNECTED -> {
                _toast.tryEmit("请先打开 Shizuku App 启动服务")
                ShizukuManager.openShizukuApp(ctx)
            }
            ShizukuManager.ShizukuDiagnosis.NOT_GRANTED -> {
                try {
                    ShizukuManager.requestShizukuPermission(SHIZUKU_REQUEST_CODE)
                } catch (e: Exception) {
                    _toast.tryEmit("请求授权失败：${e.message}")
                }
            }
            ShizukuManager.ShizukuDiagnosis.OK ->
                _toast.tryEmit("Shizuku 已授权")
            ShizukuManager.ShizukuDiagnosis.UNKNOWN_ERROR ->
                _toast.tryEmit("Shizuku 状态异常，请重启 Shizuku 服务后重试")
        }
    }

    /**
     * 点击「打开 Shizuku App」按钮
     */
    fun openShizukuApp() {
        if (!ShizukuManager.openShizukuApp(getApplication())) {
            _toast.tryEmit("未找到 Shizuku App")
        }
    }

    /**
     * 切换 VD 分辨率模式（720P / 1080P）。
     *   - 立即写入 SharedPreferences（下次启动 VD 生效）
     *   - 发出 Toast：提示"重启 VD 后生效"，避免用户以为当前正在运行的 VD 会实时变大
     */
    fun setVdResolutionMode(mode: VdResolutionMode) {
        if (_vdResolutionMode.value == mode) return
        _vdResolutionMode.value = mode
        VdResolutionMode.saveToPrefs(getApplication(), mode)
        _toast.tryEmit("已切换为「${mode.displayName}」，下一次启动 VD 后生效")
    }
}
