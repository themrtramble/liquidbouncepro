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
package net.ccbluex.liquidbounce.features.module.modules.combat.killaura

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup

object KillAuraRotationsValueGroup : RotationsValueGroup(ModuleKillAura, combatSpecific = true) {

    /**
     * Pro fork: default timing changed to SNAP — rotations snap to the target
     * instantly on attack instead of smoothly gliding. Faster & more aggressive.
     */
    val rotationTiming by enumChoice("RotationTiming", KillAuraRotationTiming.SNAP)
    /**
     * Pro fork: aim through walls ON by default — KillAura will keep targeting
     * even when the enemy is briefly occluded.
     */
    val aimThroughWalls by boolean("ThroughWalls", true)

    /**
     * When enabled, if current rotation can still raytrace the target, skip rotating.
     * Pro fork: ON by default for less suspicious aim jitter.
     */
    val lazyRotation by boolean("LazyRotation", true)

    enum class KillAuraRotationTiming(override val tag: String) : Tagged {
        NORMAL("Normal"),
        SNAP("Snap"),
        ON_TICK("OnTick")
    }

}
