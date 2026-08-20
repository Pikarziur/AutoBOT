package com.autobot.app.recognition

import android.graphics.Bitmap
import android.util.Log
import com.autobot.app.service.CompositionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 识别任务执行器：定时从 VD frameBuffer 抓图 → 丢给 OpenCV/ML Kit 识别 → 点击中心点
 *
 * 与 MAA-Meow 的区别：
 * - 不用 C++ ImageReader 回调，直接用 CompositionService.getFrameBufferBitmap() 取帧
 * - 不用 MAA Core，识别结果直接驱动 injectTouchDown/Up 点击
 * - 频率 0.5s/次（非 60fps 持续抓），任务结束即停止
 * - 清图策略：每次取最新帧后立即处理，不积压、不存磁盘
 *
 * 用法：
 *   RecognitionExecutor.start(
 *       compositionService = cs,
 *       scope = scope,
 *       mode = RecognitionMode.TEMPLATE,
 *       template = templateBitmap,
 *       onLog = { log -> appendLog(log) }
 *   )
 *   RecognitionExecutor.stop()
 */
object RecognitionExecutor {

    private const val TAG = "RecognitionExec"
    private const val CAPTURE_INTERVAL_MS = 500L

    private val running = AtomicBoolean(false)

    @Volatile
    private var job: Job? = null

    fun isExecuting(): Boolean = running.get()

