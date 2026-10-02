/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
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
package net.ccbluex.liquidbounce.features.module.modules.render

import kotlinx.atomicfu.atomic
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.CachedMeshStorage
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.GenericStaticColorMode
import net.ccbluex.liquidbounce.render.OreTypeColorMode
import net.ccbluex.liquidbounce.render.addShapeFaces
import net.ccbluex.liquidbounce.render.addShapeOutlines
import net.ccbluex.liquidbounce.render.buildMesh
import net.ccbluex.liquidbounce.render.drawGenericBlockESP
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getDynamicTransformsUniform
import net.ccbluex.liquidbounce.render.translate
import net.ccbluex.liquidbounce.render.utils.DistanceFadeUniformValueGroup
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.block.AbstractBlockLocationTracker
import net.ccbluex.liquidbounce.utils.block.ChunkScanner
import net.ccbluex.liquidbounce.utils.collection.blockSortedSetOf
import net.ccbluex.liquidbounce.utils.math.PositionedVoxelShape
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.function.Predicate

/**
 * OreESP module — Pro fork
 *
 * ESP only for ORES (gold, diamond, iron, coal, emerald, lapis, redstone, copper,
 * deepslate variants, nether gold/quartz, ancient debris). Uses map color mode so
 * each ore type has its own distinct color for easy identification.
 */
object ModuleOreESP : ClientModule("OreESP", ModuleCategories.RENDER, aliases = listOf("OreTracker")) {

    private val outline by boolean("Outline", true)
    private val fill by boolean("Fill", true)

    private val facesRenderState = CachedMeshStorage("$name Faces")
    private val outlinesRenderState = CachedMeshStorage("$name Outlines")
    private val dirtyFlag = atomic(true)

    private val distanceFade = tree(DistanceFadeUniformValueGroup())

    /**
     * Pro fork: Per-ore-type color mode.
     * Each ore type gets its own distinct color:
     * Diamond=cyan, Gold=yellow, Iron=orange, Coal=gray, Emerald=green,
     * Lapis=blue, Redstone=red, Copper=orange, Ancient Debris=purple.
     */
    private val colorMode = choices("ColorMode", 0) {
        arrayOf(
            OreTypeColorMode(it),
            net.ccbluex.liquidbounce.render.MapColorMode(it),
            GenericStaticColorMode(it, Color4b(255, 215, 0, 200)),
        )
    }.apply {
        onChanged { markDirty() }
    }

    private var useColor = false

    /**
     * All ore blocks tracked by this module. Includes deepslate and nether variants.
     */
    private val oreBlocks by blocks(
        "Ores",
        blockSortedSetOf(
            blocks = arrayOf(
                // Overworld ores
                Blocks.COAL_ORE,
                Blocks.IRON_ORE,
                Blocks.GOLD_ORE,
                Blocks.DIAMOND_ORE,
                Blocks.EMERALD_ORE,
                Blocks.LAPIS_ORE,
                Blocks.REDSTONE_ORE,
                Blocks.COPPER_ORE,

                // Deepslate variants
                Blocks.DEEPSLATE_COAL_ORE,
                Blocks.DEEPSLATE_IRON_ORE,
                Blocks.DEEPSLATE_GOLD_ORE,
                Blocks.DEEPSLATE_DIAMOND_ORE,
                Blocks.DEEPSLATE_EMERALD_ORE,
                Blocks.DEEPSLATE_LAPIS_ORE,
                Blocks.DEEPSLATE_REDSTONE_ORE,
                Blocks.DEEPSLATE_COPPER_ORE,

                // Nether ores
                Blocks.NETHER_GOLD_ORE,
                Blocks.NETHER_QUARTZ_ORE,
                Blocks.ANCIENT_DEBRIS,
            )
        )
    ).onChanged { markDirty() }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (outline) {
            mc.gameRenderer.mainRenderTarget().drawGenericBlockESP(
                outlinesRenderState,
                ClientRenderPipelines.relativeLines(useColor),
                distanceFade,
            ) {
                getDynamicTransformsUniform(
                    modelView = event.poseStack.last().pose(),
                    colorModulator = Color4b.WHITE,
                )
            }
        }

