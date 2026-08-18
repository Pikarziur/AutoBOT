# 重构方案：虚拟显示器创建迁移到独立 app_process 进程（Shizuku.newProcess）

## Context

当前项目的虚拟显示器创建走"App 进程 + ShizukuBinderWrapper 包装 display binder + 替换 DisplayManagerGlobal.sInstance"路径。问题：
- App 进程的 `Process.myUid()` 永远是 App UID，不是 shell UID
- Android 12+ system_server 侧 `DisplayManagerService.createVirtualDisplay()` 强校验 `callingUid == SHELL_UID`，App UID 被拒
- Workarounds.apply() 在 App 进程只能改 packageName 字符串，改不了内核 UID
- 真机表现为"点击启动按钮 → 虚拟显示器无法创建"

目标：对齐 scrcpy/MAA-Meow 架构 —— 用 `Shizuku.newProcess()` 启动独立 shell uid `app_process` 进程，在 server 进程内调用 `Workarounds.apply() + FakeContext + DisplayManager.createVirtualDisplay()`，因为 server 进程本身就是 shell uid，callingUid 校验自然通过。预期结果：全机型兼容，不再依赖清 FINAL 字段等概率性 workaround。

用户已确认两个关键决策：
- **A**. App 进程持有 AImageReader（NativeCapturer 不动），把 Surface 通过 Parcel 跨进程传给 server
- **B**. LocalSocket + Parcel 二进制流作为 App↔Server IPC 通道

---

## 架构总览

```
┌──────────────────── App 进程（uid=app） ────────────────────┐
│  MonitorViewModel ─► CompositionService                    │
│                          │                                  │
│                          ├─ NativeCapturer.setupNativeCapturer(w,h) → Surface1
│                          │   (AImageReader 消费侧不动，回调照常在 App 进程触发)
│                          │                                  │
│                          ├─ Surface1.writeToParcel → surfaceBytes
│                          ├─ new LocalServerSocket("com.autobot.app.vdserver")
│                          └─► ShizukuProcessManager.launchServer(socketName)
│                                    │ Shizuku.newProcess 反射
│                                    ▼
│  ┌────────── server 进程（uid=shell, app_process） ──────────┐
│  │  ServerMain.main(args):                               │
│  │    1. LocalSocket connect → "com.autobot.app.vdserver"│
│  │    2. 读 CREATE_VD(width,height,density,flags,parcel) │
│  │    3. Workarounds.apply() + FakeContext.get()          │
│  │    4. Surface.CREATOR.createFromParcel → Surface1'    │
│  │    5. new DisplayManager(FakeContext).createVirtualDisplay(..., Surface1')
│  │    6. 回写 displayId（或错误）                        │
│  │    7. PING 心跳保活；断连/RELEASE_VD → release VD + exit
│  └────────────────────────────────────────────────────────┘
│  BufferQueue 共享：VD → Surface1' (producer) → 同一 BufferQueue → AImageReader (App)
└────────────────────────────────────────────────────────────┘
```

---

## 文件变更清单

### 新增（4 个文件）

| # | 文件 | 职责 |
|---|---|---|
| 1 | `app/src/main/java/com/autobot/app/server/ServerMain.kt` | server 进程入口，`@JvmStatic fun main(args)` |
| 2 | `app/src/main/java/com/autobot/app/server/VDProtocol.kt` | 二进制协议（消息常量 + Parcel 编解码 + 4 字节长度前缀读写） + `VDRequest`/`VDResponse` 数据类 |
| 3 | `app/src/main/java/com/autobot/app/manager/ShizukuProcessManager.kt` | APK 推送 + `Shizuku.newProcess` 反射启动 + Process 生命周期 |
| 4 | （可选）`app/src/test/java/.../VDProtocolTest.kt` | 单元测试 round-trip 编解码 |

### 修改（3 个文件）

| # | 文件 | 改动点 |
|---|---|---|
| 1 | `app/src/main/java/com/autobot/app/third/DisplayManagerHelper.kt` | 删除 Path A（ShizukuBinderWrapper + sInstance 替换）；只保留 `new DisplayManager(FakeContext) + createVirtualDisplay` 路径；新增 `createVirtualDisplay(surface, name, w, h, dpi, flags): VirtualDisplay?` 供 ServerMain 直接调用 |
| 2 | `app/src/main/java/com/autobot/app/service/CompositionService.kt` | 删除 DisplayManagerHelper 直连调用；改为 LocalServerSocket + ShizukuProcessManager + VDProtocol；`displayId` 改为读 `cachedDisplayId`；新增心跳保活线程 |
| 3 | `app/proguard-rules.pro` | 加 `-keep class com.autobot.app.server.ServerMain { public static void main(java.lang.String[]); }` + keep `Workarounds`/`FakeContext`/`DisplayManagerHelper`（保险，未来启用 minify 时不会丢） |

