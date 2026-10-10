// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

#include <catch2/catch_test_macros.hpp>
#include "video_core/pica/regs_texturing.h"
#include "video_core/pica_types.h"

TEST_CASE("float24", "[video_core]") {
    REQUIRE(Pica::f24::FromRaw(0x3F0000).ToFloat32() == +1.0f);
    REQUIRE(Pica::f24::FromRaw(0xBF0000).ToFloat32() == -1.0f);
    REQUIRE(Pica::f24::FromRaw(0x3E0000).ToFloat32() == +0.5f);
    REQUIRE(Pica::f24::FromRaw(0xBE0000).ToFloat32() == -0.5f);
    REQUIRE(Pica::f24::FromRaw(0x3D0000).ToFloat32() == +0.25f);
    REQUIRE(Pica::f24::FromRaw(0xBD0000).ToFloat32() == -0.25f);
    REQUIRE(Pica::f24::FromRaw(0x408000).ToFloat32() == +3.0f);

    // Min/Max norm
    REQUIRE(Pica::f24::FromRaw(0x010000).ToFloat32() == std::bit_cast<float>(0x20800000));
    REQUIRE(Pica::f24::FromRaw(0x7EFFFF).ToFloat32() == std::bit_cast<float>(0x5F7FFF80));

    // Zero
    REQUIRE(Pica::f16::FromRaw(0x000000).ToFloat32() == +0.0f);
    REQUIRE(Pica::f24::FromRaw(0x800000).ToFloat32() == -0.0f);

    // Infinity
    REQUIRE(Pica::f24::FromRaw(0x7F0000).ToFloat32() == std::bit_cast<float>(0x7F800000));
    REQUIRE(Pica::f24::FromRaw(0xFF0000).ToFloat32() == std::bit_cast<float>(0xFF800000));

    // Denormal/Subnormal
    REQUIRE(Pica::f24::FromRaw(0x000001).ToFloat32() == std::bit_cast<float>(0x18800000));
    REQUIRE(Pica::f24::FromRaw(0x00FFFF).ToFloat32() == std::bit_cast<float>(0x207FFF00));

    // NaN
    REQUIRE(std::bit_cast<u32>(Pica::f24::FromRaw(0x7F8000).ToFloat32()) == 0x7FC00000);
    REQUIRE(std::bit_cast<u32>(Pica::f24::FromRaw(0xFF8000).ToFloat32()) == 0xFFC00000);
}

TEST_CASE("float20", "[video_core]") {
    REQUIRE(Pica::f20::FromRaw(0x3f000).ToFloat32() == +1.0f);
    REQUIRE(Pica::f20::FromRaw(0xBf000).ToFloat32() == -1.0f);
    REQUIRE(Pica::f20::FromRaw(0x3E000).ToFloat32() == +0.5f);
    REQUIRE(Pica::f20::FromRaw(0xBE000).ToFloat32() == -0.5f);
    REQUIRE(Pica::f20::FromRaw(0x3D000).ToFloat32() == +0.25f);
    REQUIRE(Pica::f20::FromRaw(0xBD000).ToFloat32() == -0.25f);
    REQUIRE(Pica::f20::FromRaw(0x40800).ToFloat32() == +3.0f);

    // Min/Max norm
    REQUIRE(Pica::f20::FromRaw(0x01000).ToFloat32() == std::bit_cast<float>(0x20800000));
    REQUIRE(Pica::f20::FromRaw(0x7EFFF).ToFloat32() == std::bit_cast<float>(0x5F7FF800));

    // Zero
    REQUIRE(Pica::f20::FromRaw(0x00000).ToFloat32() == +0.0f);
    REQUIRE(Pica::f20::FromRaw(0x80000).ToFloat32() == -0.0f);

    // Infinity
    REQUIRE(Pica::f20::FromRaw(0x7F000).ToFloat32() == std::bit_cast<float>(0x7F800000));
    REQUIRE(Pica::f20::FromRaw(0xFF000).ToFloat32() == std::bit_cast<float>(0xFF800000));

    // Denormal/Subnormal
    REQUIRE(Pica::f20::FromRaw(0x00001).ToFloat32() == std::bit_cast<float>(0x1A800000));
    REQUIRE(Pica::f20::FromRaw(0x00FFF).ToFloat32() == std::bit_cast<float>(0x207FF000));

    // NaN
    REQUIRE(std::bit_cast<u32>(Pica::f20::FromRaw(0x7F800)) == 0x7FC00000);
    REQUIRE(std::bit_cast<u32>(Pica::f20::FromRaw(0xFFFFF)) == 0xFFFFF800);
}

