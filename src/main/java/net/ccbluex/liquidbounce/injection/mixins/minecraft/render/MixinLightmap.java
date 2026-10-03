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
import com.mojang.blaze3d.systems.RenderSystem;
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
        // Pro fork: when XRay+FullBright is active, fully replace the lightmap
        // texture view with our own all-white 16x16 texture. This is the most
        // bulletproof approach because:
        //   - shaders ALWAYS sample via Lightmap#getTextureView
        //   - we control what texture they sample from
        //   - we do not depend on the vanilla Lightmap#render method being
        //     called or its dirty flag being set
        //   - it works on both vanilla and Sodium paths
        ModuleXRay module = ModuleXRay.INSTANCE;
        if (module.getRunning() && module.getFullBright()) {
            return XRayLightmapHelper.getWhiteTextureView(RenderSystem.getDevice());
        }

        // Existing ItemChams lightmap override takes precedence over
        // CustomAmbience (which only edits the texture, not the view).
        return ModuleItemChams.Lightmap.OVERRIDE.orElse(original);
    }

    /**
     * Pro fork: When XRay+FullBright is active, also clear the existing lightmap
     * texture to white as a backup of the texture-view override above. This is
     * belt-and-braces: if any code path reads the texture directly (not via
     * getTextureView), it still sees white.
     *
     * <p>Falls through to the CustomAmbience lightmap path when XRay is not
     * active, preserving the original behavior.</p>
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
