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
package net.ccbluex.liquidbounce.render

import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * Pro fork: Per-ore-type color mode for OreESP.
 *
 * Returns a distinct color for each ore type:
 * - Diamond: Cyan
 * - Gold: Yellow
 * - Iron: Light orange/tan
 * - Coal: Dark gray
 * - Emerald: Green
 * - Lapis: Blue
 * - Redstone: Red
 * - Copper: Orange
 * - Ancient Debris: Purple
 * - Nether Gold: Yellow
 * - Nether Quartz: White
 *
 * Each color has configurable alpha (transparency).
 */
class OreTypeColorMode(
    override val parent: ModeValueGroup<*>,
    private val alpha: Int = 150
) : GenericColorMode<Pair<BlockPos, BlockState>>("OreType") {

    override val isParamSensitive: Boolean = true

    private val oreColorMap: Map<BlockState, Color4b> by lazy {
        val map = mutableMapOf<BlockState, Color4b>()
        val colors = listOf(
            // Diamond — cyan
            Blocks.DIAMOND_ORE to Color4b(0x00, 0xE5, 0xFF, alpha),
            Blocks.DEEPSLATE_DIAMOND_ORE to Color4b(0x00, 0xB8, 0xCC, alpha),

            // Gold — yellow
            Blocks.GOLD_ORE to Color4b(0xFF, 0xD7, 0x00, alpha),
            Blocks.DEEPSLATE_GOLD_ORE to Color4b(0xCC, 0xAA, 0x00, alpha),
            Blocks.NETHER_GOLD_ORE to Color4b(0xFF, 0xC8, 0x00, alpha),

            // Iron — light orange/tan
            Blocks.IRON_ORE to Color4b(0xFF, 0xA0, 0x60, alpha),
            Blocks.DEEPSLATE_IRON_ORE to Color4b(0xCC, 0x80, 0x50, alpha),

            // Coal — dark gray
            Blocks.COAL_ORE to Color4b(0x50, 0x50, 0x50, alpha),
            Blocks.DEEPSLATE_COAL_ORE to Color4b(0x40, 0x40, 0x40, alpha),

            // Emerald — bright green
            Blocks.EMERALD_ORE to Color4b(0x00, 0xFF, 0x00, alpha),
            Blocks.DEEPSLATE_EMERALD_ORE to Color4b(0x00, 0xCC, 0x00, alpha),

            // Lapis — blue
            Blocks.LAPIS_ORE to Color4b(0x20, 0x60, 0xFF, alpha),
            Blocks.DEEPSLATE_LAPIS_ORE to Color4b(0x20, 0x50, 0xCC, alpha),

            // Redstone — red
            Blocks.REDSTONE_ORE to Color4b(0xFF, 0x00, 0x00, alpha),
            Blocks.DEEPSLATE_REDSTONE_ORE to Color4b(0xCC, 0x00, 0x00, alpha),

            // Copper — orange
            Blocks.COPPER_ORE to Color4b(0xFF, 0x80, 0x00, alpha),
            Blocks.DEEPSLATE_COPPER_ORE to Color4b(0xCC, 0x60, 0x00, alpha),

            // Ancient Debris — purple
            Blocks.ANCIENT_DEBRIS to Color4b(0xAA, 0x00, 0xFF, alpha),

            // Nether Quartz — white
            Blocks.NETHER_QUARTZ_ORE to Color4b(0xFF, 0xFF, 0xFF, alpha),
        )

        for ((block, color) in colors) {
            map[block.defaultBlockState()] = color
        }
        map
    }

    override fun getColor(param: Pair<BlockPos, BlockState>): Color4b {
        val (_, state) = param
        return oreColorMap[state] ?: Color4b(0xFF, 0xFF, 0xFF, alpha)
    }
}