### 不改

- `MonitorViewModel.kt` —— CompositionService 公共 API 完全兼容（签名不变，内部从直连 DisplayManager 切到 socket 协议对 ViewModel 透明）
- `NativeCapturer.kt` / `cpp/native_capturer.cpp` —— App 进程内 AImageReader 不动
- `FakeContext.kt` / `Workarounds.kt` —— 已就绪，server 进程直接复用
- `AndroidManifest.xml` —— server 进程不是 Android 组件，不需要 service 声明
- `build.gradle.kts` —— server 类编入 app 的 classes.dex，与 assembleDebug 一起完成，不需要独立 dex 编译 task
- `.github/workflows/android.yml` —— 无需修改；可选增强：编译后 `unzip -p app-debug.apk classes.dex \| strings \| grep ServerMain` 验证 server 类在 dex 中

---

## 关键代码片段

### 1. Shizuku.newProcess 反射（参考 [ShellExecutor.kt:204-230](file:///c:/Users/Dee/Desktop/AutoBOT/app/src/main/java/com/autobot/app/util/ShellExecutor.kt#L204-L230) 已验证模式）

```kotlin
fun launchServer(socketName: String): Process {
    val cmd = arrayOf(
        "app_process",
        "-Djava.class.path=$SERVER_APK_PATH",   // /data/local/tmp/autobot-server.apk
        "/",
        "com.autobot.app.server.ServerMain",
        socketName
    )
    val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
    val m = shizukuClass.getDeclaredMethod(
        "newProcess",
        Array<String>::class.java, Array<String>::class.java, String::class.java
    )
    m.isAccessible = true
    return m.invoke(null, cmd, null, null) as Process
}
```

### 2. Surface 跨进程 Parcel 传递

App 端（CompositionService.startVirtualDisplay）：
```kotlin
val p = Parcel.obtain()
surface.writeToParcel(p, 0)
p.setDataPosition(0)
val surfaceBytes = p.marshall()
p.recycle()
```

Server 端（ServerMain.handleCreateVd）：
```kotlin
val p = Parcel.obtain()
p.unmarshall(surfaceBytes, 0, surfaceBytes.size)
p.setDataPosition(0)
val surface = Surface.CREATOR.createFromParcel(p) as android.view.Surface
p.recycle()
```

### 3. LocalSocket abstract namespace

App 端：
```kotlin
val server = LocalServerSocket("com.autobot.app.vdserver")
val client = server.accept()  // 阻塞等 server 进程连入
VDProtocol.writeMessage(client.outputStream, VDProtocol.MSG_CREATE_VD, requestParcelBytes)
val (respType, respBytes) = VDProtocol.readMessage(client.inputStream)
```

Server 端：
```kotlin
val socket = LocalSocket()
socket.connect(LocalSocketAddress("com.autobot.app.vdserver", Namespace.ABSTRACT))
val (msgType, payload) = VDProtocol.readMessage(socket.inputStream)
```

### 4. VDProtocol 二进制协议

```kotlin
object VDProtocol {
    const val MSG_CREATE_VD = 1
    const val MSG_CREATE_VD_RESP = 2
    const val MSG_PING = 3
    const val MSG_PONG = 4
    const val MSG_RELEASE_VD = 5
    const val MSG_RELEASE_VD_RESP = 6

    fun writeMessage(out: OutputStream, type: Int, payload: ByteArray) {
        // 4 字节大端长度 + 4 字节 type + 4 字节 payload 长度 + payload
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use {
            it.writeInt(type)
            it.writeInt(payload.size)
            it.write(payload)
        }
        val frame = bos.toByteArray()
        val header = ByteArray(4)  // 大端 4 字节 frame.size
        header[0] = (frame.size ushr 24).toByte()
        header[1] = (frame.size ushr 16).toByte()
        header[2] = (frame.size ushr 8).toByte()
        header[3] = frame.size.toByte()
        out.write(header); out.write(frame); out.flush()
    }

    fun readMessage(input: InputStream): Pair<Int, ByteArray> { /* 对称读取 */ }
}
```

### 5. ServerMain 主循环

