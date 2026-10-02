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
package net.ccbluex.liquidbounce.injection.mixins.minecraft.render;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleItemChams;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleXRay;
import net.ccbluex.liquidbounce.features.module.modules.render.customambience.ModuleCustomAmbience;
import net.ccbluex.liquidbounce.utils.render.XRayLightmapHelper;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.jspecify.annotations.NullMarked;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(Lightmap.class)
public abstract class MixinLightmap {

    @Shadow
    @Final
    private GpuTexture texture;

    @ModifyReturnValue(
        method = "getTextureView",
        at = @At("RETURN")
    )
    private GpuTextureView lightmapOverride(GpuTextureView original) {
        return ModuleItemChams.Lightmap.OVERRIDE.orElse(original);
    }

    /**
     * Pro fork: When XRay + FullBright are both active, force the entire lightmap
     * texture to pure white. Every fragment shader that samples the lightmap
     * (vanilla ModelBlockRenderer and Sodium BlockRenderer alike) will then receive
     * full-bright lighting, regardless of the per-vertex light coords the chunk
     * builder baked into the geometry. This is the only reliable way to brighten
     * ores in pitch-black caves where block light = 0 AND sky light = 0.
     *
     * <p>Previous attempts that tried to set per-vertex light coords on
     * {@link com.mojang.blaze3d.vertex.QuadInstance} failed because the setter
     * method name differs across MC 26.x patch versions and reflection silently
     * missed it. Clearing the lightmap texture is version-tolerant because the
     * lightmap is always a 16x16 GpuTexture that the shader samples with linear
     * filtering - clear to white and every sample returns white.</p>
     *
     * <p>This inject runs at the first {@code needsUpdate} field read inside
     * {@link Lightmap#render}, so we get a chance every frame to (re)apply the
     * white clear. We {@code cancel()} so the vanilla lightmap computation
     * (which would write the cave-dark values back) is skipped entirely.</p>
     */
    @Inject(
        method = "render(Lnet/minecraft/client/renderer/state/LightmapRenderState;)V",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/LightmapRenderState;needsUpdate:Z", ordinal = 0, opcode = Opcodes.GETFIELD),
        cancellable = true
    )
    private void injectXRayFullBrightLightmap(LightmapRenderState renderState, CallbackInfo ci) {
        ModuleXRay module = ModuleXRay.INSTANCE;
        if (module.getRunning() && module.getFullBright()) {
            XRayLightmapHelper.forceWhite(this.texture);
            ci.cancel();
            return;
        }

        // Fall through to the existing CustomAmbience lightmap path when XRay
        // is not active, preserving the original behavior.
        ModuleCustomAmbience.CustomLightmap customLightmap = ModuleCustomAmbience.CustomLightmap.INSTANCE;
        if (customLightmap.getRunning() && customLightmap.getMode().getActiveMode().edit(this.texture, renderState)) {
            ci.cancel();
        }
    }

}
