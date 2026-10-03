/*
 * This file is part of LiquidBounce (https://github.com/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.utils.render

import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.textures.GpuFormat
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.gpuDevice

/**
 * Java-callable bridge for XRay's lightmap overrides.
 *
 * Java (MixinLightmap) cannot call Kotlin `inline` extension functions
 * directly, so all entry points exposed to mixins are `@JvmStatic`.
 *
 * Two complementary overrides are provided:
 *  - [forceWhite]: clears an existing lightmap texture to white (cheap, per-frame)
 *  - [getWhiteTextureView]: returns a dedicated white 16x16 texture view that
 *    *fully replaces* the lightmap texture the shaders sample from (bulletproof)
 *
 * Both work for the vanilla ModelBlockRenderer path and the Sodium BlockRenderer
 * path, because both paths sample the same Lightmap#getTextureView output.
 */
object XRayLightmapHelper {

    /**
     * Lazily-allocated 16x16 RGBA8 white texture view used by the XRay+FullBright
     * override. 16x16 is the vanilla lightmap resolution, so it drops in cleanly.
     * Created on first use and closed when XRay is disabled or the helper is
     * reset. Allocated with USAGE_TEXTURE_BINDING so it can be sampled by shaders.
     */
    @Volatile
    private var whiteTextureView: GpuTextureView? = null

    /**
     * Clears the given lightmap texture to pure white, so every fragment shader
     * sampling it receives full-bright lighting regardless of the per-vertex
     * light coords baked into the geometry. Used as a per-frame backup of the
     * texture-view override below.
     */
    @JvmStatic
    fun forceWhite(texture: GpuTexture) {
        texture.clearColor(Color4b.WHITE)
    }

    /**
     * Returns a 16x16 fully-white texture view that can be sampled by the block
     * fragment shaders in place of the vanilla lightmap. When MixinLightmap
     * substitutes this view, every fragment samples white - so ores in pitch-
     * black caves render at full bright even though their per-vertex light
     * coords are (block=0, sky=0).
     *
     * The view is created on first call and reused on subsequent frames. It
     * is closed via [close] when XRay is disabled to release GPU memory.
     */
    @JvmStatic
    @Synchronized
    fun getWhiteTextureView(device: GpuDevice): GpuTextureView {
        var existing = whiteTextureView
        if (existing != null) {
            return existing
        }

        val texture = device.createTexture(
            "LiquidBounce XRay - FullBright Lightmap",
            GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RGBA8_UNORM,
            16,
            16,
            1,
            1,
        )
        // Clear once to pure white - the texture is static for its lifetime.
        texture.clearColor(Color4b.WHITE)
        val view = texture.asView()
        whiteTextureView = view
        return view
    }

    /**
     * Releases the cached white texture view. Called by MixinLightmap when XRay
     * is disabled so we do not leak GPU memory across session reloads.
     */
    @JvmStatic
    @Synchronized
    fun close() {
        whiteTextureView?.close()
        whiteTextureView = null
    }
}
