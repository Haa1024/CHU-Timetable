import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 起，Compose 编译器是独立插件，不再用 composeOptions.kotlinCompilerExtensionVersion
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 签名口令放 local.properties（已在 .gitignore 里），不硬编码进构建脚本
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.seu.timetable"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(
                keystoreProps.getProperty("RELEASE_STORE_FILE", "keystore/chu-timetable.jks")
            )
            storePassword = keystoreProps.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = keystoreProps.getProperty("RELEASE_KEY_ALIAS", "chu")
            keyPassword = keystoreProps.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    defaultConfig {
        // 与 SEU 版刻意不同：applicationId 相同即为同一个 App，两个版本无法共存，
        // 安装会报签名不匹配。换 applicationId 才是"能并存"的充分条件，
        // 换签名只是必要条件（否则两者互为更新源，可互相覆盖安装）。
        //
        // 发布身份是 com.chu.timetable，与下面的 namespace 不一致是**有意为之**：
        // namespace 决定 R 类包名与源码目录，动它要重排整个源码树；
        // 它不对外可见，改不改都不影响安装、更新与商店识别。
        // 将来若要做彻底的包名清理，那是独立的一次重构，不该混在移植里做。
        applicationId = "com.chu.timetable"
        minSdk = 26          // API 26 起自带 java.time，免去 desugaring
        targetSdk = 35
        versionCode = 3         // 3：新手实操引导 + 切页滑动 + 周次自动校正
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 让 App 能读到自己的 versionCode / versionName。
        // 更新功能必须知道本机版本才能比对，而这里（build.gradle.kts）是版本的唯一定义处；
        // 若在代码里另写一份常量，改版本号时就会漏改，表现为「检查更新永远说已是最新」。
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    // 显式声明：本地已从它取 LocalLifecycleOwner（做「回前台」生命周期感知）。
    // 它本来是 activity-compose 的传递依赖，运行时早已在包里，此举不增体积；
    // 写出来只为防上游哪天不再传递，导致 import 无声失效。
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 刻意不引 material-icons-extended：它带上千个图标类，debug 包体积直接翻倍。
    // 本项目只需要两个箭头，KeyboardArrowLeft/Right 在 material3 自带的
    // material-icons-core 里就有。

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 解析逻辑用真实 HAR 数据做单元测试（夹具在 src/test/resources）
    testImplementation("junit:junit:4.13.2")
}
