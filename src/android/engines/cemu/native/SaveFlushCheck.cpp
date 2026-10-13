// SPDX-License-Identifier: MPL-2.0
#ifdef NDEBUG
#undef NDEBUG
#endif
#include "Common/FileStream.h"
#include "Cafe/Filesystem/fscDeviceHostFS.h"
#include "Cafe/CafeSystem.h"
#include <cassert>
#include "config/ActiveSettings.h"

bool CalamansiFlushMlc();

// Run the built ARM64 executable with a writable directory argument on Android.
// This exercises the real FileStreamUnix buffering/fsync path, not a JNI mock.
int main(int argc, char** argv)
{
    assert(argc == 2);
    std::set<fs::path> failedPaths;
    const fs::path root = fs::path(argv[1]) / "cemu-save-flush-check-root";
    assert(!fs::exists(root));
    fs::create_directory(root);
    ActiveSettings::SetPaths(false, {}, root, root, root, root, failedPaths);
    assert(failedPaths.empty());
    fs::create_directories(ActiveSettings::GetMlcPath());
    const fs::path path = root / "cemu-save-flush-check.bin";
    fs::remove(path);
    auto writer = std::unique_ptr<FileStream>(FileStream::createFile2(path));
    assert(writer);
    const std::array<uint8, 7> expected{0, 1, 4, 16, 64, 128, 255};
    assert(writer->writeData(expected.data(), expected.size()) == expected.size());
    assert(writer->Flush());
    // The writer remains open: a destructor/close cannot accidentally make the
    // check pass in place of the required explicit flush.
    auto reader = std::unique_ptr<FileStream>(FileStream::openFile2(path));
    assert(reader);
    std::array<uint8, 7> actual{};
    assert(reader->readData(actual.data(), actual.size()) == actual.size());
    assert(actual == expected);
    reader.reset();
    const fs::path renamed = root / "cemu-save-flush-check-renamed.bin";
    fs::remove(renamed);
    fs::rename(path, renamed);
    assert(writer->writeData(expected.data(), expected.size()) == expected.size());
    assert(writer->Flush()); // the real handle stays valid after pathname changes
    writer->SetPosition(3);
    assert(writer->SetEndOfFile());
    assert(writer->Flush());
    writer.reset();
    reader.reset(FileStream::openFile2(renamed));
    assert(reader);
    assert(reader->GetSize() == 3);
    assert(reader->readData(actual.data(), 3) == 3);
    assert(std::equal(actual.begin(), actual.begin() + 3, expected.begin()));
    reader.reset();
    sint32 directoryStatus = FSC_STATUS_UNDEFINED;
    auto directory = std::unique_ptr<FSCVirtualFile>(FSCVirtualFile_Host::OpenFile(
        root, FSC_ACCESS_FLAG::OPEN_DIR, directoryStatus));
    assert(directory && directoryStatus == FSC_STATUS_OK);
    assert(directory->fscFlush()); // actual Android directory fsync, not a quota no-op
    assert(CalamansiFlushMlc()); // fence closed saves and nested directory metadata
    CafeSystem::Initialize();
    fs::remove(ActiveSettings::GetMlcPath());
    assert(!CalamansiFlushMlc()); // a missing volume handle must retain ownership
    bool shutdownFailed = false;
    try { CafeSystem::Shutdown(); }
    catch (const std::runtime_error&) { shutdownFailed = true; }
    assert(shutdownFailed); // MCP has joined, but the failed FSA fence retains shutdown state.
    fs::create_directories(ActiveSettings::GetMlcPath());
    assert(CalamansiFlushMlc()); // a real retry succeeds after the cause is fixed
    CafeSystem::Shutdown(); // Retry must reach FSA instead of joining MCP a second time.
    CafeSystem::Shutdown(); // Fully quiescent shutdown remains idempotent.
    fs::remove(renamed);
    directory.reset();
    fs::remove(ActiveSettings::GetMlcPath());
    fs::remove(root);
    return 0;
}
