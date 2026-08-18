package com.autobot.app.third

import android.content.Context
import android.hardware.display.DisplayManagerGlobal
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import rikka.shizuku.ShizukuBinderWrapper

/**
 * 通过 Shizuku 创建虚拟显示器的核心 Helper（参照 MAA-Meow + scrcpy 架构加固）。
 *
 * 两条创建路径（按序尝试，第一条失败自动 fallback 到第二条）：
 *
 *   路径 A（推荐，默认）——ShizukuBinderWrapper 全局替换 sInstance
 *     1. ServiceManager.getService("display") → 原始 IBinder
 *     2. ShizukuBinderWrapper(originalBinder) → 包装后的 binder（transact 走 Shizuku shell UID）
 *     3. IDisplayManager.Stub.asInterface(wrapped) → iDisplayManager proxy
 *     4. DisplayManagerGlobal(iDisplayManager) → 新的 dmg 实例
 *     5. 反射替换 DisplayManagerGlobal.sInstance = dmg（全局替换）
 *     6. new DisplayManager(FakeContext) → 再调 createVirtualDisplay 公开 API
 *     优点：全局 DisplayManagerGlobal 都走 Shizuku，后续任何系统调用都带 shell 权限
 *     风险：清除 final 字段在 ART/TWK 上可能失败；替换全局单例有副作用
 *
 *   路径 B（fallback，MAA-Meow 原始方式）——纯 FakeContext 不替换 sInstance
 *     1. Workarounds.apply() 已注入 ActivityThread + com.android.shell AppBindData
 *     2. new DisplayManager(FakeContext) 直接实例化
 *     3. 调用 createVirtualDisplay 公开 API
 *     原理：DisplayManager(Context) 内部会从 Context 拿到 packageName/SHELL_UID，
 *           系统服务侧 DisplayManagerService.createVirtualDisplay() 的校验
 *           （Android 12+ packageName must match calling uid）被 AttributionSource 绕过
 *     优点：零全局副作用；代码最简洁
 *     风险：仅在 App 进程中，callingUid 仍然是 app uid，部分 ROM 强校验 callingUid 会失败
 *
 * 前置条件：Shizuku 已授权；调用前已执行 Workarounds.apply()（由 FakeContext.get 内部保证）。
 */
object DisplayManagerHelper {
    private const val TAG = "DisplayMgrHelper"

    @Volatile
    private var initialized = false

    /** 路径 A 是否成功替换了 sInstance */
    @Volatile
    private var sInstanceReplaced = false

    /**
     * 初始化（仅执行一次）。
     * 优先尝试路径 A（ShizukuBinderWrapper + sInstance 替换），
     * 失败后记录详细日志并静默降级——路径 B 不需要 init。
     */
    @Synchronized
    fun init(baseContext: Context) {
        if (initialized) return

        // 确保 FakeContext / Workarounds 先准备好
        try {
            FakeContext.get(baseContext)
        } catch (e: Exception) {
            Log.e(TAG, "init: FakeContext prep failed", e)
        }

        try {
            tryPathAReplaceSInstance(baseContext)
        } catch (e: Exception) {
            Log.e(TAG, "init: Path A (replace sInstance) completely failed; will fall back to Path B", e)
        } finally {
            initialized = true
        }
    }

    /**
     * 路径 A：反射替换 DisplayManagerGlobal.sInstance。
     * 每一步都打详细日志，方便定位具体失败点。
     */
    private fun tryPathAReplaceSInstance(baseContext: Context) {
        // Step 1: ServiceManager.getService("display")
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        val displayBinder = getService.invoke(null, "display") as? IBinder
            ?: run {
                Log.e(TAG, "PathA step1 FAIL: ServiceManager.getService(\"display\") returned null")
                return
            }
        Log.i(TAG, "PathA step1 OK: got raw display binder = $displayBinder")

        // Step 2: 用 ShizukuBinderWrapper 包装（使 binder.transact() 走 shell UID）
        val wrappedBinder = ShizukuBinderWrapper(displayBinder)
        Log.i(TAG, "PathA step2 OK: ShizukuBinderWrapper wrapped, interface descriptor=" +
                runCatching { wrappedBinder.interfaceDescriptor }.getOrNull())

        // Step 3: IDisplayManager.Stub.asInterface(wrappedBinder)
        // 注意：MIUI 等 ROM 的隐藏 API 过滤会拦截 IDisplayManager$Stub，
        // 所以优先在运行时类（$Stub$Proxy）上查 asInterface，而不是在接口类上。
        val iDisplayManager = try {
            val stubClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, wrappedBinder)
        } catch (stubEx: Exception) {
            Log.w(TAG, "PathA step3 IDisplayManager\\$Stub approach blocked by MIUI, fallback to runtime Proxy class", stubEx)
            var result: Any? = null
            for (candidateClass in arrayOf(
                "android.hardware.display.IDisplayManager\$Stub\$Proxy",
                "android.hardware.display.IDisplayManager\$Stub"
            )) {
                try {
                    val c = Class.forName(candidateClass)
                    val m = c.getMethod("asInterface", IBinder::class.java)
                    result = m.invoke(null, wrappedBinder)
                    if (result != null) {
                        Log.i(TAG, "PathA step3 OK via class=$candidateClass")
                        break
                    }
                } catch (_: Exception) { /* try next */ }
            }
            result
        } ?: run {
            Log.e(TAG, "PathA step3 FAIL: all approaches to asInterface returned null")
            return
        }
        Log.i(TAG, "PathA step3 OK: iDisplayManager proxy = ${iDisplayManager.javaClass.name}")

