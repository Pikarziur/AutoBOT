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
 * 修复策略：
 *   1. 所有构造函数中立即将 textOn/textOff/text 置为空字符串，showText 设为 false
 *   2. 重写 onMeasure，整个流程套 try-catch，捕获异常时 fallback 到 super.onMeasure
 *   3. 额外覆写 setTextOn/setTextOff/setShowText/setText 防御 null 传入
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

    // ---- 对外 setter 统一防御 null ----

    override fun setTextOn(textOn: CharSequence?) {
        super.setTextOn(textOn ?: "")
    }

    override fun setTextOff(textOff: CharSequence?) {
        super.setTextOff(textOff ?: "")
    }

    override fun setShowText(showText: Boolean) {
        // 任何情况都不显示文字 —— 避免 makeLayout 走绘制分支
        super.setShowText(false)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text ?: "", type)
    }
}