        if (fill) {
            mc.gameRenderer.mainRenderTarget().drawGenericBlockESP(
                facesRenderState,
                ClientRenderPipelines.relativeQuads(useColor),
                distanceFade,
            ) {
                getDynamicTransformsUniform(
                    modelView = event.poseStack.last().pose(),
                    colorModulator = Color4b.WHITE,
                )
            }
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (OreTracker.isEmpty()) {
            facesRenderState.clearStates()
            outlinesRenderState.clearStates()
            return@handler
        }

        if (!dirtyFlag.compareAndSet(expect = true, update = false)) {
            return@handler
        }

        val colorMode = colorMode.activeMode
        useColor = colorMode.isParamSensitive
        val mergedShapes = collectBlockShapes(colorMode, useColor)

        if (fill) {
            facesRenderState.buildMesh(
                pipeline = ClientRenderPipelines.relativeQuads(useColor),
                origin = player.blockPosition(),
            ) { pose, origin ->
                for (mergedShape in mergedShapes) {
                    pose.withPush {
                        translate(mergedShape.blockPos, origin)
                        addShapeFaces(last().pose(), mergedShape.shape, mergedShape.key.color)
                    }
                }
            }
        }

        if (outline) {
            outlinesRenderState.buildMesh(
                pipeline = ClientRenderPipelines.relativeLines(useColor),
                origin = player.blockPosition(),
            ) { pose, origin ->
                for (mergedShape in mergedShapes) {
                    pose.withPush {
                        translate(mergedShape.blockPos, origin)
                        addShapeOutlines(last().pose(), mergedShape.shape, mergedShape.key.color)
                    }
                }
            }
        }
    }

    private fun markDirty() {
        if (running) {
            dirtyFlag.value = true
        }
    }

    private fun collectBlockShapes(
        colorMode: net.ccbluex.liquidbounce.render.GenericColorMode<Pair<BlockPos, BlockState>>,
        useColor: Boolean,
    ): List<PositionedVoxelShape<OreKey>> {
        val shapes = buildList {
            for ((blockPos, t) in OreTracker.iterate()) {
                val blockState = t.state
                val color = if (useColor) colorMode.getColor(blockPos to blockState) else null
                add(
                    PositionedVoxelShape(
                        blockPos = blockPos.asLong(),
                        key = OreKey(blockState.block, color),
                        shape = t.shape,
                    )
                )
            }
        }
        return shapes
    }

    private data class OreKey(val block: Block, val color: Color4b?)

    private class TrackedState(@JvmField val state: BlockState, @JvmField val shape: VoxelShape) {
        constructor(pos: BlockPos, state: BlockState) : this(state, state.getShape(world, pos))
    }

    override fun onEnabled() {
        ChunkScanner.subscribe(OreTracker)
    }

    override fun onDisabled() {
        ChunkScanner.unsubscribe(OreTracker)
        facesRenderState.clearStates()
        facesRenderState.clearBuffers()
        outlinesRenderState.clearStates()
        outlinesRenderState.clearBuffers()
    }

    private object OreTracker : AbstractBlockLocationTracker.BlockPos2State<TrackedState>(), Predicate<BlockState> {
        override val shouldCallRecordBlockOnChunkUpdate: Boolean
            get() = false

        override fun chunkUpdate(chunk: LevelChunk) {
            chunk.findBlocks(this) { pos, state ->
                track(pos, TrackedState(pos, state))
            }
        }

        override fun getStateFor(pos: BlockPos, state: BlockState): TrackedState? {
            return if (this.test(state)) {
                TrackedState(pos, state)
            } else {
                null
            }
        }

        override fun onUpdated() {
            markDirty()
        }

        override fun test(state: BlockState): Boolean = !state.isAir && state.block in oreBlocks
    }

}