        // Step 4: 反射 DisplayManagerGlobal(iDisplayManager) 构造器
        val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val dmgConstructor = run {
            // 优先匹配精确类型，再兜底匹配任何单参构造器
            try {
                dmgClass.getDeclaredConstructor(iDisplayManager.javaClass)
            } catch (_: NoSuchMethodException) {
                val iface = iDisplayManager.javaClass.interfaces.firstOrNull()
                val candidates = dmgClass.declaredConstructors.filter { it.parameterCount == 1 }
                val exact = if (iface != null) {
                    candidates.firstOrNull { it.parameterTypes[0] == iface }
                } else null
                exact ?: candidates.firstOrNull()
                ?: throw NoSuchMethodException("DisplayManagerGlobal(Interface) not found")
            }
        }
        dmgConstructor.isAccessible = true
        val dmgInstance = dmgConstructor.newInstance(iDisplayManager)
        Log.i(TAG, "PathA step4 OK: DisplayManagerGlobal instance created")

        // Step 5: 定位 sInstance 字段
        val sInstanceField: java.lang.reflect.Field = try {
            dmgClass.getDeclaredField("sInstance")
        } catch (e: NoSuchFieldException) {
            dmgClass.declaredFields.firstOrNull { f ->
                java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                f.type == dmgClass
            } ?: throw e
        }
        sInstanceField.isAccessible = true
        Log.i(TAG, "PathA step5 OK: found sInstance field = ${sInstanceField.name}, modifiers=${sInstanceField.modifiers}")

        // Step 6: 清除 final（关键易错点）
        val hadFinal = java.lang.reflect.Modifier.isFinal(sInstanceField.modifiers)
        if (hadFinal) {
            // 经典方式：通过 Field.class.getDeclaredField("accessFlags") 清 FINAL 位
            val accessFlagsField: java.lang.reflect.Field? = runCatching {
                java.lang.reflect.Field::class.java.getDeclaredField("accessFlags").apply { isAccessible = true }
            }.getOrElse {
                Log.w(TAG, "PathA step6 WARN: no accessFlags field; try modifiers() on newer JDK")
                null
            }
            if (accessFlagsField != null) {
                val oldFlags = accessFlagsField.getInt(sInstanceField)
                accessFlagsField.setInt(sInstanceField, oldFlags and java.lang.reflect.Modifier.FINAL.inv())
                Log.i(TAG, "PathA step6 OK: cleared FINAL via accessFlags, oldFlags=$oldFlags -> newFlags=${accessFlagsField.getInt(sInstanceField)}")
            } else {
                // Android 14+/JDK 17+ 的替代：Field 的 "modifiers" 字段（名称不同）
                runCatching {
                    val modsField = java.lang.reflect.Field::class.java.getDeclaredField("modifiers").apply { isAccessible = true }
                    val v = modsField.getInt(sInstanceField)
                    modsField.setInt(sInstanceField, v and java.lang.reflect.Modifier.FINAL.inv())
                    Log.i(TAG, "PathA step6 OK: cleared FINAL via Field.modifiers fallback")
                }.getOrElse { e ->
                    Log.e(TAG, "PathA step6 FAIL: cannot clear FINAL modifier on sInstance field. ART may cache this inline; falling back to Path B", e)
                    return
                }
            }
        } else {
            Log.i(TAG, "PathA step6 SKIP: sInstance field is not final")
        }

        // Step 7: 真正写 sInstance = dmgInstance
        sInstanceField.set(null, dmgInstance)