```kotlin
@JvmStatic
fun main(args: Array<String>) {
    val socketName = args.getOrElse(0) { "com.autobot.app.vdserver" }
    Log.i(TAG, "ServerMain start, socket=$socketName pid=${Process.myPid()}")
    val socket = LocalSocket()
    socket.connect(LocalSocketAddress(socketName, Namespace.ABSTRACT))
    val input = socket.inputStream; val out = socket.outputStream
    val (msgType, payload) = VDProtocol.readMessage(input)
    if (msgType != VDProtocol.MSG_CREATE_VD) { Log.e(TAG, "Unexpected first msg: $msgType"); return }
    handleCreateVd(payload, out)  // 内部写 MSG_CREATE_VD_RESP
    runKeepAliveLoop(input, out)  // PING/RELEASE_VD 分发；超时或断连 → release VD + exit
    socket.close()
}
```

### 6. APK 推送（scrcpy 同款）

```kotlin
private const val SERVER_APK_PATH = "/data/local/tmp/autobot-server.apk"

fun ensureServerDex(context: Context): String {
    val src = context.applicationInfo.sourceDir  // /data/app/.../base.apk（shell uid 可读）
    val cmd = "if [ -f $SERVER_APK_PATH ] && cmp -s $src $SERVER_APK_PATH; then " +
              "echo UP_TO_DATE; else cp $src $SERVER_APK_PATH && chmod 644 $SERVER_APK_PATH && echo COPIED; fi"
    ShellExecutor.execute(cmd, useShizuku = true, timeout = 10_000)
    return SERVER_APK_PATH
}
```

---

## 实施步骤

### Step 1 — 新增 VDProtocol.kt + VDRequest/VDResponse

- 路径：`app/src/main/java/com/autobot/app/server/VDProtocol.kt`
- 内容：消息常量、`writeMessage`/`readMessage`、`VDRequest(width, height, density, flags, surfaceBytes)` + `VDResponse(ok, displayId, error)` 数据类 + Parcel 编解码
- 验证：单元测试 round-trip 编解码（空 payload + 10KB Surface Parcel）

### Step 2 — 简化 DisplayManagerHelper.kt

- 删除 `tryPathAReplaceSInstance` 整段（约 120 行 ShizukuBinderWrapper/sInstance 替换）
- 删除 `import rikka.shizuku.ShizukuBinderWrapper`、`sInstanceReplaced` 字段
- `init()` 简化为空（server 进程不需要替换全局）
- 保留 `buildDisplayFlags()`
- `createVirtualDisplay` 改为直接调 `new DisplayManager(FakeContext) + 反射 createVirtualDisplay`
- 验证：`grep -n ShizukuBinderWrapper app/src/main` 应无残留

### Step 3 — 新增 ShizukuProcessManager.kt

- 路径：`app/src/main/java/com/autobot/app/manager/ShizukuProcessManager.kt`
- 实现 `ensureServerDex(context)`：通过 ShellExecutor.execute 执行 `cp + chmod 644`（参考代码片段 6）
- 实现 `launchServer(socketName)`：反射 `Shizuku.newProcess` 启动 `app_process`（参考代码片段 1，复用 ShellExecutor 已验证的反射模式）
- 实现 `destroyServer(process)` / `isServerAlive(process)`
- 验证：临时调用 `ensureServerDex + launchServer("dummy")`，确认 `adb shell ps -A | grep app_process` 出现新行，UID=shell

### Step 4 — 新增 ServerMain.kt

- 路径：`app/src/main/java/com/autobot/app/server/ServerMain.kt`
- 实现 `main(args)`：连 socket、读 CREATE_VD、调 Workarounds/FakeContext、反射 `new DisplayManager` 创建 VD（参考代码片段 5）
- 实现 `runKeepAliveLoop`：5s PING 间隔、15s 超时；RELEASE_VD → release VD + 写 RESP + break
- 实现 `unmarshallSurface(bytes)`（参考代码片段 2 server 端）
- 类级 `@Volatile var heldVd: VirtualDisplay?` / `heldSurface: Surface?`，释放时置 null
- 验证：logcat filter `ServerMain` + `DisplayMgrHelper` + `Workarounds`，从 App 触发 launchServer + 发 CREATE_VD，确认看到 "ServerMain start, socket=..." → Workarounds.apply OK → createVirtualDisplay 返回非 null → displayId > 0

### Step 5 — 改造 CompositionService.startVirtualDisplay

