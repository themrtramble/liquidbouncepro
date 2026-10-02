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
package net.ccbluex.liquidbounce.common;

import com.mojang.blaze3d.vertex.QuadInstance;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleXRay;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * Scoped context for XRay background block rendering.
 *
 * @see net.minecraft.client.renderer.chunk.SectionCompiler#compile
 * @see net.minecraft.client.renderer.chunk.ChunkSectionLayer#byTransparency
 * @see net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo#layer
 * @see net.minecraft.client.renderer.block.ModelBlockRenderer#putQuadWithTint
 * @see net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer#processQuad
 */
public final class XRayBlockRenderContext {

    private static final ScopedValue<Integer> BACKGROUND_ALPHA = ScopedValue.newInstance();

    /**
     * Vanilla {@link QuadInstance} light setter - the method name differs across MC versions
     * (setLight on some, setLightCoords on others, setLightmap on yet others). We resolve it
     * once via reflection and cache the {@link MethodHandle} so the per-quad overhead stays
     * at a single {@code invokeExact} call. Resolution is best-effort: if no (int, int) setter
     * matching a known name is found, the FullBright light override is silently disabled and
     * ores fall back to whatever light Vanilla computes.
     */
    private static final MethodHandle QUAD_SET_LIGHT;

    static {
        MethodHandle handle = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType type = MethodType.methodType(void.class, int.class, int.class);
            String[] candidates = {"setLight", "setLightCoords", "setLightmap", "setLightCoord", "lightCoords", "light"};
            for (String name : candidates) {
                try {
                    handle = lookup.findVirtual(QuadInstance.class, name, type);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // try next candidate
                }
            }
            if (handle == null) {
                // Fall back to a public-method reflection scan in case the setter is named
                // differently than any of our guesses (e.g. vendor-specific rename).
                for (Method m : QuadInstance.class.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && p[0] == int.class && p[1] == int.class
                            && (m.getName().toLowerCase().contains("light"))) {
                        handle = lookup.unreflect(m);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // QuadInstance is unavailable / incompatible - FullBright light override disabled.
        }
        QUAD_SET_LIGHT = handle;
    }

    private XRayBlockRenderContext() {
    }

    public static boolean isRenderingTransparentBackground() {
        return BACKGROUND_ALPHA.isBound();
    }

    public static ChunkSectionLayer forceTranslucentLayer(ChunkSectionLayer original) {
        return isRenderingTransparentBackground() ? ChunkSectionLayer.TRANSLUCENT : original;
    }

    public static void renderTransparentBackground(int alpha, Runnable render) {
        if (alpha >= 255) {
            render.run();
            return;
        }

        ScopedValue.where(BACKGROUND_ALPHA, alpha).run(render);
    }

    /**
     * Runs [render] in the transparent background scope when XRay applies to the block being rendered.
     */
    public static void renderIfActive(boolean active, BlockState state, Runnable render) {
        if (!active) {
            render.run();
            return;
        }

        renderTransparentBackground(ModuleXRay.INSTANCE.transparentBackgroundAlpha(state), render);
    }

    public static void applyAlpha(QuadInstance quadInstance) {
        if (!isRenderingTransparentBackground()) {
            // Pro fork: when not transparent background (i.e. rendering an ORE),
            // force the per-vertex light coords to FULL_BRIGHT so the lightmap
            // lookup returns full white. Without this, ores inside dark caves
            // (block light = 0, sky light = 0) appear black even with FullBright's
            // gamma boost, because gamma only brightens the existing lightmap
            // values - it does not invent light where there is none.
            //
            // This mirrors what the Sodium path does in
            // MixinSodiumAbstractBlockRenderContext#injectXRayFullBright via
            // quad.setLight(i, FULL_BRIGHT_LIGHTMAP). Vanilla ModelBlockRenderer
            // builds its QuadInstance before calling BlockQuadOutput#put, so we
            // override the light here right before the quad is emitted.
            //
            // The setter is resolved reflectively because its name differs across
            // MC 26.x patch versions and the public Blaze3D API surface has been
            // moving; reflection lets us stay source-compatible without recompiling
            // against each new mappings drop.
            if (ModuleXRay.INSTANCE.getFullBright() && QUAD_SET_LIGHT != null) {
                int fullBright = LightCoordsUtil.FULL_BRIGHT;
                try {
                    for (int i = 0; i < 4; i++) {
                        QUAD_SET_LIGHT.invoke(quadInstance, i, fullBright);
                    }
                } catch (Throwable ignored) {
                    // Light setter call failed for this quad - skip silently. The
                    // ore will fall back to the default light value for this frame.
                }
            }
            return;
        }

        float alpha = BACKGROUND_ALPHA.get() / 255f;
        for (int i = 0; i < 4; i++) {
            quadInstance.setColor(i, ARGB.multiplyAlpha(quadInstance.getColor(i), alpha));
        }
    }

    public static int applyAlpha(int color) {
        float alpha = BACKGROUND_ALPHA.get() / 255f;
        return ARGB.multiplyAlpha(color, alpha);
    }

}
