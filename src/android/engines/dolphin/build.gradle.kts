// SPDX-License-Identifier: GPL-2.0-or-later
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val staged = layout.buildDirectory.dir("generated/dolphin")
val dolphinSource = rootProject.file("../../externals/nintendo/dolphin")
val prepareDolphin = tasks.register<Exec>("prepareDolphin") {
    inputs.dir(dolphinSource)
    inputs.file("prepare.py")
    inputs.file("patches/embed.patch")
    inputs.file("patches/lifecycle.patch")
    inputs.file("patches/cancel.patch")
    inputs.file("patches/isolation.patch")
    inputs.file("patches/prune.patch")
    inputs.file("patches/restore.patch")
    inputs.file("patches/library-names.patch")
    inputs.file("patches/ownership.patch")
    inputs.file("patches/controller-shutdown.patch")
    inputs.file("patches/settings-ui.patch")
    inputs.file("patches/picture-filter.patch")
    outputs.dir(staged)
    commandLine(
        providers.gradleProperty("pythonExecutable").getOrElse("python"),
        file("prepare.py"),
        dolphinSource,
        staged.get().asFile
    )
}

// AGP constructs its native model during configuration, before preBuild can generate this path.
if (!staged.get().file("native/CMakeLists.txt").asFile.isFile) {
    providers.exec {
        commandLine(
            providers.gradleProperty("pythonExecutable").getOrElse("python"),
            file("prepare.py"),
            dolphinSource,
            staged.get().asFile
        )
    }.result.get().assertNormalExitValue()
}

android {
    namespace = "org.calamansi.dolphin"
    compileSdk = 35
    ndkVersion = "27.3.13750724"
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "GIT_HASH", "\"8d2ad1a662472955497e5bb79d7b9ec6999e1f0d\"")
        buildConfigField("String", "BRANCH", "\"calamansi\"")
        buildConfigField("String", "VERSION_NAME", "\"2609-calamansi\"")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild.cmake {
            arguments(
                "-DANDROID_STL=c++_static",
                "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                "-DBUILD_SHARED_LIBS=OFF", "-DENABLE_TESTS=OFF",
                "-DENABLE_QT=OFF", "-DUSE_SYSTEM_LIBS=OFF",
                "-DDISTRIBUTOR=Calamansi", "-DCMAKE_DISABLE_FIND_PACKAGE_Git=ON",
                "-DDOLPHIN_WC_REVISION=8d2ad1a662472955497e5bb79d7b9ec6999e1f0d",
                "-DDOLPHIN_WC_DESCRIBE=2609-calamansi", "-DDOLPHIN_WC_BRANCH=calamansi"
            )
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main") {
        java.srcDir(staged.map { it.dir("java") })
        res.srcDir(staged.map { it.dir("res") })
        assets.srcDir(staged.map { it.dir("assets") })
    }
    externalNativeBuild.cmake {
        path = staged.get().file("native/CMakeLists.txt").asFile
        version = "3.25.0+"
    }
}
tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureCMake") ||
        name.startsWith("generateJsonModel") ||
        name.startsWith("buildCMake")
    ) {
        dependsOn(prepareDolphin)
    }
}

dependencies {
    implementation(project(":engine-common"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.0.1")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.slidingpanelayout:slidingpanelayout:1.2.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.leanback:leanback:1.2.0")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.nononsenseapps:filepicker:4.2.1")
}