- 删除原步骤 3、4（DisplayManagerHelper 直连调用）
- 步骤 2 后：`surface.writeToParcel` → `surfaceBytes`（参考代码片段 2 App 端）
- 新建 `LocalServerSocket("com.autobot.app.vdserver")`，存到 `@Volatile var serverSocket`
- 调 `ShizukuProcessManager.ensureServerDex + launchServer(socketName)`，存到 `@Volatile var serverProcess`
- `serverSocket.accept()` → `VDProtocol.writeMessage(MSG_CREATE_VD, VDRequest(width,height,density,flags,surfaceBytes).toParcel())`
- `VDProtocol.readMessage` → `VDResponse`，校验 `ok=true && displayId>0` → `cachedDisplayId = resp.displayId`
- 失败：返回 `null to resp.error`，关闭所有句柄
- 成功：返回 `displaySurface to ""`（与原 API 一致）
- 验证：从 MonitorViewModel.startVirtualDisplay 触发，logcat 看完整链路；displayId StateFlow > 0；getFrameCount() 增长

### Step 6 — 心跳保活线程

- CompositionService 内启动 `keepAliveThread`：
  - 每 5s `VDProtocol.writeMessage(MSG_PING, emptyPayload)`
  - 读 `MSG_PONG`，未在 15s 内收到 → 视为 server 死亡，本地 `stopVirtualDisplay` 并报错
  - 线程退出条件：`keepAliveThread?.interrupt()` 在 stopVirtualDisplay 中调用
- 验证：手动 `kill -9 <server pid>`（通过 Shizuku），App 端 15s 内检测断连，`_isRunning` 转 false，UI 弹错误提示

### Step 7 — 改造 stopVirtualDisplay + restartVirtualDisplay

- `stopVirtualDisplay`：
  1. `keepAliveThread?.interrupt()`
  2. 若 client socket 存活：`VDProtocol.writeMessage(MSG_RELEASE_VD, emptyPayload)` → 等 `MSG_RELEASE_VD_RESP` 或 1s 超时
  3. `serverSocket.close()`、`serverProcess.destroyForcibly()`、`capturer.releaseNativeCapturer()`
  4. `cachedDisplayId = -1`
- `restartVirtualDisplay`：保持原逻辑 —— 调 stopVirtualDisplay 然后 startVirtualDisplay(newW, newH)
- 验证：连续 `startVirtualDisplay → stopVirtualDisplay → startVirtualDisplay` 5 轮，无 fd 泄漏（`adb shell ls -l /proc/<app pid>/fd | wc -l` 不应单调上升）

### Step 8 — 端到端验证

- `MonitorViewModel.toggleDisplayOrientation` 触发 `restartVirtualDisplay(960, 540)`
- 验证 server 旧进程退出、新进程启动、新 displayId 拿到、`am start --display <new displayId> <pkg>` 把 App 拉到新 VD 上
- 验证：从 UI 切到横屏 → 1s 后 `dumpsys display | grep "AutoBOT-VirtualDisplay"` 看到新 displayId；启动任意 App 进入该 displayId；预览 SurfaceView 显示该 App 画面

---

## 端到端验证清单

| 验证项 | 方法 | 期望 |
|---|---|---|
| 1. server APK 推送 | `adb shell ls -la /data/local/tmp/autobot-server.apk` | 文件存在，size 与 app/base.apk 一致，权限 `-rw-r--r--` |
| 2. server 启动 | `adb shell ps -A \| grep app_process` | 出现新行，UID=shell(2000)，PPID 指向 Shizuku |
| 3. Surface Parcel 传递 | logcat `ServerMain` 标签 | 看到 `handleCreateVd: surface=<android.view.Surface@...>` 且 createVirtualDisplay 返回非 null |
| 4. VD displayId | logcat `CompositionSvc` 标签 | `cachedDisplayId=N, N>0` |
| 5. AImageReader 回调 | logcat `AutoBOT-Native` + `MonitorViewModel.refreshFrameCount()` | `onImageAvailable` 触发，frameCount 增长 |
| 6. 预览画面 | UI SurfaceView | 看到虚拟显示器画面（启动 Settings 到该 displayId 观察明显变化） |
| 7. server 异常退出 | `adb shell su -c 'kill -9 <server pid>'`（或通过 Shizuku） | App 端 15s 内 PING 超时，UI 报错 |
| 8. 主动关闭 | UI 点停止按钮 | server 进程消失，`/proc/<pid>` 不存在，App `_isRunning=false` |
| 9. 横竖屏切换 | UI 切换按钮 | displayId 变化，新 VD 出现在 `dumpsys display` |
| 10. App 启动到 VD | `am start --display <id> <pkg>` 通过 Shizuku | App 进入虚拟显示器，预览实时更新 |

---

## 风险点与应对

