set(VCPKG_TARGET_ARCHITECTURE arm64)
set(VCPKG_CMAKE_SYSTEM_NAME Android)
set(VCPKG_CMAKE_SYSTEM_VERSION 31)
set(VCPKG_CRT_LINKAGE static)
set(VCPKG_LIBRARY_LINKAGE static)
# Preserve the upstream triplet's NDK ABI and autotools cross-compilation identity.
set(VCPKG_MAKE_BUILD_TRIPLET "--host=aarch64-linux-android")
set(VCPKG_CMAKE_CONFIGURE_OPTIONS
    -DANDROID_ABI=arm64-v8a
    -DANDROID_STL=c++_static
    -DANDROID_PLATFORM=android-31
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)
