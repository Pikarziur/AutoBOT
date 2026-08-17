package com.autobot.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.autobot.app.R
import com.autobot.app.databinding.ActivityMainBinding
import com.autobot.app.ui.settings.SettingsFragment
import com.autobot.app.ui.tasks.TasksFragment

/**
 * 主 Activity
 * 承载底部导航和 2 个页面 Fragment：后台任务、设置
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var tasksFragment: TasksFragment
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var activeFragment: Fragment

    private val NOTIFICATION_REQUEST_CODE = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            tasksFragment = TasksFragment()
            settingsFragment = SettingsFragment()
            initFragments()
            activeFragment = tasksFragment
        } else {
            with(supportFragmentManager) {
                tasksFragment = (findFragmentByTag(TAG_TASKS) as? TasksFragment) ?: TasksFragment()
                settingsFragment = (findFragmentByTag(TAG_SETTINGS) as? SettingsFragment) ?: SettingsFragment()
                activeFragment = findFragmentById(R.id.fragment_container) ?: tasksFragment
            }
        }

        setupBottomNavigation()
        binding.navView.selectedItemId = when (activeFragment) {
            settingsFragment -> R.id.nav_settings
            else -> R.id.nav_tasks
        }

        checkNotificationPermission()
    }

    /**
     * 初始化 Fragment 并默认显示后台任务页
     */
    private fun initFragments() {
        supportFragmentManager.beginTransaction().apply {
            add(R.id.fragment_container, tasksFragment, TAG_TASKS)
            add(R.id.fragment_container, settingsFragment, TAG_SETTINGS).hide(settingsFragment)
        }.commit()
    }

    /**
     * 设置底部导航栏
     */
    private fun setupBottomNavigation() {
        binding.navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_tasks -> { switchFragment(tasksFragment); true }
                R.id.nav_settings -> { switchFragment(settingsFragment); true }
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

    companion object {
        private const val TAG_TASKS = "fragment_tasks"
        private const val TAG_SETTINGS = "fragment_settings"
    }
}
