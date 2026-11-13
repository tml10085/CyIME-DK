import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

apply(from = "build-logic/tasks-native.gradle.kts")
apply(from = "build-logic/tasks-source-patches.gradle.kts")
apply(from = "build-logic/tasks-speech.gradle.kts")
apply(from = "build-logic/tasks-bundled-models.gradle.kts")
apply(from = "build-logic/tasks-builtin-sync.gradle.kts")
apply(from = "build-logic/tasks-plugin-dev.gradle.kts")
apply(from = "build-logic/tasks-japanese.gradle.kts")
apply(from = "build-logic/tasks-offline-translate.gradle.kts")
apply(from = "build-logic/tasks-chinese.gradle.kts")
apply(from = "build-logic/tasks-stroke.gradle.kts")
apply(from = "build-logic/tasks-cangjie.gradle.kts")
apply(from = "build-logic/tasks-t9-grammar.gradle.kts")
apply(from = "build-logic/tasks-rime-manifest.gradle.kts")

val preparePluginMarket by tasks.registering(Sync::class) {
    from(rootProject.file("store/plugins")) { into("plugin-market") }
    into(layout.buildDirectory.dir("generated/plugin-market-assets"))
}
tasks.named("preBuild").configure { dependsOn(preparePluginMarket) }

// 关于 › 更新内容：发 Release 用的 docs/<x.y.z>.md 一起打进安装包，看说明不联网。
val prepareReleaseNotes by tasks.registering(Sync::class) {
    from(rootProject.file("docs")) {
        include("*.md")
        eachFile { if (!name.matches(Regex("""\d+\.\d+\.\d+\.md"""))) exclude() }
        into("release-notes")
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("generated/release-notes-assets"))
}
tasks.named("preBuild").configure { dependsOn(prepareReleaseNotes) }

// 获取 Git 提交哈希
fun getGitHash(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootDir)
            .start()
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "unknown"
    }
}

// 获取构建时间已移除：构建时刻会写入 BuildConfig 进而进入 classes.dex，
// 破坏 F-Droid 可复现构建（不同环境构建时间不同导致产物不一致）。

