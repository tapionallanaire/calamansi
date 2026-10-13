// SPDX-License-Identifier: MPL-2.0
#include "CalamansiLifecycle.h"
#include "JNIUtils.h"
#include "Cafe/CafeSystem.h"
#include "Cafe/Filesystem/fsc.h"
#include "Cafe/TitleList/TitleList.h"
#include "Cafe/TitleList/SaveList.h"
#include "Cafe/HW/Latte/Renderer/Vulkan/VulkanRenderer.h"
#include "Cemu/GuiSystem/GuiSystem.h"
#include "audio/IAudioAPI.h"
#include "config/CemuConfig.h"
#include "config/ActiveSettings.h"
#include <fcntl.h>
#include <unistd.h>
#include "GameTitleLoader.h"
#include "input/InputManager.h"
#include "input/api/Android/AndroidControllerProvider.h"
#include <condition_variable>
#include <chrono>
#include <exception>
#include <mutex>
namespace NativeGameTitles { extern GameTitleLoader s_gameTitleLoader; }
extern std::atomic_bool sLatteCleanupFailed;
std::atomic_bool gCalamansiGpuActive = false;

namespace {
std::mutex surfaceMutex;
std::condition_variable surfaceCondition;
bool surfacePending = false;
bool surfaceMain = true;
ANativeWindow* surfaceWindow = nullptr;
std::exception_ptr surfaceFailure;
JNIUtils::Scopedjclass runtimeClass;
jmethodID errorMethod = nullptr;
jmethodID relaunchMethod = nullptr;

void applySurface(ANativeWindow* ownedWindow, bool mainWindow)
{
    auto& info = GuiSystem::getWindowInfo();
    if (!ownedWindow) {
        auto& input = InputManager::instance();
        auto& touch = mainWindow ? input.m_main_mouse : input.m_pad_mouse;
        std::scoped_lock lock(touch.m_mutex);
        touch.left_down = touch.left_down_toggle = false;
    }
    int width = mainWindow ? info.phys_width.load() : info.phys_pad_width.load();
    int height = mainWindow ? info.phys_height.load() : info.phys_pad_height.load();
    if (g_renderer)
        static_cast<VulkanRenderer*>(g_renderer.get())->AndroidReplaceSurface(ownedWindow, {width, height}, mainWindow);
    else {
        auto& handle = mainWindow ? info.canvas_main : info.canvas_pad;
        if (handle.surface)
            ANativeWindow_release(static_cast<ANativeWindow*>(handle.surface));
        handle.surface = ownedWindow;
        if (!mainWindow)
            info.pad_open = ownedWindow != nullptr;
    }
}

void cancelPendingSurface()
{
    if (!surfacePending)
        return;
    surfaceFailure = surfaceWindow
        ? std::make_exception_ptr(std::runtime_error("Renderer stopped before surface creation"))
        : nullptr; // A stopped GPU has genuinely stopped using a released surface.
    if (surfaceWindow) ANativeWindow_release(surfaceWindow);
    surfaceWindow = nullptr;
    surfacePending = false;
    surfaceCondition.notify_all();
}
}

std::mutex& CalamansiSurfaceMutex() { return surfaceMutex; }

void CalamansiGpuFinished()
{
    std::lock_guard lock(surfaceMutex);
    gCalamansiGpuActive = false;
    cancelPendingSurface();
}

void CalamansiInitializeCallbacks()
{
    if (runtimeClass.isValid())
        return;
    JNIUtils::ScopedJNIENV env;
    runtimeClass = JNIUtils::Scopedjclass("org/calamansi/cemu/CemuRuntime");
    errorMethod = env->GetStaticMethodID(*runtimeClass, "reportNativeError", "(Ljava/lang/String;)V");
    relaunchMethod = env->GetStaticMethodID(*runtimeClass, "requestRelaunch", "(J)V");
    if (!errorMethod || !relaunchMethod)
        throw std::runtime_error("Missing Wii U lifecycle callback");
}

void CalamansiReportNativeError(const char* message)
{
    JNIUtils::ScopedJNIENV env;
    auto text = JNIUtils::toJString(env, message);
    env->CallStaticVoidMethod(*runtimeClass, errorMethod, text);
    env->DeleteLocalRef(text);
}

void CalamansiRequestRelaunch(uint64 titleId)
{
    JNIUtils::ScopedJNIENV env;
    env->CallStaticVoidMethod(*runtimeClass, relaunchMethod, static_cast<jlong>(titleId));
}

