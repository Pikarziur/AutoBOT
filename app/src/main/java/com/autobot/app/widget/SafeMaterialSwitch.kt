package com.autobot.app.widget

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 安全版 MaterialSwitch —— 彻底解决 SwitchCompat.makeLayout NPE 崩溃
 *
 * 崩溃根源：
 *   androidx.appcompat.widget.SwitchCompat.makeLayout() 在某些设备/系统版本上，
 *   即使 textOn/textOff/showText 已设，内部 CharSequence 仍可能为 null，
 *   传入 android.text.StaticLayout 构造器时触发 NPE。
 *   且 onMeasure 调用时机早于 Fragment.onViewCreated，代码层设置为时已晚。
 *
 * 修复策略（最小化，避免 override 签名不匹配导致编译失败）：
 *   1. init 块立即将 textOn/textOff/text 置空、showText 设 false
 *   2. onMeasure 套 try-catch，NPE 时重置后重试，再失败用最小安全尺寸兜底
 */
class SafeMaterialSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialSwitchStyle
) : MaterialSwitch(context, attrs, defStyleAttr) {

    init {
        // 构造阶段立即初始化 —— 赶在首次 onMeasure 之前
        applySafeDefaults()
    }

    private fun applySafeDefaults() {
        super.setTextOn("")
        super.setTextOff("")
        super.setShowText(false)
        super.setText("")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        try {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } catch (e: NullPointerException) {
            // 防御性兜底：若 makeLayout 内部仍抛 NPE，强制重置后重试一次
            applySafeDefaults()
            try {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            } catch (e2: Throwable) {
                // 再次失败则用最小安全尺寸，保证不崩
                setMeasuredDimension(
                    resolveSizeAndState(suggestedMinimumWidth, widthMeasureSpec, 0),
                    resolveSizeAndState(suggestedMinimumHeight, heightMeasureSpec, 0)
                )
            }
        } catch (e: Throwable) {
            // 其他任何异常也不能让视图测量崩掉
            try {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            } catch (e2: Throwable) {
                setMeasuredDimension(
                    resolveSizeAndState(suggestedMinimumWidth, widthMeasureSpec, 0),
                    resolveSizeAndState(suggestedMinimumHeight, heightMeasureSpec, 0)
                )
            }
        }
    }
}
