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

package net.ccbluex.liquidbounce.features.module.modules.movement.fly.modes

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.events.BlockShapeEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerJumpEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.sequenceHandler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.event.waitTicks
import net.ccbluex.liquidbounce.features.module.modules.movement.fly.ModuleFly
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.entity.withStrafe
import net.ccbluex.liquidbounce.utils.math.sq
import net.ccbluex.liquidbounce.utils.math.withLength
import net.ccbluex.liquidbounce.utils.network.MovePacketType
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import kotlin.jvm.optionals.getOrNull

internal object FlyVanilla : Mode("Vanilla") {

    private val glide by float("Glide", 0.0f, -1f..1f)

    private val bypassVanillaCheck by boolean("BypassVanillaCheck", true)

    object BaseSpeed : ValueGroup("BaseSpeed") {
        val horizontalSpeed by float("Horizontal", 1.0f, 0.1f..10f)
        val verticalSpeed by float("Vertical", 1.0f, 0.1f..10f)
    }

    object SprintSpeed : ToggleableValueGroup(this, "SprintSpeed", true) {
        val horizontalSpeed by float("Horizontal", 2.0f, 0.1f..10f)
        val verticalSpeed by float("Vertical", 2.0f, 0.1f..10f)
    }

    init {
        tree(BaseSpeed)
        tree(SprintSpeed)
    }

    override val parent: ModeValueGroup<*>
        get() = ModuleFly.modes

    /**
     * Pro fork: packet-based anti-cheat bypass (same approach as FlyCreative).
     *
     * Every 40 ticks (2 seconds), if the player is mid-air with no block directly
     * below within 0.55 blocks, we send a fake "I'm on ground" position packet so
     * the server's vanilla anti-fly check does not flag us for hovering.
     *
     * This is much more reliable than the old `waitTicks(1) + deltaMovement.y = -0.04`
     * approach which suspended our tickHandler for 2 ticks and broke the fly.
     */
    private fun shouldFlyDown(): Boolean {
        if (!bypassVanillaCheck) return false
        if (player.tickCount % 40 != 0) return false
        // If a block is right below the player, no need to spoof — server will see us
        // standing on it anyway.
        if (world.getBlockStates(player.boundingBox.move(0.0, -0.55, 0.0)).anyMatch { !it.isAir }) return false
        return true
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        val useSprintSpeed = mc.options.keySprint.isDown && SprintSpeed.enabled
        val hSpeed =
            if (useSprintSpeed) SprintSpeed.horizontalSpeed else BaseSpeed.horizontalSpeed
        val vSpeed =
            if (useSprintSpeed) SprintSpeed.verticalSpeed else BaseSpeed.verticalSpeed

        // Pro fork: FORCE both flying flags every tick. Vanilla physics can reset
        // `flying` in survival mode (e.g. when landing on ground) and reset
        // `mayfly` if the server sends an abilities packet. Re-setting them
        // every tick guarantees the player stays in flight mode.
        player.abilities.mayfly = true
        player.abilities.flying = true
        player.abilities.flyingSpeed = hSpeed
        player.fallDistance = 0.0

        // Manual vertical control. Vanilla flying uses jump/shift keys but with
        // a very slow vertical speed (~0.05). Override only when keys pressed so
        // the player gets the configured vSpeed.
        val dy = when {
            mc.options.keyJump.isDown && !mc.options.keyShift.isDown -> vSpeed.toDouble()
            mc.options.keyShift.isDown && !mc.options.keyJump.isDown -> (-vSpeed).toDouble()
            else -> glide.toDouble()
        }

        // Horizontal strafe + vertical override in one shot
        player.deltaMovement = player.deltaMovement.withStrafe(speed = hSpeed.toDouble())
        player.deltaMovement = Vec3(player.deltaMovement.x, dy, player.deltaMovement.z)

        // Anti-cheat bypass: send a fake "on ground" position packet every 40 ticks
        // so the server does not flag us for hovering (vanilla anti-fly check).
        if (shouldFlyDown()) {
            network.send(MovePacketType.POSITION_AND_ON_GROUND.generatePacket())
        }
    }

