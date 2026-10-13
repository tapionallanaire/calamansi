import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.calamansi.retroarch"
    compileSdk = 35
    ndkVersion = "27.3.13750724"
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("boolean", "PLAY_STORE_BUILD", "false")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("bundled-jni"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("license-assets"))
}

data class CoreSource(val directory: String, val pin: String, val recipe: String, val name: String)
val cores = listOf(
    CoreSource(
        "gambatte-libretro",
        "d9d6cd06382d1ced30de34d56d3609452323dab1",
        "libgambatte/libretro/jni/Android.mk",
        "gambatte"
    ),
    CoreSource(
        "snes9x",
        "fae2fea08f74180759ef540ee94259213f503480",
        "libretro/jni/Android.mk",
        "snes9x"
    ),
    CoreSource(
        "libretro-fceumm",
        "ac0d00a7fc01166f84c7bac01d1084ebfd2b865e",
        "jni/Android.mk",
        "fceumm"
    )
)
val sources = rootProject.file("../../externals/nintendo")
val retroarchSource = sources.resolve("retroarch")
val stagedRetroarch = layout.buildDirectory.dir("native-retroarch")
val packagedLibraries = layout.buildDirectory.dir("bundled-jni")
val patch = file("native/calamansi.patch")

val packageCoreLicenses by tasks.registering(Sync::class) {
    dependsOn("checkRetroarchPins")
    into(layout.buildDirectory.dir("license-assets"))
    for (directory in listOf("retroarch", "gambatte-libretro", "mgba", "snes9x", "libretro-fceumm")) {
        from(sources.resolve(directory)) {
            include(
                "COPYING", "Copying", "LICENSE", "LICENSE.*", "AUTHORS*", "Authors",
                "jma/license.txt", "filter/*license.txt"
            )
            into("licenses/$directory")
        }
    }
}

fun requirePin(directory: File, pin: String) {
    val process = ProcessBuilder("git", "-C", directory.absolutePath, "rev-parse", "HEAD")
        .redirectErrorStream(true).start()
    val revision = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0 && revision == pin) {
        "Expected ${directory.name} at $pin; found $revision. " +
            "Initialize the pinned Nintendo gitlinks."
    }
}