TEST_CASE("float16", "[video_core]") {
    REQUIRE(Pica::f16::FromRaw(0x3C00).ToFloat32() == +1.0f);
    REQUIRE(Pica::f16::FromRaw(0xBC00).ToFloat32() == -1.0f);
    REQUIRE(Pica::f16::FromRaw(0x3800).ToFloat32() == +0.5f);
    REQUIRE(Pica::f16::FromRaw(0xB800).ToFloat32() == -0.5f);
    REQUIRE(Pica::f16::FromRaw(0x3400).ToFloat32() == +0.25f);
    REQUIRE(Pica::f16::FromRaw(0xB400).ToFloat32() == -0.25f);
    REQUIRE(Pica::f16::FromRaw(0x4200).ToFloat32() == +3.0f);

    // Min/Max norm
    REQUIRE(Pica::f16::FromRaw(0x0400).ToFloat32() == std::bit_cast<float>(0x38800000));
    REQUIRE(Pica::f16::FromRaw(0x7BFF).ToFloat32() == std::bit_cast<float>(0x477FE000));

    // Zero
    REQUIRE(Pica::f16::FromRaw(0x0000).ToFloat32() == +0.0f);
    REQUIRE(Pica::f16::FromRaw(0x8000).ToFloat32() == -0.0f);

    // Infinity
    REQUIRE(Pica::f16::FromRaw(0x7C00).ToFloat32() == std::bit_cast<float>(0x7F800000));
    REQUIRE(Pica::f16::FromRaw(0xFC00).ToFloat32() == std::bit_cast<float>(0xFF800000));

    // Denormal/Subnormal
    REQUIRE(Pica::f16::FromRaw(0x0001).ToFloat32() == std::bit_cast<float>(0x33800000));
    REQUIRE(Pica::f16::FromRaw(0x03FF).ToFloat32() == std::bit_cast<float>(0x387FC000));

    // NaN
    REQUIRE(std::bit_cast<u32>(Pica::f16::FromRaw(0x7E00)) == 0x7FC00000);
    REQUIRE(std::bit_cast<u32>(Pica::f16::FromRaw(0xFFFF)) == 0xFFFFE000);
}

TEST_CASE("Post-processing ignores unused TEV texture bindings", "[video_core][postfx]") {
    using Stage = Pica::TexturingRegs::TevStageConfig;
    Pica::TexturingRegs regs{};
    for (auto* stage : {&regs.tev_stage0, &regs.tev_stage1, &regs.tev_stage2,
                        &regs.tev_stage3, &regs.tev_stage4, &regs.tev_stage5}) {
        stage->color_source1.Assign(Stage::Source::Previous);
        stage->alpha_source1.Assign(Stage::Source::Previous);
    }
    regs.tev_stage0.color_source1.Assign(Stage::Source::Texture0);
    regs.tev_stage0.color_source2.Assign(Stage::Source::Texture1);
    REQUIRE(regs.GetTevTextureMask() == 1); // Replace does not read its second input.
    regs.tev_stage1.color_op.Assign(Stage::Operation::Add);
    regs.tev_stage1.color_source2.Assign(Stage::Source::Texture1);
    regs.tev_stage1.color_source3.Assign(Stage::Source::Texture2);
    REQUIRE(regs.GetTevTextureMask() == 3); // Add does not read its third input.
    regs.tev_stage2.color_source1.Assign(Stage::Source::Texture2);
    REQUIRE(regs.GetTevTextureMask() == 4); // A later Replace discards earlier samples.
    regs.tev_stage2.color_source1.Assign(Stage::Source::Previous);
    regs.tev_stage2.color_modifier1.Assign(Stage::ColorModifier::SourceAlpha);
    REQUIRE(regs.GetTevTextureMask() == 0); // RGB and alpha have distinct dependencies.
    regs.tev_stage1.alpha_source1.Assign(Stage::Source::Texture1);
    REQUIRE(regs.GetTevTextureMask() == 2);
    regs.tev_stage2.color_modifier1.Assign(Stage::ColorModifier::SourceColor);
    regs.tev_stage2.color_source1.Assign(Stage::Source::PreviousBuffer);
    regs.tev_combiner_buffer_input.update_mask_rgb.Assign(1);
    REQUIRE(regs.GetTevTextureMask() == 1); // The buffer exposes stage 0, not stage 1.
}