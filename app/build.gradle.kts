import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autobot.app"
    compileSdk = 34
    // NDK 版本：mediandk 的 AImageReader 在 API 26 引入，minSdk 26 才能用全部特性
    // 为兼容 minSdk 24，运行时做版本判断；NDK r25c 自带 mediandk 头文件
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.autobot.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.03"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NDK / CMake 配置：虚拟显示器捕获需要 mediandk (AImageReader)
        externalNativeBuild {
            cmake {
                // C++ 标准与异常支持
                cppFlags("-std=c++17", "-fexceptions", "-frtti")
                // 过滤 ABI：模拟器常用 x86_64，真机 arm64-v8a
                abiFilters("arm64-v8a", "x86_64")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    // 固定签名配置：从 keystore.properties 读取
    // Shizuku 授权基于 APK 签名，签名一致才能保证授权不随 CI 构建变化而失效
    // 若 keystore.properties 不存在（未配置 Secrets），则不设置签名配置，回退到默认 debug 签名
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }
    val hasKeystore = keystoreProperties.getProperty("storeFile", "").isNotBlank()

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Debug 独立 applicationId，可与 release 同时安装
            applicationIdSuffix = ".debug"
            // Debug 也用固定签名，保证 Shizuku 授权不随构建变化
            if (hasKeystore) {
                signingConfig = signingConfigs.findByName("release")
            }
        }
        release {
            // CPU/存储优化：开启 R8 代码压缩 + 资源压缩
            // proguard-rules.pro 已 keep 所有反射类（Shizuku/Server/Native/Workarounds），不会破坏运行时
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystore) {
                signingConfig = signingConfigs.findByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        // 启用 Jetpack Compose
        compose = true
    }
    // Compose Compiler 版本需与 Kotlin 1.9.20 匹配
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    // CMake 构建脚本路径
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // 避免 C++ shared lib 重复打包
    packagingOptions {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    // APK 输出文件名：AutoBOT-debug.apk / AutoBOT-release.apk
    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            output.outputFileName = "AutoBOT-${variant.name}.apk"
        }
    }
}

dependencies {
    // AndroidX 核心库
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // 底部导航（实际上并未使用 NavHost，仅做依赖保留，后续可接入）
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // 生命周期
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Shizuku 权限库
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Jetpack Compose（使用 BOM 统一版本管理，与 Kotlin 1.9.20 / Compose Compiler 1.5.4 兼容）
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    // Material Icons Extended：提供 RadioButtonChecked/Unchecked、Image、Add、Delete 等图标
    // core 包不含这些图标，CI 编译会报 unresolved reference
    implementation("androidx.compose.material:material-icons-extended")
    // Compose 与 Fragment/Activity 集成
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    // Compose 中复用 AndroidView 包裹 SurfaceView
    implementation("androidx.compose.ui:ui-viewbinding")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // OpenCV4Android：模板匹配、图像处理（4.9.0 起官方发布到 Maven Central，无需 NDK）
    implementation("org.opencv:opencv:4.9.0")

    // ML Kit Text Recognition：端侧 OCR，支持中文（离线，免费）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