    /**
     * 启动识别任务
     *
     * @param compositionService VD 服务（取帧 + 注入点击）
     * @param scope              协程作用域
     * @param mode               识别模式（TEMPLATE / OCR / BOTH）
     * @param template           模板图（TEMPLATE 模式必填，OCR 模式可为 null）
     * @param targetText         目标文字（OCR 模式必填，找到该文字后点击其中心）
     * @param threshold          模板匹配阈值（默认 0.8）
     * @param maxAttempts        最大尝试次数（0 = 无限，直到找到或用户停止）
     * @param onLog              日志回调
     */
    fun start(
        compositionService: CompositionService,
        scope: CoroutineScope,
        mode: RecognitionMode,
        template: Bitmap? = null,
        targetText: String? = null,
        threshold: Double = 0.8,
        maxAttempts: Int = 0,
        onLog: (String) -> Unit
    ) {
        if (running.get()) {
            onLog("⚠️  识别任务已在执行中")
            return
        }

        running.set(true)
        val taskStartMs = System.currentTimeMillis()
        var attempts = 0

        onLog("")
        onLog("╔══════════════════════════════════════════╗")
        onLog("║ 🔍 识别任务启动                            ║")
        onLog("║ 📺 VD 尺寸：${"${compositionService.width}×${compositionService.height}".padEnd(24).take(24)} ║")
        onLog("║ 🆔 displayId：${"${compositionService.displayId}".padEnd(23).take(23)} ║")
        onLog("║ ⏱  抓图间隔：${"${CAPTURE_INTERVAL_MS}ms".padEnd(24).take(24)} ║")
        onLog("║ 🎯 识别模式：${mode.name.padEnd(24).take(24)} ║")
        onLog("╚══════════════════════════════════════════╝")

        job = scope.launch(Dispatchers.IO) {
            try {
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    if (maxAttempts > 0 && attempts >= maxAttempts) {
                        onLog("已达最大尝试次数 $maxAttempts，停止")
                        break
                    }
                    attempts++

                    // 1. 抓图（取 frameBuffer 最新帧，不积压）
                    val bitmap = compositionService.getFrameBufferBitmap()
                    if (bitmap == null) {
                        onLog("[${attempts.toString().padStart(3, '0')}] ⚠️  frameBuffer 为空，等待下一轮")
                        delay(CAPTURE_INTERVAL_MS)
                        continue
                    }

                    onLog("[${attempts.toString().padStart(3, '0')}] 📸 抓图成功 ${bitmap.width}×${bitmap.height}")

                    // 2. 识别
                    val clickPoint = when (mode) {
                        RecognitionMode.TEMPLATE -> {
                            if (template == null) {
                                onLog("  └─ ❌ 模板为空，无法识别")
                                null
                            } else {
                                val pt = RecognitionManager.findTemplate(bitmap, template, threshold)
                                if (pt != null) {
                                    onLog("  └─ ✅ 模板匹配成功 → ($pt.x, $pt.y)")
                                } else {
                                    onLog("  └─ ❌ 模板未匹配")
                                }
                                pt
                            }
                        }

                        RecognitionMode.OCR -> {
                            if (targetText.isNullOrBlank()) {
                                onLog("  └─ ❌ 目标文字为空，无法识别")
                                null
                            } else {
                                val results = RecognitionManager.recognizeText(bitmap)
                                val match = results.find { it.text.contains(targetText) }
                                if (match != null) {
                                    val pt = android.graphics.Point(match.x, match.y)
                                    onLog("  └─ ✅ OCR 匹配「$targetText」→ ($pt.x, $pt.y)")
                                    onLog("     └─ 原文：${match.text}")
                                    pt
                                } else {
                                    onLog("  └─ ❌ OCR 未找到「$targetText」(识别到 ${results.size} 个文字块)")
                                    null
                                }
                            }
                        }

                        RecognitionMode.BOTH -> {
                            // 先模板匹配，失败再 OCR 兜底
                            var pt: android.graphics.Point? = null
                            if (template != null) {
                                pt = RecognitionManager.findTemplate(bitmap, template, threshold)
                                if (pt != null) {
                                    onLog("  └─ ✅ 模板匹配成功 → ($pt.x, $pt.y)")
                                }
                            }
                            if (pt == null && !targetText.isNullOrBlank()) {
                                val results = RecognitionManager.recognizeText(bitmap)
                                val match = results.find { it.text.contains(targetText) }
                                if (match != null) {
                                    pt = android.graphics.Point(match.x, match.y)
                                    onLog("  └─ ✅ OCR 兜底匹配「$targetText」→ ($pt.x, $pt.y)")
                                } else {
                                    onLog("  └─ ❌ 模板+OCR 均未匹配")
                                }
                            }
                            pt
                        }
                    }

                    // 3. 识别成功 → 点击中心点
                    if (clickPoint != null) {
                        onLog("  └─ 👆 点击 ($clickPoint.x, $clickPoint.y)")
                        compositionService.injectTouchDown(clickPoint.x, clickPoint.y)
                        delay(50)
                        compositionService.injectTouchUp(clickPoint.x, clickPoint.y)
                        onLog("  └─ ✓ 点击完成")
                        break
                    }

                    // 4. 未识别到，等待下一轮
                    delay(CAPTURE_INTERVAL_MS)
                }

                // 任务结束日志
                val totalMs = System.currentTimeMillis() - taskStartMs
                onLog("")
                if (running.get()) {
                    onLog("✅ 识别任务完成 · 尝试 $attempts 次 · 耗时 ${totalMs / 1000.0}s")
                } else {
                    onLog("⏹  识别任务已停止 · 尝试 $attempts 次 · 耗时 ${totalMs / 1000.0}s")
                }
            } catch (e: Exception) {
                onLog("❌ 识别任务出错：${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Recognition task error", e)
            } finally {
                running.set(false)
                job = null
            }
        }
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        job?.let { j ->
            CoroutineScope(Dispatchers.IO).launch {
                try { j.cancelAndJoin() } catch (_: Exception) {}
            }
        }
    }
}

/**
 * 识别模式
 * - TEMPLATE：OpenCV 模板匹配找按钮
 * - OCR：ML Kit 识别文字后点击
 * - BOTH：先模板匹配，失败再 OCR 兜底
 */
enum class RecognitionMode {
    TEMPLATE,
    OCR,
    BOTH
}
