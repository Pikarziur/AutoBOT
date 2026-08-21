package com.autobot.app.recognition

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 识别管理器：封装 OpenCV 模板匹配 + ML Kit OCR
 *
 * 两个能力互补：
 * - [findTemplate]：OpenCV 模板匹配，找按钮位置（快、精确，需预先截好模板）
 * - [recognizeText]：ML Kit OCR，读屏幕文字（不需模板，能读动态数值）
 *
 * 使用前必须先调用 [AutoBOTApp.onCreate] 中的 OpenCVLoader.initLocal()
 *
 * CPU 优化：[findTemplate] 内部缓存 screenMat 和 templateMat，frameCount 未变时跳过
 * bitmapToMat（OpenCV 像素拷贝，1080P 单次 ~5-10ms）。注意调用方需传 frameCount。
 */
object RecognitionManager {

    private const val TAG = "RecognitionManager"

    // ML Kit 中文识别器（单例，避免重复创建）
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    // ==================== OpenCV Mat 缓存（CPU 优化）====================
    /** screenMat 缓存：frameCount 未变时复用，避免重复 bitmapToMat */
    private val cachedScreenMat = Mat()
    @Volatile private var cachedScreenFrameCount: Long = -1
    @Volatile private var cachedScreenW: Int = 0
    @Volatile private var cachedScreenH: Int = 0
    private val screenCacheLock = Any()

    /** templateMat 缓存：同一 template bitmap 引用复用 */
    private val cachedTemplateMat = Mat()
    @Volatile private var cachedTemplateRef: Bitmap? = null
    private val templateCacheLock = Any()

    /**
     * OpenCV 模板匹配：在截图中查找模板图的位置
     *
     * @param screen   VD 截图
     * @param template 要查找的按钮/图标模板图
     * @param threshold 匹配阈值（0~1，越高越严格，默认 0.8）
     * @param frameCount VD 帧计数（用于缓存命中判断，<0 表示禁用缓存）
     * @return 匹配到的中心坐标，未找到返回 null
     */
    fun findTemplate(
        screen: Bitmap,
        template: Bitmap,
        threshold: Double = 0.8,
        frameCount: Long = -1
    ): android.graphics.Point? {
        val resultMat = Mat()
        try {
            // screenMat 缓存：frameCount 相同且尺寸匹配则复用，否则重新 bitmapToMat
            val screenMat = synchronized(screenCacheLock) {
                val cacheHit = frameCount >= 0 &&
                        frameCount == cachedScreenFrameCount &&
                        cachedScreenW == screen.width &&
                        cachedScreenH == screen.height &&
                        !cachedScreenMat.empty()
                if (!cacheHit) {
                    Utils.bitmapToMat(screen, cachedScreenMat)
                    cachedScreenFrameCount = frameCount
                    cachedScreenW = screen.width
                    cachedScreenH = screen.height
                }
                cachedScreenMat  // 注意：返回的是缓存实例，matchTemplate 内部只读不写
            }

            // templateMat 缓存：同一 template 引用复用
            val templateMat = synchronized(templateCacheLock) {
                if (cachedTemplateRef !== template || cachedTemplateMat.empty()) {
                    Utils.bitmapToMat(template, cachedTemplateMat)
                    cachedTemplateRef = template
                }
                cachedTemplateMat
            }

            Imgproc.matchTemplate(
                screenMat,
                templateMat,
                resultMat,
                Imgproc.TM_CCOEFF_NORMED
            )

            val mmr = Core.minMaxLoc(resultMat)
            if (mmr.maxVal >= threshold) {
                val cx = mmr.maxLoc.x + template.width / 2.0
                val cy = mmr.maxLoc.y + template.height / 2.0
                Log.i(TAG, "findTemplate: matched at ($cx, $cy), score=${mmr.maxVal}")
                return android.graphics.Point(cx.toInt(), cy.toInt())
            } else {
                Log.i(TAG, "findTemplate: no match, maxScore=${mmr.maxVal} < $threshold")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "findTemplate error", e)
            return null
        } finally {
            // 只 release resultMat；screenMat 和 templateMat 是缓存实例，跨调用复用
            resultMat.release()
        }
    }

    /**
     * ML Kit OCR：识别截图中的文字
     *
     * @param bitmap VD 截图
     * @return 识别结果列表（文字 + 坐标框）
     */
    suspend fun recognizeText(bitmap: Bitmap): List<OcrResult> = suspendCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val results = mutableListOf<OcrResult>()
                for (block in visionText.textBlocks) {
                    val bounds = block.boundingBox ?: continue
                    results.add(
                        OcrResult(
                            text = block.text,
                            x = bounds.centerX(),
                            y = bounds.centerY(),
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom
                        )
                    )
                }
                Log.i(TAG, "recognizeText: found ${results.size} blocks")
                cont.resume(results)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "recognizeText failed", e)
                cont.resume(emptyList())
            }
    }
}

/**
 * OCR 识别结果
 *
 * @param text  识别出的文字
 * @param x     文字中心 X 坐标
 * @param y     文字中心 Y 坐标
 * @param left  文字框左边界
 * @param top   文字框上边界
 * @param right 文字框右边界
 * @param bottom 文字框下边界
 */
data class OcrResult(
    val text: String,
    val x: Int,
    val y: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
