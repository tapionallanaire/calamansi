// Copyright 2023 Citra Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":app")
include(":engine-common", ":engine-nds", ":engine-retroarch", ":engine-dolphin", ":engine-cemu")
project(":engine-common").projectDir = file("engines/common")
project(":engine-nds").projectDir = file("engines/nds")
project(":engine-retroarch").projectDir = file("engines/retroarch")
project(":engine-dolphin").projectDir = file("engines/dolphin")
project(":engine-cemu").projectDir = file("engines/cemu")
