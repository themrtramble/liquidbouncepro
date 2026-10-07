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
package net.ccbluex.liquidbounce.features.module.modules.movement

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PlayerJumpEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.features.ScaffoldSprintControlFeature
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.math.fastCos
import net.ccbluex.liquidbounce.utils.math.fastSin
import net.ccbluex.liquidbounce.utils.math.toRadians
import net.ccbluex.liquidbounce.utils.entity.getMovementDirectionOfInput
import net.ccbluex.liquidbounce.utils.entity.isSlowDueToUsingItem
import net.ccbluex.liquidbounce.utils.entity.movementForward
import net.ccbluex.liquidbounce.utils.entity.movementSideways
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.CRITICAL_MODIFICATION
import net.ccbluex.liquidbounce.utils.kotlin.Priority

/**
 * Sprint module (alias: KeepSprint)
 *
 * Pro fork: Sprints automatically whenever the player is moving in any
 * direction - walking forward, strafing, walking in air, walking while
 * using an item, walking while hurt. Any movement input triggers sprint.
 *
 * Default state: OFF (user enables manually when they want it).
 * Once enabled, sprint is maintained across all movement states.
 */

object ModuleSprint : ClientModule("Sprint", ModuleCategories.MOVEMENT, aliases = listOf("KeepSprint")) {

    private enum class SprintMode(override val tag: String) : Tagged {
        LEGIT("Legit"),
        OMNIDIRECTIONAL("Omnidirectional"),
        OMNIROTATIONAL("Omnirotational"),
    }

    private val sprintMode by enumChoice("Mode", SprintMode.LEGIT)

    private val ignore by multiEnumChoice<Ignore>("Ignore")

    /**
     * This is used to stop sprinting when the player is not moving forward
     * without a velocity fix enabled.
     */
    private val stopOn by multiEnumChoice("StopOn", StopOn.entries)

    val shouldSprintOmnidirectional: Boolean
        get() = running && sprintMode == SprintMode.OMNIDIRECTIONAL ||
            ScaffoldSprintControlFeature.allowOmnidirectionalSprint

    val shouldIgnoreBlindness
        get() = running && Ignore.BLINDNESS in ignore

    val shouldIgnoreHunger
        get() = running && Ignore.HUNGER in ignore

    val shouldIgnoreCollision
        get() = running && Ignore.COLLISION in ignore

    @Suppress("unused")
    private val sprintHandler = handler<SprintEvent>(priority = CRITICAL_MODIFICATION) { event ->
        if (!event.directionalInput.isMoving) {
            return@handler
        }

        // Pro fork (v2): force sprint on the CLIENT-side movement sources only
        // (MOVEMENT_TICK and INPUT). This keeps the 'KeepSprint' feel — you
        // always sprint while moving in any direction — WITHOUT touching the
        // NETWORK source.
        //
        // Forcing sprint on NETWORK too (the old v1 behavior) fought with other
        // combat modules (KillAura crit sprint-gating, Criticals, Scaffold,
        // InventoryMove) which deliberately hold back the server-side sprint
        // flag. Two modules flipping the same flag every tick made the server
        // see sprint toggling dozens of times per second — rubber-banding and
        // a jerky, 'stuck' movement feel. Client-side sources keep full speed;
        // server-side state stays consistent.
        if (event.source == SprintEvent.Source.MOVEMENT_TICK || event.source == SprintEvent.Source.INPUT) {
            event.sprint = true
        }
    }

    @Suppress("unused")
    private val sprintPreventionHandler = handler<SprintEvent> { event ->
        // Pro fork: NEVER prevent sprint. The original shouldPreventSprint()
        // checked for using-item, sneaking, ground/air, no forward movement,
        // etc. and turned sprint off. User wants KeepSprint to ALWAYS
        // keep sprinting when moving - so this handler is now a no-op.
    }

    @Suppress("unused")
    private val jumpHandler = handler<PlayerJumpEvent> { event ->
        if (sprintMode == SprintMode.OMNIDIRECTIONAL && shouldSprintOmnidirectional) {
            // Allows us to sprint boost in every direction
            event.yaw = player.getMovementDirectionOfInput()
        }
    }

    // DO NOT USE TREE TO MAKE SURE THAT THE ROTATIONS ARE NOT CHANGED
    private val rotations = RotationsValueGroup(this)

    @Suppress("unused")
    private val omniRotationalHandler = handler<GameTickEvent> {
        // Check if omnirotational sprint is enabled
        if (sprintMode != SprintMode.OMNIROTATIONAL) {
            return@handler
        }

        val yaw = player.getMovementDirectionOfInput()

        // todo: unhook pitch - AimPlan needs support for only yaw or pitch operation
        val rotation = Rotation(yaw, player.xRot)

        RotationManager.setRotationTarget(rotations.toRotationTarget(rotation), Priority.NOT_IMPORTANT,
            this@ModuleSprint)
    }

    private fun shouldPreventSprint(): Boolean {
        if (StopOn.USING_ITEM in stopOn && player.isSlowDueToUsingItem ||
            StopOn.SNEAKING in stopOn && player.isShiftKeyDown) {
            return true
        }

        val deltaYawRad = (player.yRot - (RotationManager.currentRotation ?: return false).yaw).toRadians()
        val forward = player.input.movementForward
        val sideways = player.input.movementSideways

        val hasForwardMovement = forward * deltaYawRad.fastCos() + sideways * deltaYawRad.fastSin() > 1.0E-5

        return (if (player.onGround()) StopOn.GROUND in stopOn else StopOn.AIR in stopOn)
            && !shouldSprintOmnidirectional
            && RotationManager.activeRotationTarget?.movementCorrection == MovementCorrection.OFF
            && !hasForwardMovement
    }

    private enum class Ignore(override val tag: String) : Tagged {
        BLINDNESS("Blindness"),
        HUNGER("Hunger"),
        COLLISION("Collision"),
    }

    private enum class StopOn(override val tag: String) : Tagged {
        GROUND("Ground"),
        AIR("Air"),
        SNEAKING("Sneaking"),
        USING_ITEM("UsingItem"),
    }
}
