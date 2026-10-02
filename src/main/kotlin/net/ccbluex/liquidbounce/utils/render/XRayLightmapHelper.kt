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

import com.mojang.renderpearl.api.textures.GpuTexture
import net.ccbluex.liquidbounce.render.engine.type.Color4b

/**
 * Java-callable bridge for the Kotlin `inline fun GpuTexture.clearColor` extension.
 *
 * Java cannot see Kotlin `inline` extension functions, so the MixinLightmap
 * (Java) calls this `@JvmStatic` entry point instead. The implementation
 * forwards to the same `gpuDevice.createCommandEncoder().clearColorTexture(...)`
 * chain used everywhere else in the codebase.
 */
object XRayLightmapHelper {

    /**
     * Clears the given lightmap texture to pure white, so every fragment shader
     * sampling it (both vanilla ModelBlockRenderer and Sodium's BlockRenderer)
     * receives full-bright lighting regardless of the per-vertex light coords
     * baked into the geometry. Used by the XRay+FullBright override.
     */
    @JvmStatic
    fun forceWhite(texture: GpuTexture) {
        texture.clearColor(Color4b.WHITE)
    }
}
