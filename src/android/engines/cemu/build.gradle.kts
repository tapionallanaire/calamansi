plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val powershell = if (System.getProperty(
        "os.name"
    ).startsWith("Windows")
) {
    "powershell.exe"
} else {
    "pwsh"
}
// Keep native vcpkg IO below MAX_PATH; Git's core.longpaths alone is insufficient.
val cemuVcpkgRoot = rootProject.file("../../build/cemu").absolutePath.replace('\\', '/')
val cemuVcpkgInstallOptions = listOf(
    "--x-buildtrees-root=$cemuVcpkgRoot/b",
    "--x-packages-root=$cemuVcpkgRoot/p",
    "--downloads-root=$cemuVcpkgRoot/d",
    "--binarysource=clear",
    "--binarysource=files,$cemuVcpkgRoot/a,readwrite"
).joinToString(";")
val cemuSourceCommand = listOf(
    powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
    file("native/PrepareSource.ps1").absolutePath,
    "-Upstream", rootProject.file("../../externals/nintendo/cemu-android").absolutePath,
    "-Destination", layout.buildDirectory.dir("upstream").get().asFile.absolutePath,
    "-VcpkgBuildRoot", cemuVcpkgRoot
)

// AGP reads CMakeLists during model creation, before configure-task dependencies run.
exec { commandLine(cemuSourceCommand) }

val prepareCemuSource = tasks.register<Exec>("prepareCemuSource") {
    val recipe = layout.projectDirectory.file("native/PrepareSource.ps1")
    inputs.files(
        recipe,
        "native/calamansi.patch",
        "native/calamansi.patch.sha256",
        "native/triplets/arm64-android.cmake"
    )
    outputs.file(layout.buildDirectory.file("upstream/.calamansi-patch-hash"))
    outputs.upToDateWhen {
        false
    } // Recheck the pin/hash; the recipe reuses matching generated sources.
    commandLine(cemuSourceCommand)
}

android {
    namespace = "org.calamansi.cemu"
    compileSdk = 35
    ndkVersion = "27.3.13750724"
    defaultConfig {
        minSdk = 29 // Guard API 31 and arm64 before loading this backend's library.
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                targets += "CemuAndroid"
                arguments += listOf(
                    "-DANDROID_PLATFORM=android-31", "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCALAMANSI_NATIVE_SOURCE=${file("native").absolutePath.replace('\\', '/')}",
                    "-DENABLE_VCPKG=ON", "-DVCPKG_TARGET_ANDROID=ON",
                    "-DVCPKG_INSTALLED_DIR=$cemuVcpkgRoot/i",
                    "-DVCPKG_INSTALL_OPTIONS=$cemuVcpkgInstallOptions",
                    "-DVCPKG_OVERLAY_TRIPLETS=$cemuVcpkgRoot/t",
                    "-DENABLE_VULKAN=ON", "-DENABLE_OPENGL=OFF", "-DENABLE_SDL=OFF",
                    "-DENABLE_WXWIDGETS=OFF", "-DENABLE_BLUEZ=OFF", "-DBUNDLE_SPEEX=ON",
                    "-DENABLE_DISCORD_RPC=OFF", "-DENABLE_NSYSHID_LIBUSB=OFF",
                    "-DENABLE_WAYLAND=OFF", "-DENABLE_HIDAPI=OFF", "-DENABLE_CUBEB=ON"
                )
                if (powershell == "powershell.exe") {
                    arguments +=
                        "-DPKG_CONFIG_EXECUTABLE=$cemuVcpkgRoot/h/pkgconf-3.0.8/pkgconf.exe"
                }
            }
        }
    }
    externalNativeBuild {
        cmake {
            version = "3.25.0+"
            path = file("build/upstream/CMakeLists.txt")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(prepareCemuSource)
    }
}

dependencies {
    implementation(project(":engine-common"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.0")
}
