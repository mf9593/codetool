plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.codetool.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codetool.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
        // 只打 arm64（tawcroot 和 rootfs 都是 aarch64）
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 不压缩 .so 和 .xz（rootfs），否则 jniLibs/assets 提取会出问题
    androidResources {
        noCompress += listOf("so", "xz")
    }

    // libtawcroot.so 是 ET_EXEC 可执行文件（伪装成 .so），必须解压到文件系统才能执行
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 纯 Java XZ 解压（App 内无 xz 可执行文件，toybox tar -J 需要外部 xz）
    implementation("org.tukaani:xz:1.10")
    // Material Design（BottomNav、Toolbar 等）
    implementation("com.google.android.material:material:1.12.0")

    // sora-editor：成熟的 Android 代码编辑器（内置行号、语法高亮、大文件流畅）。
    // 替换手搓的 LineNumberEditText（行号 desync / 滑动卡顿修不干净）。
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")

    // Termux 终端视图（源码集成，无 proot，纯终端 UI + emulator）
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))
}
