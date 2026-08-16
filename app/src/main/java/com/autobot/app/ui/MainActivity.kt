package com.autobot.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.autobot.app.R
import com.autobot.app.databinding.ActivityMainBinding
import com.autobot.app.manager.ShizukuManager
import com.autobot.app.ui.home.HomeFragment
import com.autobot.app.ui.settings.SettingsFragment
import com.autobot.app.ui.tasks.TasksFragment

/**
 * 主 Activity
 * 承载底部导航和3个页面Fragment的切换
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Fragment 字段改为 lateinit var：
    // 首次启动时 new 新实例；Activity 重建时从 FragmentManager 恢复
    private lateinit var homeFragment: HomeFragment
    private lateinit var tasksFragment: TasksFragment
    private lateinit var settingsFragment: SettingsFragment

    private lateinit var activeFragment: Fragment

    // 通知权限请求码
    private val NOTIFICATION_REQUEST_CODE = 2001

    // SharedPreferences 相关键名
    private val PREFS_NAME = "autobot_prefs"
    private val KEY_AUTO_CONNECT_SHIZUKU = "auto_connect_shizuku"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fragment 实例策略：有 savedState 就从 FragmentManager 取回，否则新建
        if (savedInstanceState == null) {
            homeFragment = HomeFragment()
            tasksFragment = TasksFragment()
            settingsFragment = SettingsFragment()
            initFragments()
            activeFragment = homeFragment
        } else {
            with(supportFragmentManager) {
                homeFragment = (findFragmentByTag(TAG_HOME) as? HomeFragment) ?: HomeFragment()
                tasksFragment = (findFragmentByTag(TAG_TASKS) as? TasksFragment) ?: TasksFragment()
                settingsFragment = (findFragmentByTag(TAG_SETTINGS) as? SettingsFragment) ?: SettingsFragment()
                activeFragment = findFragmentById(R.id.fragment_container) ?: homeFragment
            }
        }

        setupBottomNavigation()

        // 初始选中项与当前显示的 Fragment 对齐
        binding.navView.selectedItemId = when (activeFragment) {
            homeFragment -> R.id.nav_home
            tasksFragment -> R.id.nav_tasks
            settingsFragment -> R.id.nav_settings
            else -> R.id.nav_home
        }

        checkNotificationPermission()
        tryAutoConnectShizuku()
    }

    /**
     * 初始化 Fragment 并显示首页
     */
    private fun initFragments() {
        supportFragmentManager.beginTransaction().apply {
            add(R.id.fragment_container, homeFragment, TAG_HOME)
            add(R.id.fragment_container, tasksFragment, TAG_TASKS).hide(tasksFragment)
            add(R.id.fragment_container, settingsFragment, TAG_SETTINGS).hide(settingsFragment)
        }.commit()
    }

    /**
     * 设置底部导航栏
     */
    private fun setupBottomNavigation() {
        binding.navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    switchFragment(homeFragment)
                    true
                }
                R.id.nav_tasks -> {
                    switchFragment(tasksFragment)
                    true
                }
                R.id.nav_settings -> {
                    switchFragment(settingsFragment)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 切换 Fragment（使用 hide/show 保留状态）
     */
    private fun switchFragment(target: Fragment) {
        if (activeFragment == target) return

        supportFragmentManager.beginTransaction().apply {
            hide(activeFragment)
            if (!target.isAdded) {
                add(R.id.fragment_container, target)
            }
            show(target)
        }.commit()

        activeFragment = target
    }

    /**
     * 检查并请求通知权限（Android 13+）
     * 用于前台服务通知的显示
     */
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

    /**
     * 根据设置尝试自动连接 Shizuku
     */
    private fun tryAutoConnectShizuku() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT_SHIZUKU, true)
        if (autoConnect && ShizukuManager.isShizukuInstalled(this)) {
            // Shizuku 连接会通过 binder 自动建立，无需主动调用
            // 已在 Application 类注册监听
        }
    }

    companion object {
        private const val TAG_HOME = "fragment_home"
        private const val TAG_TASKS = "fragment_tasks"
        private const val TAG_SETTINGS = "fragment_settings"
    }
}
