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
package net.ccbluex.liquidbounce.utils.clicking

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.minecraft.world.entity.player.Player

open class ItemCooldown : ValueGroup("ItemCooldown", aliases = listOf("Cooldown")) {

    /**
     * Pro fork (v5 ROLLBACK): minimum cooldown 0.85..1.0 — back to the v2
     * 'light' build's timing that never froze.
     *
     * v3/v4 (0..0) removed the vanilla attack-strength wait entirely; that
     * zero-cooldown spam is exactly when the freeze came back. Waiting for the
     * attack meter (0.85..1.0) paces attacks so every hit lands at ~85-100%
     * damage — fewer packets, full damage per hit, and the movement pipeline
     * stays clean.
     *
     * Users on 1.8-style servers (no cooldown damage scaling) can still lower
     * this in the GUI.
     */
    private val minimumCooldown by floatRange(
        "Minimum",
        0.85f..1.0f, 0.0f..2.0f
    )

    private var nextCooldown = minimumCooldown.random()

    open fun isCooldownPassed(ticks: Int = 0): Boolean {
        // Fast path when the cooldown requirement is zero.
        if (nextCooldown <= 0.0f) return true
        return cooldownProgress(ticks) >= nextCooldown
    }

    /**
     * Calculates the current cooldown progress.
     *
     * This can be out of percentage range [0, 1] to allow for higher minimum cooldowns.
     *
     * @see Player.getAttackStrengthScale
     */
    fun cooldownProgress(baseTime: Int = 0) =
        (player.attackStrengthTicker + baseTime).toFloat() / player.currentItemAttackStrengthDelay

    /**
     * Generates a new cooldown based on the range that was set by the user.
     */
    fun newCooldown() {
        nextCooldown = minimumCooldown.random()
    }

}
