// SPDX-License-Identifier: MPL-2.0
#pragma once
#include <android/native_window.h>
#include <atomic>
#include <mutex>
extern std::atomic_bool gCalamansiGpuActive;
std::mutex& CalamansiSurfaceMutex();
void CalamansiGpuFinished();
void CalamansiSetSurface(ANativeWindow* ownedWindow, bool mainWindow);
void CalamansiRecreateSurface(bool mainWindow);
void CalamansiPollSurfaceCommands();
void CalamansiInitializeCallbacks();
void CalamansiReportNativeError(const char* message);