        // Step 8: 验证替换成功（读回来）
        val verify = sInstanceField.get(null)
        if (verify === dmgInstance) {
            sInstanceReplaced = true
            Log.i(TAG, "PathA ALL STEPS OK ✅: DisplayManagerGlobal.sInstance replaced with Shizuku-wrapped version")
        } else {
            Log.w(TAG, "PathA step8 WARN: verification failed (read-back differs). ART may have optimized final field inlining; still try Path B.")
        }
    }

    /**
     * 创建虚拟显示器（按序尝试路径 A → 路径 B）。
     *
     * @return VirtualDisplay? 成功返回实例，失败返回 null（详见 logcat）
     */
    fun createVirtualDisplay(
        context: Context,
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
        flags: Int
    ): VirtualDisplay? {
        if (!initialized) init(context)

        // 路径 A：先尝试（sInstance 可能已替换）
        var result = tryCreateByPath(context, name, width, height, density, surface, flags, "A")
        if (result != null) return result

        // 路径 B：fallback 到纯 FakeContext
        if (!sInstanceReplaced) {
            Log.w(TAG, "Path A returned null and sInstance was not replaced; trying Path B (pure FakeContext — no sInstance tamper)")
            result = tryCreateByPath(context, name, width, height, density, surface, flags, "B")
            if (result != null) return result
        }

        Log.e(TAG, "Both Path A and Path B failed — see stack traces above.")
        return null
    }

    /**
     * 单条路径的创建逻辑。
     * path="A" 用当前全局 DisplayManagerGlobal（可能已被 ShizukuBinderWrapper 替换）；
     * path="B" 强制使用 FakeContext，并希望 DisplayManager(Context) 不从全局取 sInstance。
     * 实际上两者在 Java 层代码一致——区别只在 init() 期间有没有替换 sInstance。
     * 这里分别打日志，便于定位。
     */
    private fun tryCreateByPath(
        context: Context,
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
        flags: Int,
        pathLabel: String
    ): VirtualDisplay? {
        return try {
            val fakeContext = FakeContext.get(context)
            Log.i(TAG, "[$pathLabel] new DisplayManager(FakeContext)...")
            val dmClass = android.hardware.display.DisplayManager::class.java
            val constructor = dmClass.getDeclaredConstructor(Context::class.java)
            constructor.isAccessible = true
            val dm = constructor.newInstance(fakeContext)

            // 6 参数公开 API：createVirtualDisplay(name, w, h, dpi, surface, flags)
            val method = dmClass.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
                Int::class.javaPrimitiveType
            )
            Log.i(TAG, "[$pathLabel] calling DisplayManager.createVirtualDisplay(\"$name\", ${width}x$height, dpi=$density, flags=0x${flags.toString(16)})...")
            val vd = method.invoke(dm, name, width, height, density, surface, flags) as? VirtualDisplay
            if (vd != null) {
                val displayId = vd.display?.displayId ?: -1
                Log.i(TAG, "[$pathLabel] ✅ VirtualDisplay created: name=$name, ${width}x${height}, displayId=$displayId")
            } else {
                Log.e(TAG, "[$pathLabel] createVirtualDisplay returned null (no exception thrown; check logcat for system_server side errors)")
            }
            vd
        } catch (e: Exception) {
            Log.e(TAG, "[$pathLabel] createVirtualDisplay exception: ${e.javaClass.simpleName}: ${e.message}", e)
            // 把根因挖出来，常见 SecurityException "packageName must match the calling uid"
            var cause: Throwable? = e
            while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
            if (cause != e && cause != null) {
                Log.e(TAG, "[$pathLabel] Root cause: ${cause.javaClass.simpleName}: ${cause.message}")
            }
            null
        }
    }

    /**
     * 构建虚拟显示器标志位（对齐 MAA-Meow buildDisplayFlags）。
     */
    fun buildDisplayFlags(): Int {
        var flags = (
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            or (1 shl 6)  // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH (hidden constant)
        )
        // API 33+（Android 13 Tiramisu）新增标志
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or (
                (1 shl 8)   // VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                or (1 shl 10)  // VIRTUAL_DISPLAY_FLAG_TRUSTED
                or (1 shl 11)  // VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                or (1 shl 12)  // VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                or (1 shl 13)  // VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
            )
            // API 34+（Android 14 UpsideDownCake）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or (
                    (1 shl 14)  // VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                    or (1 shl 15)  // VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
                    or (1 shl 16)  // VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED
                )
            }
        }
        Log.i(TAG, "buildDisplayFlags: SDK=${Build.VERSION.SDK_INT}, flags=0x${flags.toString(16)}")
        return flags
    }
}