// 加载签名配置
val keystorePropertiesFile = rootProject.file("app/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.kingzcheung.xime"
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/plugin-market-assets").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/release-notes-assets").get().asFile)
    if (providers.gradleProperty("bundleModels").orNull == "true") {
        sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/bundled-model-assets").get().asFile)
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/japanese-azookey-assets").get().asFile)
    // libCyAzooKey.so per ABI (tasks-japanese.gradle.kts), loaded by the Japanese Rime translator.
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/azookey-jniLibs").get().asFile)
    // libcytranslate.so (arm64-v8a, x86_64; tasks-offline-translate.gradle.kts): offline translation engine.
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/translate-jniLibs").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/chinese-assets").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/stroke-assets").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/cangjie-assets").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/rime-manifest").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/speech-assets").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/builtin-sync-assets").get().asFile)
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    // JVM 单测中未 mock 的 Android 框架方法（如 android.util.Log）返回默认值而非抛异常，
    // 使服务层状态机（如语音收尾流程）可直接实例化测试
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric 渲染测试（如候选栏两端按钮对比图）需要真实资源与 assets。
        unitTests.isIncludeAndroidResources = true
    }

    defaultConfig {
        // 安装身份（对外）与源码 namespace（内部）分离：
        // applicationId 使用 CyIME 自己的包名，namespace 保留 com.kingzcheung.xime，
        // 以便继续跟踪上游、避免大规模移动源码目录。
        applicationId = "com.cyletix.cyime"
        val sideBySide = providers.gradleProperty("sideBySide").orNull == "true"
        if (sideBySide) {
            applicationIdSuffix = ".dk"
        }
        manifestPlaceholders["appLabel"] = if (sideBySide) "CyIME DK" else "@string/app_name"
        manifestPlaceholders["imeServiceLabel"] = if (sideBySide) "CyIME DK" else "CyIME"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        // 按发布批次定号：兼容修复升 PATCH，兼容功能升 MINOR 并归零 PATCH。
        // 不兼容变更升 MAJOR；日常编辑不逐次升号。完整规则见 docs/packaging.md。
        // versionCode 是独立递增的交付序号；同批普通版与内置版共用。
        versionCode = 20261435
        versionName = "1.15.82"


        // Isolated Compose rendering on a local x86 emulator; ordinary device tests keep the full app.
        testInstrumentationRunner = if (providers.gradleProperty("uiRenderingOnly").orNull == "true")
            "com.kingzcheung.xime.ui.UiRenderingTestRunner" else "androidx.test.runner.AndroidJUnitRunner"

        // NDK 配置
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        // 构建信息
        buildConfigField("String", "GIT_HASH", "\"${getGitHash()}\"")
        buildConfigField("boolean", "BUNDLED_MODELS", (providers.gradleProperty("bundleModels").orNull == "true").toString())
        // The elder package (elder-pack-spec.md); other bundled-model builds such as the friend package keep normal behaviour.
        buildConfigField("boolean", "ELDER", (providers.gradleProperty("elderEdition").orNull == "true").toString())
        // Developer-only features (handwriting training / capture): only the packager's 装到我的设备 preset (-PdevTools=true)
        // and debug builds show them; public, elder and friend packages never do.
        buildConfigField("boolean", "DEV_TOOLS", (providers.gradleProperty("devTools").orNull == "true").toString())
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                // 优先使用 storeFile（已存在的 keystore 文件）；否则解码 keyBase64。
                // keyBase64 方式用于 CI/F-Droid 从 secrets 注入签名。
                val storeFileProp = keystoreProperties.getProperty("storeFile")?.let { file(it) }
                storeFile = if (storeFileProp != null && storeFileProp.exists()) {
                    storeFileProp
                } else {
                    keystoreProperties.getProperty("keyBase64")?.let { keyBase64 ->
                        val ks = File(layout.buildDirectory.get().asFile, "release-keystore.jks")
                        ks.writeBytes(Base64.getDecoder().decode(keyBase64.replace("\n", "").trim()))
                        ks
                    }
                }
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
            // minSdk 28 时 AGP 默认只签 v2；vivo OriginOS 安装器只认 META-INF 里的 v1（JAR）证书，
            // 缺了就报「安装包缺乏开发者证书」并拒绝安装。同一把密钥补签 v1，不影响已装用户覆盖升级。
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            // 本地有正式密钥时沿用它，debug/release 都能覆盖原应用，避免卸载丢数据。
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 只在本地有 keystore.properties 时才使用签名配置
            // GitHub Actions 使用自己的签名方式
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // 离线构建类型：initWith(release)，由 src/offline 的 manifest 叠加层移除 INTERNET 权限。
        // 产物（assembleOffline）供高敏感环境使用：系统层面禁止一切联网，模型/词库下载、
        // 扩展商店、检查更新、在线语音、样本贡献、剪贴板同步在该构建中均不可用，
        // 需配合完整版离线模型内置包。现有 assembleDebug/assembleRelease 任务不受影响。
        create("offline") {
            initWith(getByName("release"))
            // :plugin-core 等 library 模块只有 debug/release，offline 变体回落到 release。
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xunused")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    // NDK 构建配置
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 打包配置
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // tasks-native.gradle.kts extracts this same 1.28.0 runtime for C++ consumers.
            // The Java OCR binding contributes an identical copy plus onnxruntime4j_jni.
            pickFirsts.add("**/libonnxruntime.so")
            pickFirsts.add("**/libc++_shared.so")
        }
    }
    // 九键长句重排已停用（t9_pinyin.schema.yaml），两种包都不再带这个模型；源文件只留给电脑回放对照。
    // Keep vocabulary and licenses; both editions offer the large grammar separately.
    androidResources.ignoreAssetsPatterns.add("t9_sentence.onnx")
    // 拍照识别模型（约 21 MB）：轻量版用到时再下载（模型目录 ocr-ppocr-v5，国内魔搭、海外 GitHub）；
    // 带模型的完整版、老人包、朋友包照旧内置，不联网也能识别。
    if (providers.gradleProperty("bundleModels").orNull != "true")
        androidResources.ignoreAssetsPatterns.add("<dir>ppocr-v5")
    // 内置的拍照识别模型不压缩存放（只多约 3 MB 下载）：识别时直接映射安装包，手机上不再解出第二份（OcrModelFiles）。
    // 语音（SenseVoice）和九键词组模型不这样做：不压缩会让完整版下载各多约 76 MB（2026-11-02 决定保持压缩、解压一份）。
    // 声纹和手写模型保持解压：模型管理（是否装好、删除、设置向导跳过）和手写模板、训练页都按 models/ 下的文件路径读，
    // 为省 7–27 MB 改这些地方不值得。
    androidResources.noCompress.addAll(listOf("ch_PP-OCRv5_det_mobile.onnx", "ch_PP-OCRv5_rec_mobile.onnx"))
    // 拍照识别引擎 OpenCV（arm64 约 9 MB）同理：轻量版用到时和模型一起下载（模型目录 ocr-engine，ocr/OcrEngine.kt），
    // 带模型的安装包照旧内置。它要的 libc++_shared.so 留在包里。离线包（assembleOffline）也必须带 bundleModels。
    if (providers.gradleProperty("bundleModels").orNull != "true")
        packaging.jniLibs.excludes.add("**/libopencv_java4.so")
    // 日语词库（assets/azookey，约 18 MB）和转换引擎 libCyAzooKey.so（约 6 MB）：轻量版不带，装「日语」插件时下载
    // （settings/JapanesePack.kt）；带模型的安装包（完整版、朋友包、离线包）照旧内置。日语方案 rime_japanese 很小，留在包里。
    if (providers.gradleProperty("bundleModels").orNull != "true") {
        androidResources.ignoreAssetsPatterns.add("<dir>azookey")
        packaging.jniLibs.excludes.add("**/libCyAzooKey.so")
    }
    // The elder package carries no Japanese dictionaries and no letter-language (Spanish, German…) schemas (elder-pack-spec.md).
    if (providers.gradleProperty("elderEdition").orNull == "true") {
        listOf("<dir>rime_japanese", "<dir>azookey", "<dir>rime_letters").forEach { androidResources.ignoreAssetsPatterns.add(it) }
        packaging.jniLibs.excludes.add("**/libCyAzooKey.so")
    }
    ndkVersion = "29.0.14206865"

    // F-Droid 一致性验证：不在 APK 中写入依赖元数据（Dependency metadata 签名块）。
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // 测试 classpath 包含 main assets，使 T9Decoder() 无参构造可加载 pinyin_lm.bin
    sourceSets {
        getByName("test") {
            resources.srcDirs("src/main/assets")
        }
    }
    lint {
        checkReleaseBuilds = false
        checkGeneratedSources = false
        abortOnError = false
        checkDependencies = true
    }

    // 分架构打包
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

