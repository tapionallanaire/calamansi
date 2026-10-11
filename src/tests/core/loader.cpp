// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <string>
#include <vector>
#include <catch2/catch_test_macros.hpp>
#include "common/file_util.h"
#include "core/file_sys/ncch_container.h"
#include "core/loader/loader.h"

namespace {

struct LoaderFiles {
    LoaderFiles() {
#ifndef ANDROID
        directory = std::filesystem::temp_directory_path() /
                    ("azahar-loader-" +
                     std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
        REQUIRE(std::filesystem::create_directory(directory));
#endif
    }

    ~LoaderFiles() {
#ifdef ANDROID
        for (auto* file : descriptors) {
            std::fclose(file);
        }
#else
        std::error_code error;
        std::filesystem::remove_all(directory, error);
#endif
    }

    std::string Write(const std::string& filename, const std::vector<u8>& bytes) {
#ifdef ANDROID
        // Exercise real native IO without a JVM; content detection handles descriptor paths.
        (void)filename;
        auto* file = std::tmpfile();
        REQUIRE(file);
        descriptors.push_back(file);
        REQUIRE(std::fwrite(bytes.data(), 1, bytes.size(), file) == bytes.size());
        REQUIRE(std::fflush(file) == 0);
        return "fd://" + std::to_string(fileno(file));
#else
        const auto path = (directory / filename).string();
        FileUtil::IOFile file(path, "wb");
        REQUIRE(file.IsOpen());
        REQUIRE(file.WriteBytes(bytes.data(), bytes.size()) == bytes.size());
        REQUIRE(file.Close());
        return path;
#endif
    }

#ifdef ANDROID
    std::vector<std::FILE*> descriptors;
#else
    std::filesystem::path directory;
#endif
};

std::vector<u8> Cartridge(bool encrypted = false, bool corrupt = false) {
    NCSD_Header ncsd{};
    ncsd.magic = FileUtil::MakeMagic('N', 'C', 'S', 'D');
    ncsd.media_size = 4;
    ncsd.partitions[0] = {1, 3};

    NCCH_Header ncch{};
    ncch.magic = corrupt ? 0 : FileUtil::MakeMagic('N', 'C', 'C', 'H');
    ncch.content_size = 3;
    ncch.program_id = 0x0004000000123D00;
    ncch.no_crypto.Assign(!encrypted);
    ncch.is_executable.Assign(1);
    ncch.exefs_offset = 1;
    ncch.exefs_size = 2;

    ExeFs_Header exefs{};
    std::memcpy(exefs.section[0].name, ".code", 5);
    exefs.section[0].size = 4;
    const std::array<u8, 4> code{0x1E, 0xFF, 0x2F, 0xE1};
    std::vector<u8> bytes(4 * 0x200);
    std::memcpy(bytes.data(), &ncsd, sizeof(ncsd));
    std::memcpy(bytes.data() + 0x200, &ncch, sizeof(ncch));
    std::memcpy(bytes.data() + 0x400, &exefs, sizeof(exefs));
    std::memcpy(bytes.data() + 0x600, code.data(), code.size());
    return bytes;
}

} // namespace

TEST_CASE("Decrypted NCSD loads by content with either cartridge extension", "[loader]") {
    LoaderFiles files;
    for (const auto* extension : {".cci", ".CCI", ".3ds", ".3DS", ".3Ds", ".bin"}) {
        const std::string suffix(extension);
        INFO(suffix);
        const auto path = files.Write("cartridge" + suffix, Cartridge());
        REQUIRE(Loader::IdentifyFile(path) == Loader::FileType::CCI);
        auto loader = Loader::GetLoader(path);
        REQUIRE(loader);
        REQUIRE(loader->GetFileType() == Loader::FileType::CCI);
        u64 program_id{};
        REQUIRE(loader->ReadProgramId(program_id) == Loader::ResultStatus::Success);
        REQUIRE(program_id == 0x0004000000123D00);
#ifndef ANDROID
        std::vector<u8> code;
        REQUIRE(loader->ReadCode(code) == Loader::ResultStatus::Success);
        REQUIRE(code == std::vector<u8>{0x1E, 0xFF, 0x2F, 0xE1});
#endif
        if (suffix != ".bin") {
            REQUIRE(Loader::GuessFromExtension(suffix) == Loader::FileType::CCI);
        }
    }
}

TEST_CASE("Cartridge extensions do not bypass encryption or corrupt NCCH errors", "[loader]") {
    LoaderFiles files;
    for (const auto* extension : {".cci", ".3ds", ".3DS"}) {
        INFO(extension);
        for (bool encrypted : {false, true}) {
            const auto path =
                files.Write(std::string("invalid") + extension, Cartridge(encrypted, !encrypted));
            auto loader = Loader::GetLoader(path);
            REQUIRE(loader);
            u64 program_id{};
            const auto expected = encrypted ? Loader::ResultStatus::ErrorEncrypted
                                            : Loader::ResultStatus::ErrorInvalidFormat;
            REQUIRE(loader->ReadProgramId(program_id) == expected);
            std::vector<u8> code;
            REQUIRE(loader->ReadCode(code) == expected);
        }
    }
}

TEST_CASE("3DSX content still takes precedence over a cartridge extension", "[loader]") {
    LoaderFiles files;
    std::vector<u8> bytes(0x200);
    std::memcpy(bytes.data(), "3DSX", 4);
    const auto path = files.Write("homebrew.3ds", bytes);
    REQUIRE(Loader::IdentifyFile(path) == Loader::FileType::THREEDSX);
    auto loader = Loader::GetLoader(path);
    REQUIRE(loader);
    REQUIRE(loader->GetFileType() == Loader::FileType::THREEDSX);
    REQUIRE(Loader::GuessFromExtension(".3dsx") == Loader::FileType::THREEDSX);
    REQUIRE(Loader::GuessFromExtension(".3DSX") == Loader::FileType::THREEDSX);
}
