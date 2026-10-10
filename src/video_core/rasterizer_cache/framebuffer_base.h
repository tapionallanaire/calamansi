// Copyright 2023-2025 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

#pragma once

#include <algorithm>
#include "common/hash.h"
#include "common/math_util.h"
#include "video_core/pica/regs_rasterizer.h"
#include "video_core/rasterizer_cache/slot_id.h"
#include "video_core/rasterizer_cache/surface_base.h"
#include "video_core/rasterizer_cache/surface_params.h"

namespace VideoCore {

class SurfaceBase;

struct ViewportInfo {
    s32 x;
    s32 y;
    s32 width;
    s32 height;
};

struct FramebufferParams {
    SurfaceId color_id;
    SurfaceId depth_id;
    u32 color_level;
    u32 depth_level;
    bool shadow_rendering;
    INSERT_PADDING_BYTES(3);

    bool operator==(const FramebufferParams& params) const noexcept {
        return std::memcmp(this, &params, sizeof(FramebufferParams)) == 0;
    }

    u64 Hash() const noexcept {
        return Common::ComputeHash64(this, sizeof(FramebufferParams));
    }

    u32 Index(VideoCore::SurfaceType type) const noexcept {
        switch (type) {
        case VideoCore::SurfaceType::Color:
            return 0;
        case VideoCore::SurfaceType::Depth:
        case VideoCore::SurfaceType::DepthStencil:
            return 1;
        default:
            LOG_CRITICAL(HW_GPU, "Unknown surface type in framebuffer");
            return 0;
        }
    }
};
static_assert(std::has_unique_object_representations_v<FramebufferParams>,
              "FramebufferParams is not suitable for hashing");

template <class T>
class RasterizerCache;

/**
 * @brief FramebufferHelper is a RAII wrapper over backend specific framebuffer handle that
 * provides the viewport/scissor/draw rectanges and performs automatic rasterizer cache invalidation
 * when out of scope.
 */
template <class T>
class FramebufferHelper {
public:
    explicit FramebufferHelper(RasterizerCache<T>* res_cache_, typename T::Framebuffer* fb_,
                               bool flip_rect, const Pica::RasterizerRegs& regs,
                               Common::Rectangle<u32> surfaces_rect)
        : res_cache{res_cache_}, fb{fb_} {
        const u32 res_scale = fb->Scale();
        const u32 height = surfaces_rect.GetHeight() / res_scale;

        // Determine the draw rectangle (render area + scissor)
        Common::Rectangle viewport_rect = regs.GetViewportRect();
        if (flip_rect) {
            viewport_rect = viewport_rect.VerticalMirror(height);
        }

        draw_rect.left =
            std::clamp<s32>(static_cast<s32>(surfaces_rect.left) + viewport_rect.left * res_scale,
                            surfaces_rect.left, surfaces_rect.right);
        draw_rect.top =
            std::clamp<s32>(static_cast<s32>(surfaces_rect.bottom) + viewport_rect.top * res_scale,
                            surfaces_rect.bottom, surfaces_rect.top);
        draw_rect.right =
            std::clamp<s32>(static_cast<s32>(surfaces_rect.left) + viewport_rect.right * res_scale,
                            surfaces_rect.left, surfaces_rect.right);
        draw_rect.bottom = std::clamp<s32>(static_cast<s32>(surfaces_rect.bottom) +
                                               viewport_rect.bottom * res_scale,
                                           surfaces_rect.bottom, surfaces_rect.top);

        // Update viewport
        viewport.x = static_cast<s32>(surfaces_rect.left) + viewport_rect.left * res_scale;
        viewport.y = static_cast<s32>(surfaces_rect.bottom) + viewport_rect.bottom * res_scale;
        viewport.width = static_cast<s32>(viewport_rect.GetWidth() * res_scale);
        viewport.height = static_cast<s32>(viewport_rect.GetHeight() * res_scale);

        // Scissor checks are window-, not viewport-relative, which means that if the cached texture
        // sub-rect changes, the scissor bounds also need to be updated.
        scissor_rect.left = static_cast<s32>(surfaces_rect.left + regs.scissor_test.x1 * res_scale);
        scissor_rect.bottom =
            static_cast<s32>(surfaces_rect.bottom + regs.scissor_test.y1 * res_scale);

        // x2, y2 have +1 added to cover the entire pixel area, otherwise you might get cracks when
        // scaling or doing multisampling.
        scissor_rect.right =
            static_cast<s32>(surfaces_rect.left + (regs.scissor_test.x2 + 1) * res_scale);
        scissor_rect.top =
            static_cast<s32>(surfaces_rect.bottom + (regs.scissor_test.y2 + 1) * res_scale);

        if (flip_rect) {
            scissor_rect = scissor_rect.VerticalMirror(height);
        }

        // Pixels outside an inclusive scissor box are never written, so only invalidate the
        // intersection. Invalidating the whole viewport clobbers textures that alias the render
        // target outside the box (e.g. the blur chain in Fire Emblem Fates/Echoes).
        written_rect = draw_rect;
        if (regs.scissor_test.mode == Pica::RasterizerRegs::ScissorMode::Include && !flip_rect) {
            const auto clamp_u = [](s32 v) { return static_cast<u32>(std::max(v, 0)); };
            written_rect.left = std::max(written_rect.left, clamp_u(scissor_rect.left));
            written_rect.bottom = std::max(written_rect.bottom, clamp_u(scissor_rect.bottom));
            written_rect.right = std::min(written_rect.right, clamp_u(scissor_rect.right));
            written_rect.top = std::min(written_rect.top, clamp_u(scissor_rect.top));
            written_rect.right = std::max(written_rect.right, written_rect.left);
            written_rect.top = std::max(written_rect.top, written_rect.bottom);
        }
    }

