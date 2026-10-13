#!/usr/bin/env bash
set -euo pipefail

export NDK_CCACHE="$(command -v ccache)"
export CMAKE_BUILD_PARALLEL_LEVEL="${CMAKE_BUILD_PARALLEL_LEVEL:-2}"

cd "$(dirname "$0")/../src/android"
chmod +x ./gradlew
./gradlew --no-daemon --no-parallel --max-workers=2 \
    --no-configuration-cache --no-build-cache \
    '-Dorg.gradle.jvmargs=-Xmx3072m -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2' \
    -Pkotlin.compiler.execution.strategy=in-process \
    -Pcalamansi.cmakeVersion=3.31.4 \
    assembleVanillaRelease assembleVanillaFatesFix

ccache -s
