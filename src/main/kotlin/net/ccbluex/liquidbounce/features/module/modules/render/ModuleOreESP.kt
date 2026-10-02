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
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.CachedMeshStorage
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.addShapeFaces
import net.ccbluex.liquidbounce.render.addShapeOutlines
import net.ccbluex.liquidbounce.render.buildMesh
import net.ccbluex.liquidbounce.render.drawGenericBlockESP
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.getDynamicTransformsUniform
import net.ccbluex.liquidbounce.render.translate
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.block.AbstractBlockLocationTracker
import net.ccbluex.liquidbounce.utils.block.ChunkScanner
import net.ccbluex.liquidbounce.utils.math.PositionedVoxelShape
import net.ccbluex.liquidbounce.utils.math.mergeAdjacentVoxelShapes
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Matrix4f
import java.util.function.Predicate

/**
 * OreESP module — Pro fork
 *
 * ESP sirf ORES ke liye (gold, diamond, iron, coal, emerald, lapis, redstone, copper,
 * deepslate variants, nether gold/quartz, ancient debris). Blocks pe nahi, sirf ores pe.
 *
 * Color-coded by ore type:
 * - Diamond ore: cyan
 * - Gold ore: yellow
 * - Iron ore: light orange
 * - Coal ore: black
 * - Emerald ore: green
 * - Lapis ore: blue
 * - Redstone ore: red
 * - Copper ore: orange
 * - Ancient debris: purple
 * - Nether gold ore: yellow
 * - Nether quartz ore: white
 */
object ModuleOreESP : ClientModule("OreESP", ModuleCategories.RENDER, aliases = listOf("OreTracker")) {

    private val outline by boolean("Outline", true)
    private val fill by boolean("Fill", true)
    private val fillAlpha by int("FillAlpha", 80, 0..255)

    private val facesRenderState = CachedMeshStorage("$name Faces")
    private val outlinesRenderState = CachedMeshStorage("$name Outlines")
    private val dirtyFlag = atomic(true)

    /**
     * Color map: each ore type has its own distinct color for easy identification.
     */
    private val oreColors: Map<Block, Color4b> = mapOf(
        // Diamond — cyan
        Blocks.DIAMOND_ORE to Color4b(0x00, 0xFF, 0xFF, 0xFF),
        Blocks.DEEPSLATE_DIAMOND_ORE to Color4b(0x00, 0xCC, 0xCC, 0xFF),

        // Gold — yellow
        Blocks.GOLD_ORE to Color4b(0xFF, 0xD7, 0x00, 0xFF),
        Blocks.DEEPSLATE_GOLD_ORE to Color4b(0xCC, 0xAA, 0x00, 0xFF),
        Blocks.NETHER_GOLD_ORE to Color4b(0xFF, 0xAA, 0x00, 0xFF),

        // Iron — light orange
        Blocks.IRON_ORE to Color4b(0xFF, 0xA0, 0x70, 0xFF),
        Blocks.DEEPSLATE_IRON_ORE to Color4b(0xCC, 0x80, 0x60, 0xFF),

        // Coal — dark gray
        Blocks.COAL_ORE to Color4b(0x40, 0x40, 0x40, 0xFF),
        Blocks.DEEPSLATE_COAL_ORE to Color4b(0x30, 0x30, 0x30, 0xFF),

        // Emerald — green
        Blocks.EMERALD_ORE to Color4b(0x00, 0xFF, 0x00, 0xFF),
        Blocks.DEEPSLATE_EMERALD_ORE to Color4b(0x00, 0xCC, 0x00, 0xFF),

        // Lapis — blue
        Blocks.LAPIS_ORE to Color4b(0x20, 0x40, 0xFF, 0xFF),
        Blocks.DEEPSLATE_LAPIS_ORE to Color4b(0x20, 0x30, 0xCC, 0xFF),

        // Redstone — red
        Blocks.REDSTONE_ORE to Color4b(0xFF, 0x00, 0x00, 0xFF),
        Blocks.DEEPSLATE_REDSTONE_ORE to Color4b(0xCC, 0x00, 0x00, 0xFF),

        // Copper — orange
        Blocks.COPPER_ORE to Color4b(0xFF, 0x80, 0x00, 0xFF),
        Blocks.DEEPSLATE_COPPER_ORE to Color4b(0xCC, 0x60, 0x00, 0xFF),

        // Ancient Debris — purple (rare nether ore)
        Blocks.ANCIENT_DEBRIS to Color4b(0xAA, 0x00, 0xFF, 0xFF),

        // Nether Quartz — white
        Blocks.NETHER_QUARTZ_ORE to Color4b(0xFF, 0xFF, 0xFF, 0xFF),
    )