    ~FramebufferHelper() {
        if (written_rect.GetWidth() == 0 || written_rect.GetHeight() == 0) {
            return;
        }
        const Common::Rectangle draw_rect_unscaled{written_rect / fb->Scale()};
        const auto invalidate = [&](SurfaceId surface_id, u32 level) {
            const auto& surface = res_cache->GetSurface(surface_id);
            const SurfaceInterval interval = surface.GetSubRectInterval(draw_rect_unscaled, level);
            // Don't claim regions this surface only holds via stride aliasing: draws without a
            // scissor report the whole viewport, and taking ownership there would discard the
            // real data held by the aliasing surfaces (Fire Emblem Fates/Echoes blur chain).
            SurfaceRegions written{interval};
            written -= surface.alias_regions;
            for (const auto& sub : written) {
                res_cache->InvalidateRegion(boost::icl::first(sub), boost::icl::length(sub),
                                            surface_id);
            }
        };
        if (fb->color_id) {
            invalidate(fb->color_id, fb->color_level);
        }
        if (fb->depth_id) {
            invalidate(fb->depth_id, fb->depth_level);
        }
    }

    typename T::Framebuffer* Framebuffer() const noexcept {
        return fb;
    }

    Common::Rectangle<u32> DrawRect() const noexcept {
        return draw_rect;
    }

    Common::Rectangle<s32> Scissor() const noexcept {
        return scissor_rect;
    }

    ViewportInfo Viewport() const noexcept {
        return viewport;
    }

private:
    RasterizerCache<T>* res_cache;
    typename T::Framebuffer* fb;
    Common::Rectangle<s32> scissor_rect;
    Common::Rectangle<u32> draw_rect;
    Common::Rectangle<u32> written_rect;
    ViewportInfo viewport;
};

} // namespace VideoCore

namespace std {
template <>
struct hash<VideoCore::FramebufferParams> {
    std::size_t operator()(const VideoCore::FramebufferParams& params) const noexcept {
        return params.Hash();
    }
};
} // namespace std
