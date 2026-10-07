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
     * Pro fork (v3 TURBO): minimum cooldown 0..0 — NO waiting on the vanilla
     * attack-strength meter. Every attack fires the instant the clicker wants
     * it, at full CPS, so enemies can no longer hit-and-run.
     *
     * The v2 default (0.85..1.0) paced attacks to the weapon cooldown, which
     * meant only ~1.6 attacks/sec with a sword — that is exactly why enemies
     * could hit you and walk away before the aura retaliated.
     *
     * Trade-off: on servers WITH vanilla attack-cooldown damage scaling each
     * hit deals reduced damage — but the attack rate is ~12x higher, which
     * dominates on 1.8-style PvP servers and always feels responsive.
     * Raise this in the GUI if you ever want timed full-damage hits back.
     */
    private val minimumCooldown by floatRange(
        "Minimum",
        0.0f..0.0f, 0.0f..2.0f
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