    /**
     * Pro fork: when bypassing the vanilla fly check, also rewrite the y-coordinate
     * of any outgoing position packet so the server thinks we are 0.04 blocks
     * below our actual position (mimicking a tiny fall). This is what the vanilla
     * anti-cheat expects from a "non-flying" player and prevents rollback.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (shouldFlyDown() && event.packet is ServerboundMovePlayerPacket) {
            event.packet.y = player.yLast - 0.04
        }
    }

    override fun disable() {
        // Pro fork: turn flying OFF when leaving this mode so vanilla physics (gravity)
        // resumes normally. Without this, the player stays in creative-flight state.
        player.abilities.flying = false
    }

}

internal object FlyCreative : Mode("Creative") {

    override val parent: ModeValueGroup<*>
        get() = ModuleFly.modes

    private val speed by float("Speed", 0.1f, 0.1f..5f)

    private object SprintSpeed : ToggleableValueGroup(this, "SprintSpeed", true) {
        val speed by float("Speed", 0.1f, 0.1f..5f)
    }

    init {
        tree(SprintSpeed)
    }

    private val maxVelocity by float("MaxVelocity", 4f, 1f..20f)

    private val bypassVanillaCheck by boolean("BypassVanillaCheck", true)

    private val forceFlight by boolean("ForceFlight", true)

    private fun shouldFlyDown(): Boolean {
        if (!bypassVanillaCheck) return false
        if (player.tickCount % 40 != 0) return false

        // check if the player is above a block or in midair
        // if the player is right above a block, we don't need to fly down
        if (world.getBlockStates(player.boundingBox.move(0.0, -0.55, 0.0)).anyMatch { !it.isAir }) return false

        return true
    }

    val repeatable = tickHandler {
        player.abilities.flyingSpeed =
            if (mc.options.keySprint.isDown && SprintSpeed.enabled) SprintSpeed.speed else speed

        // Pro fork: force mayfly too because vanilla physics resets it in survival.
        if (forceFlight) {
            player.abilities.mayfly = true
            player.abilities.flying = true
        }
        // Pro fork: reset fall distance so disabling FlyCreative mid-air does not
        // kill the player with accumulated fall damage.
        player.fallDistance = 0.0

        if (player.deltaMovement.lengthSqr() > maxVelocity.sq()) {
            player.deltaMovement = player.deltaMovement.withLength(maxVelocity.toDouble())
        }

        if (shouldFlyDown()) {
            network.send(MovePacketType.POSITION_AND_ON_GROUND.generatePacket())
        }

    }

    val packetHandler = handler<PacketEvent> { event ->
        if (shouldFlyDown() && event.packet is ServerboundMovePlayerPacket) {
            event.packet.y = player.yLast - 0.04
        }
    }

    override fun disable() {
        player.abilities.flying = false
    }

}

internal object FlyAirWalk : Mode("AirWalk") {

    override val parent: ModeValueGroup<*>
        get() = ModuleFly.modes

    val onGround by boolean("OnGround", true)

    val packetHandler = handler<PacketEvent> { event ->
        if (event.packet is ServerboundMovePlayerPacket) {
            event.packet.onGround = onGround
        }
    }

    @Suppress("unused")
    val shapeHandler = handler<BlockShapeEvent> { event ->
        if (event.state.block !is LiquidBlock && event.pos.y < player.y) {
            event.shape = Shapes.block()
        }
    }

    @Suppress("unused")
    val jumpEvent = handler<PlayerJumpEvent> { event ->
        event.cancelEvent()
    }
}

/**
 * Explode yourself to fly
 * Takes any kind of damage, preferably explosion damage.
 * Might bypass some anti-cheats.
 */
internal object FlyExplosion : Mode("Explosion") {

    override val parent: ModeValueGroup<*>
        get() = ModuleFly.modes

    val vertical by float("Vertical", 4f, 0f..10f)
    val startStrafe by float("StartStrafe", 1f, 0.6f..4f)
    val strafeDecrease by float("StrafeDecrease", 0.005f, 0.001f..0.1f)

    private var strafeSince = 0.0f

    override fun enable() {
        chat("You need to be damaged by an explosion to fly.")
        super.enable()
    }

    val repeatable = tickHandler {
        if (strafeSince > 0) {
            if (!player.onGround()) {
                player.deltaMovement = player.deltaMovement.withStrafe(speed = strafeSince.toDouble())
                strafeSince -= strafeDecrease
            } else {
                strafeSince = 0f
            }
        }
    }

    val packetHandler = sequenceHandler<PacketEvent> { event ->
        val packet = event.packet

        // Check if this is a regular velocity update
        if (packet is ClientboundSetEntityMotionPacket && packet.id == player.id) {
            // Modify packet according to the specified values
            packet.movement.x = 0.0
            packet.movement.y = packet.movement.y * vertical
            packet.movement.z = 0.0

            waitTicks(1)
            strafeSince = startStrafe
        } else if (packet is ClientboundExplodePacket) { // Check if explosion affects velocity
            packet.playerKnockback.getOrNull()?.let { knockback ->
                knockback.x = 0.0
                knockback.y *= vertical
                knockback.z = 0.0

                waitTicks(1)
                strafeSince = startStrafe
            }
        }
    }

}

internal object FlyJetpack : Mode("Jetpack") {

    override val parent: ModeValueGroup<*>
        get() = ModuleFly.modes

    val repeatable = handler<GameTickEvent> {
        if (player.input.keyPresses.jump) {
            val deltaMovement = player.deltaMovement
            player.deltaMovement = Vec3(
                deltaMovement.x * 1.1,
                deltaMovement.y + 0.15,
                deltaMovement.z * 1.1,
            )
        }
    }

}