    /**
     * Set of all ore blocks we track
     */
    private val trackedOres: Set<Block> = oreColors.keys

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (outline) {
            mc.gameRenderer.mainRenderTarget().drawGenericBlockESP(
                outlinesRenderState,
                ClientRenderPipelines.relativeLines(true),
                null,
            ) {
                getDynamicTransformsUniform(
                    modelView = event.poseStack.last().pose(),
                    colorModulatorAlpha = 200,
                )
            }
        }

        if (fill) {
            mc.gameRenderer.mainRenderTarget().drawGenericBlockESP(
                facesRenderState,
                ClientRenderPipelines.relativeQuads(true),
                null,
            ) {
                getDynamicTransformsUniform(
                    modelView = event.poseStack.last().pose(),
                    colorModulatorAlpha = fillAlpha,
                )
            }
        }
    }

    private fun getDynamicTransformsUniform(
        modelView: Matrix4f? = null,
        colorModulatorAlpha: Int = -1,
    ) = getDynamicTransformsUniform(
        modelView = modelView,
        colorModulator = Color4b.WHITE,
    )

    @Suppress("unused")
    private val tickHandler = handler<net.ccbluex.liquidbounce.event.events.GameTickEvent> {
        if (BlockTracker.isEmpty()) {
            facesRenderState.clearStates()
            outlinesRenderState.clearStates()
            return@handler
        }

        if (!dirtyFlag.compareAndSet(expect = true, update = false)) {
            return@handler
        }

        val shapes = buildList {
            for ((blockPos, t) in BlockTracker.iterate()) {
                val blockState = t.state
                val color = oreColors[blockState.block] ?: continue
                val colorWithAlpha = if (colorModulatorAlphaPass >= 0) color.alpha(colorModulatorAlpha) else color
                add(
                    PositionedVoxelShape(
                        blockPos = blockPos.asLong(),
                        key = OreKey(blockState.block, color),
                        shape = t.shape,
                    )
                )
            }
        }

        if (fill) {
            facesRenderState.buildMesh(
                pipeline = ClientRenderPipelines.relativeQuads(true),
                origin = player.blockPosition(),
            ) { pose, origin ->
                for (shape in shapes) {
                    pose.withPush {
                        translate(shape.blockPos, origin)
                        addShapeFaces(last().pose(), shape.shape, shape.key.color)
                    }
                }
            }
        }

        if (outline) {
            outlinesRenderState.buildMesh(
                pipeline = ClientRenderPipelines.relativeLines(true),
                origin = player.blockPosition(),
            ) { pose, origin ->
                for (shape in shapes) {
                    pose.withPush {
                        translate(shape.blockPos, origin)
                        addShapeOutlines(last().pose(), shape.shape, shape.key.color)
                    }
                }
            }
        }
    }

    private val colorModulatorAlphaPass: Int
        get() = -1

    override fun onEnabled() {
        ChunkScanner.subscribe(BlockTracker)
    }

    override fun onDisabled() {
        ChunkScanner.unsubscribe(BlockTracker)
        facesRenderState.clearStates()
        facesRenderState.clearBuffers()
        outlinesRenderState.clearStates()
        outlinesRenderState.clearBuffers()
    }

    private data class OreKey(val block: Block, val color: Color4b)

    private class TrackedState(@JvmField val state: BlockState, @JvmField val shape: VoxelShape) {
        constructor(pos: BlockPos, state: BlockState) : this(state, state.getShape(world, pos))
    }

    private object BlockTracker : AbstractBlockLocationTracker.BlockPos2State<TrackedState>(), Predicate<BlockState> {
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
            dirtyFlag.value = true
        }

        override fun test(state: BlockState): Boolean = !state.isAir && state.block in trackedOres
    }

}