void CalamansiSetSurface(ANativeWindow* ownedWindow, bool mainWindow)
{
    std::unique_lock lock(surfaceMutex);
    // Retain ownership when a timed-out command is subsequently acknowledged.
    // GPU startup uses this same mutex so pre-boot changes cannot race it.
    if (!surfaceCondition.wait_for(lock, std::chrono::seconds(15), [] { return !surfacePending; })) {
        if (ownedWindow) ANativeWindow_release(ownedWindow);
        throw std::runtime_error("Previous Wii U surface command has not completed");
    }
    if (!gCalamansiGpuActive) {
        if (!g_renderer && ownedWindow) {
            ANativeWindow_release(ownedWindow);
            throw std::runtime_error("Wii U renderer is stopped");
        }
        applySurface(ownedWindow, mainWindow);
        return;
    }
    surfaceWindow = ownedWindow;
    surfaceMain = mainWindow;
    surfaceFailure = nullptr;
    surfacePending = true;
    if (!surfaceCondition.wait_for(lock, std::chrono::seconds(15), [] { return !surfacePending; }))
        throw std::runtime_error("Wii U renderer did not acknowledge surface release/recreation");
    if (surfaceFailure)
        std::rethrow_exception(surfaceFailure);
}

void CalamansiPollSurfaceCommands()
{
    std::lock_guard lock(surfaceMutex);
    if (!surfacePending)
        return;
    try {
        applySurface(surfaceWindow, surfaceMain);
    } catch (...) {
        surfaceFailure = std::current_exception();
    }
    surfaceWindow = nullptr;
    surfacePending = false;
    surfaceCondition.notify_all();
}

void CalamansiRecreateSurface(bool mainWindow)
{
    auto& info = GuiSystem::getWindowInfo();
    auto* window = static_cast<ANativeWindow*>((mainWindow ? info.canvas_main : info.canvas_pad).surface);
    if (!window)
        throw std::runtime_error("Cannot recreate an absent Wii U surface");
    ANativeWindow_acquire(window);
    CalamansiSetSurface(window, mainWindow);
}

bool CalamansiFlushMlc()
{
    const int fd = ::open(ActiveSettings::GetMlcPath().c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0)
        return false;
    // ponytail: one volume fence also persists already-closed saves and nested
    // rename/mkdir metadata; track dirty MLC directories if this becomes costly.
    const bool flushed = ::syncfs(fd) == 0;
    const bool closed = ::close(fd) == 0;
    return flushed && closed;
}

extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_safeStop(JNIEnv* env, jclass)
{
    JNIUtils::handleNativeException(env, [] {
        // ShutdownTitle joins guest CPU and GPU first, then posts a FIFO FSA
        // barrier which flushes and closes handles before any mount/memory reset.
        CafeTitleList::JoinWorker();
        CafeSaveList::JoinWorker();
        NativeGameTitles::s_gameTitleLoader.Stop();
        auto provider = InputManager::instance().get_api_provider(InputAPI::Android);
        if (auto* androidProvider = dynamic_cast<AndroidControllerProvider*>(provider.get()))
            androidProvider->release_all_inputs();
        CafeSystem::ShutdownTitle();
        CafeSystem::UnsetOverrideArgs();
        InputManager::instance().StopUpdateThread();
        if (sLatteCleanupFailed)
            throw std::runtime_error("Wii U renderer cleanup failed; session cannot safely end");
        CafeSystem::Shutdown();
        {
            std::unique_lock lock(g_audioMutex);
            g_tvAudio.reset();
            g_padAudio.reset();
        }
        std::lock_guard lock(surfaceMutex);
        cancelPendingSurface();
        applySurface(nullptr, false);
        applySurface(nullptr, true);
        g_renderer.reset(); // covers renderer initialized before a failed title boot
        if (!g_config.Save())
            throw std::runtime_error("Failed to save Wii U settings");
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_getForegroundTitleId(JNIEnv*, jclass)
{
    return CafeSystem::IsTitleRunning() ? static_cast<jlong>(CafeSystem::GetForegroundTitleId()) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_info_cemu_cemu_nativeinterface_NativeEmulation_relaunchTitle(JNIEnv* env, jclass, jlong titleId)
{
    JNIUtils::handleNativeException(env, [&] {
        CafeSystem::ShutdownTitle();
        if (sLatteCleanupFailed)
            throw std::runtime_error("Cannot relaunch after failed renderer cleanup");
        if (CafeSystem::PrepareForegroundTitle(static_cast<uint64>(titleId)) != CafeSystem::PREPARE_STATUS_CODE::SUCCESS)
            throw std::runtime_error("Failed to prepare guest-requested Wii U title");
        // ShutdownTitle destroyed the previous renderer, not Android's retained
        // ANativeWindow handles. Rebuild against those real TV/GamePad surfaces.
        auto& info = GuiSystem::getWindowInfo();
        if (!info.canvas_main.surface)
            throw std::runtime_error("Wii U TV surface is unavailable for relaunch");
        info.window_main.surface = info.canvas_main.surface;
        try { g_renderer = std::make_unique<VulkanRenderer>(); }
        catch (...) { info.window_main.surface = nullptr; throw; }
        info.window_main.surface = nullptr;
        CalamansiRecreateSurface(true);
        if (info.canvas_pad.surface)
            CalamansiRecreateSurface(false);
        CafeSystem::LaunchForegroundTitle();
    });
}