val checkRetroarchPins by tasks.registering {
    doLast {
        requirePin(retroarchSource, "69a4f0ea1e8aaf442ae4858f2e7f2b31a1776576")
        cores.forEach { requirePin(sources.resolve(it.directory), it.pin) }
        requirePin(sources.resolve("mgba"), "7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6")
        val hash = MessageDigest.getInstance("SHA-256").digest(patch.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(hash == "330b7cfee4ceebde31cafec3ddefce4bd8e127a3e584471474d7812cee78da34") {
            "RetroArch integration patch hash mismatch"
        }
    }
}

val prepareRetroarch by tasks.registering(Sync::class) {
    dependsOn(checkRetroarchPins)
    from(retroarchSource) { exclude(".git", ".git/**", "pkg/apple/**") }
    into(stagedRetroarch)
    inputs.file(patch)
    doLast {
        exec {
            workingDir(stagedRetroarch.get().asFile)
            commandLine("git", "apply", "--unsafe-paths", patch.absolutePath)
        }
        // Rewrite UI prose, not identifiers, URLs, filenames or legal attribution.
        val message = Regex(
            """MSG_HASH\(\s*(?:/\*[\s\S]*?\*/\s*)*(MENU_ENUM_(?:LABEL_VALUE|SUBLABEL|LABEL_HELP)_[A-Z0-9_]+|MENU_ENUM_LABEL_CHEAT_HANDLER_TYPE_RETRO|MSG_[A-Z0-9_]+)\s*,\s*((?:"(?:\\.|[^"\\])*"\s*)+)\)"""
        )
        val product = Regex("""(?:(?<![\w./:-])|(?<=(?<!\\)\\[nrt]))(?:libretro/)?RetroArch(?![\w/-]|\.\w|:/)""")
        val interfaceName = Regex("""(?:(?<![\w./:-])|(?<=(?<!\\)\\[nrt]))(?:Libretro|libretro)(?![\w/-]|\.\w|:/)""")
        fun rewriteProse(id: String, value: String): String {
            if (listOf("LICENSE", "COPYRIGHT", "CREDITS").any { it in id }) return value
            val prose = product.replace(value, "Classic consoles")
            return if (listOf("ERROR", "FAILED", "WARNING", "FALLBACK").any { it in id }) {
                prose
            } else {
                interfaceName.replace(prose, "console engine")
            }
        }
        fun rewriteHeader(text: String): String = message.replace(text) { match ->
            match.value.replaceRange(
                match.groups[2]!!.range.first - match.range.first,
                match.groups[2]!!.range.last - match.range.first + 1,
                rewriteProse(match.groupValues[1], match.groupValues[2])
            )
        }
        val probe = rewriteHeader(
            """MSG_HASH( /* frontend */ MSG_ERROR, "RetroArch.\nRetroArch: libretro/RetroArch RetroArch.cfg https://RetroArch.org https://example.org/RetroArch %s")"""
        )
        val attribution = """MSG_HASH(MSG_COPYRIGHT, "RetroArch")"""
        val protectedTokens = listOf("MSG_ERROR", "RetroArch.cfg", "https://RetroArch.org", "https://example.org/RetroArch", "%s")
        check(!protectedTokens.fold(probe) { text, token -> text.replace(token, "") }.contains("RetroArch") &&
            protectedTokens.all { it in probe } && rewriteHeader(attribution) == attribution) {
            "Console wording must parse comments, escapes and punctuation while preserving identifiers, paths, URLs, arguments and attribution"
        }
        stagedRetroarch.get().asFile.resolve("intl").listFiles()
            ?.filter { it.name.startsWith("msg_hash_") && it.extension == "h" }
            ?.forEach { header ->
                header.writeText(rewriteHeader(header.readText()))
            }
    }
}

val buildBundledRetroarch by tasks.registering {
    dependsOn(prepareRetroarch)
    inputs.dir(stagedRetroarch)
    cores.forEach { inputs.dir(sources.resolve(it.directory)) }
    inputs.dir(sources.resolve("mgba"))
    inputs.file("native/Application.mk")
    outputs.dir(packagedLibraries)
    doLast {
        val sdk = android.sdkDirectory
        val ndk = sdk.resolve("ndk/${android.ndkVersion}")
        val windows = System.getProperty("os.name").startsWith("Windows")
        val ndkBuild = ndk.resolve(if (windows) "ndk-build.cmd" else "ndk-build")
        val cmakeVersion = providers.gradleProperty("calamansi.cmakeVersion").getOrElse("3.31.6")
        val cmake = sdk.resolve("cmake/$cmakeVersion/bin/${if (windows) "cmake.exe" else "cmake"}")
        val ninja = sdk.resolve("cmake/$cmakeVersion/bin/${if (windows) "ninja.exe" else "ninja"}")
        check(ndkBuild.isFile && cmake.isFile && ninja.isFile) {
            "Install NDK ${android.ndkVersion} and SDK CMake $cmakeVersion (>=3.25.0)."
        }
        val mgba = sources.resolve("mgba")
        val output = packagedLibraries.get().asFile
        output.deleteRecursively()
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val abiOutput = output.resolve(abi).apply { mkdirs() }
            fun buildNdk(directory: File, recipe: String, label: String) {
                val buildScript = directory.resolve(recipe)
                val recipeDirectory = buildScript.parentFile
                val objectDir = layout.buildDirectory.dir("ndk/$abi/$label").get().asFile
                val libraries = layout.buildDirectory.dir("ndk-libs/$abi/$label").get().asFile
                val applicationMk = file("native/Application.mk").invariantSeparatorsPath
                exec {
                    // Windows Make's stat buffer is only 260 bytes; avoid absolute source/object paths.
                    workingDir(recipeDirectory)
                    commandLine(
                        ndkBuild.absolutePath, "NDK_PROJECT_PATH=null",
                        "APP_BUILD_SCRIPT=${buildScript.name}",
                        "NDK_APPLICATION_MK=$applicationMk",
                        "APP_ABI=$abi",
                        "NDK_OUT=${objectDir.relativeTo(recipeDirectory).invariantSeparatorsPath}",
                        "NDK_LIBS_OUT=${libraries.relativeTo(
                            recipeDirectory
                        ).invariantSeparatorsPath}",
                        "GIT_VERSION=calamansi", "-j2"
                    )
                }
                val filename = if (label ==
                    "retroarch-activity"
                ) {
                    "libretroarch-activity.so"
                } else {
                    "libretro.so"
                }
                val built = libraries.resolve("$abi/$filename")
                check(built.isFile) { "Missing native output $built" }
                built.copyTo(
                    abiOutput.resolve(
                        if (label ==
                            "retroarch-activity"
                        ) {
                            filename
                        } else {
                            "lib${label}_libretro_android.so"
                        }
                    ),
                    overwrite = true
                )
            }
            buildNdk(
                stagedRetroarch.get().asFile,
                "pkg/android/phoenix-common/jni/Android.mk",
                "retroarch-activity"
            )
            cores.forEach { buildNdk(sources.resolve(it.directory), it.recipe, it.name) }
            val mgbaBuild = layout.buildDirectory.dir("mgba/$abi").get().asFile
            exec {
                commandLine(
                    cmake.absolutePath, "-S", mgba.absolutePath, "-B", mgbaBuild.absolutePath,
                    "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}",
                    "-DCMAKE_TOOLCHAIN_FILE=${ndk.resolve(
                        "build/cmake/android.toolchain.cmake"
                    ).absolutePath}",
                    "-DANDROID_ABI=$abi",
                    "-DANDROID_PLATFORM=android-29", "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DCMAKE_BUILD_TYPE=Release",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    "-DBUILD_LIBRETRO=ON", "-DBUILD_QT=OFF", "-DBUILD_SDL=OFF",
                    "-DBUILD_SHARED=OFF", "-DBUILD_STATIC=OFF", "-DSKIP_LIBRARY=ON",
                    "-DDISABLE_DEPS=ON", "-DENABLE_SCRIPTING=OFF",
                    "-DENABLE_DEBUGGERS=OFF", "-DENABLE_GDB_STUB=OFF",
                    "-DM_CORE_GB=ON", "-DM_CORE_GBA=ON"
                )
            }
            exec {
                commandLine(
                    cmake.absolutePath,
                    "--build",
                    mgbaBuild.absolutePath,
                    "--target",
                    "mgba_libretro",
                    "--parallel",
                    "2"
                )
            }
            mgbaBuild.resolve(
                "mgba_libretro.so"
            ).copyTo(abiOutput.resolve("libmgba_libretro_android.so"), overwrite = true)
        }
    }
}

tasks.named("preBuild") { dependsOn(buildBundledRetroarch, packageCoreLicenses) }
tasks.configureEach {
    if (name.contains("JniLibFolders") ||
        name.contains("NativeLibs")
    ) {
        dependsOn(buildBundledRetroarch)
    }
}

dependencies {
    implementation(project(":engine-common"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    testImplementation("junit:junit:4.13.2")
}