| 风险 | 概率 | 应对 |
|---|---|---|
| R1: Surface Parcel 跨进程后 AImageReader onImageAvailable 不回调 | 中 | 原理上成立——Surface(Parcel) 共享 BufferQueue producer，App 端 AImageReader 仍是 consumer。需真机验证。若失败，备选：server 端自己创建 AImageReader，把帧数据通过 socket 回流（性能差，仅兜底） |
| R2: `/data/local/tmp/autobot-server.apk` SELinux 拒绝写入 | 低 | `/data/local/tmp` 是 `shell_data_file`，shell uid 默认可读写。`adb shell ls -Z /data/local/tmp/` 验证 context。若 ROM 强限，备选 `/data/data/com.autobot.app/server.apk` + chown shell:shell + chmod 644 |
| R3: dex 类加载失败（ClassNotFoundException: ServerMain） | 中 | minSdk=26 一般单 dex。检查 `unzip -l app-debug.apk \| grep classes` 是否只有 `classes.dex`。若多个，用 `d8 --output server.dex classes*.dex` 合并 |
| R4: server 进程的 Workarounds.apply() 在 app_process 中 NPE | 中 | app_process 默认无 ActivityThread，Workarounds 内部已设计为创建并注入新的 ActivityThread。Samsung/MIUI 在 Android 12+ 上 ConfigurationController 注入可能失败——已在 fillConfigurationController 中 try-catch 静默吞掉。若失败回退至 server 直接调 DisplayManager(FakeContext) 不调 Workarounds |
| R5: app_process 路径不同 | 低 | 标准 `/system/bin/app_process`，在 PATH 中。若某些 ROM 用 `app_process32/64`，先 `which app_process` 探测 |
| R6: socket 命名冲突 | 低 | abstract namespace "com.autobot.app.vdserver" 全局唯一。同 App 同时只允许一个 VD（与 CompositionService 现状一致）。若需多实例，加 `.<randomUUID>` 后缀 |
| R7: 未来启用 minify 时 server 类被 R8 混淆 | 中 | 当前 release isMinifyEnabled=false，无影响。proguard-rules.pro 加 keep 规则保险 |

---

## CI 影响

**当前 CI 无需任何修改**。理由：
- `build-tools;34.0.0` 已安装（提供 d8，本方案不依赖但保留）
- `app_process` 是设备端二进制，CI 不涉及
- server 类编入 app 的 classes.dex，与 `assembleDebug`/`assembleRelease` 一起完成

**可选增强**（建议但非必须）：在 "Build Debug APK" 之后加一步验证 server 类已编入 dex：
```yaml
- name: Verify server classes in APK
  run: |
    unzip -p app/build/outputs/apk/debug/app-debug.apk classes.dex | \
      strings | grep -c "com/autobot/app/server/ServerMain" || \
      (echo "ServerMain not in dex"; exit 1)
  shell: bash
```

---

## 关键文件路径（绝对路径）

新增：
- `c:\Users\Dee\Desktop\AutoBOT\app\src\main\java\com\autobot\app\server\ServerMain.kt`
- `c:\Users\Dee\Desktop\AutoBOT\app\src\main\java\com\autobot\app\server\VDProtocol.kt`
- `c:\Users\Dee\Desktop\AutoBOT\app\src\main\java\com\autobot\app\manager\ShizukuProcessManager.kt`

修改：
- `c:\Users\Dee\Desktop\AutoBOT\app\src\main\java\com\autobot\app\third\DisplayManagerHelper.kt`（简化，删除 Path A）
- `c:\Users\Dee\Desktop\AutoBOT\app\src\main\java\com\autobot\app\service\CompositionService.kt`（核心改造）
- `c:\Users\Dee\Desktop\AutoBOT\app\proguard-rules.pro`（可选 keep 规则）

参考（已有，复用）：
- `c:\Users\Dee\Desktop\AutoBOT\app\src\main\java\com\autobot\app\util\ShellExecutor.kt`（第 204-230 行已验证 Shizuku.newProcess 反射模式）
- `c:\Users\Dee\Desktop\AutoBOT\app\src\main\java\com\autobot\app\third\Workarounds.kt`（已就绪）
- `c:\Users\Dee\Desktop\AutoBOT\app\src\main\java\com\autobot\app\third\FakeContext.kt`（已就绪）

不改：
- `c:\Users\Dee\Desktop\AutoBOT\app\src\main\java\com\autobot\app\ui\tasks\MonitorViewModel.kt`（API 完全兼容）
- `c:\Users\Dee\Desktop\AutoBOT\app\src\main\java\com\autobot\app\nativelib\NativeCapturer.kt` 和 `cpp/native_capturer.cpp`（App 进程内不动）
- `c:\Users\Dee\Desktop\AutoBOT\app\src\main\AndroidManifest.xml`、`app\build.gradle.kts`、`.github\workflows\android.yml`