android.applicationVariants.all {
    val appName = "CyIME"
    outputs.all {
        val abi = filters.find { it.filterType.toString() == "ABI" }?.identifier ?: "universal"
        val buildLabel = if (buildType.name == "debug") "-debug" else ""
        val sideBySideLabel = if (providers.gradleProperty("sideBySide").orNull == "true") "-dk" else ""
        val edition = if (providers.gradleProperty("bundleModels").orNull == "true") "-full" else ""
        (this as BaseVariantOutputImpl).outputFileName = "$appName-$versionName$edition$sideBySideLabel$buildLabel-$abi.apk"
    }
}

// Nightly 构建通过 androidComponents API 覆盖 versionCode/versionName
androidComponents {
    onVariants { variant ->
        val vc = project.findProperty("versionCode")?.toString()?.toIntOrNull()
        val vn = project.findProperty("versionName")?.toString()
        if (vc != null && vn != null) {
            variant.outputs.forEach { output ->
                output.versionCode.set(vc)
                output.versionName.set(vn)
            }
        }
    }
}
dependencies {
    implementation(project(":plugin-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Kotlin stdlib - CRITICAL for plugin compatibility
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    implementation(libs.kotlinx.coroutines.core)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)

    // Material Icons
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // SavedState
    implementation(libs.androidx.savedstate)

    // Coil (Image Loading)
    implementation(libs.coil)

    // OkHttp for WebSocket and model download
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // okhttp-sse for SSE stream parsing (plugin streaming API)
    implementation("com.squareup.okhttp3:okhttp-sse:5.4.0")
    // Apache Commons Compress for tar.bz2 extraction
    implementation("org.apache.commons:commons-compress:1.28.0")

    // Kaml for YAML parsing
    implementation(libs.kaml)

    // Autofill inline suggestions (API 30+)
    implementation(libs.androidx.autofill)

    // exp4j for calculator expression evaluation
    implementation(libs.exp4j)

    // ZXing for QR code generation
    implementation("com.google.zxing:core:3.5.4")
    // Bundled OCR works without Play Services or a first-use model download.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    implementation("org.opencv:opencv:4.12.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")

    implementation(libs.kotlinx.serialization.json)

    // Room 3.0 (SQLite)
    implementation(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)

    // Sora Code Editor for YAML viewing/editing
    implementation(platform("io.github.rosemoe:editor-bom:0.24.6"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-textmate")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    debugImplementation(libs.androidx.compose.ui.tooling)
    // Compose 仪器测试需要一个独立宿主 Activity，仅加入 debug 变体。
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    // JVM 单测使用真实 org.json 实现（android.jar 内为抛异常的 stub）
    testImplementation("org.json:json:20240303")
    // 在电脑上用真实 Compose 代码渲染界面截图（Robolectric 原生绘制），不用模拟器。
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(libs.androidx.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.concurrent:concurrent-futures:1.2.0")
}

// Align concurrent-futures version: espresso 3.7.0 requires 1.2.0
dependencies {
    constraints {
        implementation("androidx.concurrent:concurrent-futures:1.2.0") {
            because("test dependencies (espresso 3.7.0) require 1.2.0")
        }
    }
}
