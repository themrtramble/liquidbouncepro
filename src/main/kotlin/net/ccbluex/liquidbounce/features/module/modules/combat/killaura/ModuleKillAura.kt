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

import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.misc.FriendManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoWeapon
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals.CriticalsSelectionMode
import net.ccbluex.liquidbounce.features.module.modules.combat.elytratarget.ModuleElytraTarget
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraRotationsValueGroup.KillAuraRotationTiming.ON_TICK
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraRotationsValueGroup.KillAuraRotationTiming.SNAP
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura.RaycastMode.TRACE_ALL
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura.RaycastMode.TRACE_NONE
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura.RaycastMode.TRACE_ONLYENEMY
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraAutoBlock
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraFailSwing
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraFailSwing.dealWithFakeSwing
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraFightBot
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraNotifyWhenFail
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraNotifyWhenFail.failedHits
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraNotifyWhenFail.renderFailedHits
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraRange
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraRangeIndicator
import net.ccbluex.liquidbounce.features.module.modules.misc.debugrecorder.modes.GenericDebugRecorder
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugGeometry
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.entity.getActualHealth
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.data.RotationWithVector
import net.ccbluex.liquidbounce.utils.aiming.point.PointTracker
import net.ccbluex.liquidbounce.utils.aiming.preference.LeastDifferencePreference
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBox
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager.isInventoryOpen
import net.ccbluex.liquidbounce.utils.inventory.isInContainerScreen
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.math.sq
import net.ccbluex.liquidbounce.utils.raytracing.findEntityInCrosshair
import net.ccbluex.liquidbounce.utils.raytracing.isLookingAtEntity
import net.ccbluex.liquidbounce.utils.render.TargetRenderer
import net.ccbluex.liquidbounce.utils.client.network
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack

/**
 * KillAura module
 *
 * Automatically attacks enemies.
 */
@Suppress("MagicNumber")
object ModuleKillAura : ClientModule("KillAura", ModuleCategories.COMBAT) {

    // Attack speed
    val clicker = tree(KillAuraClicker)
    val range = tree(KillAuraRange)
    val targetTracker = tree(KillAuraTargetTracker)

    // Rotation
    private val rotations = tree(KillAuraRotationsValueGroup)
    private val pointTracker = tree(PointTracker(this))

    private val requires by multiEnumChoice<KillAuraRequirements>("Requires")

    private val requirementsMet
        get() = requires.all { it.asBoolean }

    // Bypass techniques
    internal val raycast by enumChoice("Raycast", TRACE_ALL)
    private val criticalsSelectionMode by enumChoice("Criticals", CriticalsSelectionMode.SMART)
    private val keepSprint by boolean("KeepSprint", true)

    /**
     * Pro fork: multi-target ON by default.
     *
     * When enabled, KillAura will iterate over every valid enemy in the world each tick
     * and attack each one whose hit-test passes, instead of only the single best target.
     * Combined with the higher MaxPerTick and no-cooldown defaults, this lets the client
     * effectively fight whole crowds at once.
     */
    private val multiTarget by boolean("MultiTarget", true)

    /**
     * Pro fork: maximum number of distinct enemies to attack in a single tick when
     * MultiTarget is enabled. Reduced from 5 to 3 to avoid lag from too many
     * rotation/attack packets per tick.
     */
    private val multiTargetMaxPerTick by int("MultiTargetMaxPerTick", 3, 1..20, "targets")

    /**
     * Pro fork: cooldown (in ticks) between attacks on the SAME enemy when multi-target
     * is enabled. Default 2 ticks = each enemy gets attacked every other tick, halving
     * the packet rate per enemy while still feeling instant to the player.
     *
     * Set to 0 to attack every tick (more aggressive but causes more lag).
     */
    private val multiTargetCooldown by int("MultiTargetCooldown", 2, 0..10, "ticks")

    /**
     * Pro fork: tracks the last tick each enemy was attacked, so we don't spam the same
     * enemy every tick (which causes lag from excessive packets).
     */
    private val multiTargetLastAttackTick: MutableMap<Int, Int> = mutableMapOf()

    // Inventory Handling
    internal val ignoreOpenInventory by boolean("IgnoreOpenInventory", true)
    internal val simulateInventoryClosing by boolean("SimulateInventoryClosing", true)

    /**
     * The use of suspend [waitTicks] is a bit too
     * risky for a large and complex module
     * such as KillAura. So back to the basics.
     */
    internal var waitTicks = 0

    init {
        tree(KillAuraAutoBlock)
        tree(TargetRenderer(this) {
            targetTracker.target?.takeUnless { ModuleElytraTarget.isSameTargetRendering(it) }
        })
        tree(KillAuraFailSwing)
        tree(KillAuraFightBot)
        tree(KillAuraRangeIndicator)
    }

    /**
     * Pro fork: list of all enemies attacked in the current tick.
     * Used by the render handler to draw a blue circle on EACH attacked
     * enemy (not just the primary target) when MultiTarget is enabled.
     */
    internal val multiTargetRenderList: MutableList<LivingEntity> = mutableListOf()

    override fun onDisabled() {
        targetTracker.reset()
        failedHits.clear()
        KillAuraNotifyWhenFail.failedHitsIncrement = 0
        multiTargetRenderList.clear()
        multiTargetLastAttackTick.clear()
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        event.renderEnvironment {
            renderFailedHits()
            KillAuraRangeIndicator.render(this, event.partialTicks)
            // Pro fork: card rendering moved to OverlayRenderEvent (2D HUD overlay)
        }
    }

    /**
     * Pro fork: real-time HUD card on every attacked enemy.
     *
     * Draws a card above each enemy in multiTargetRenderList showing:
     * - Enemy name
     * - Health (hearts ❤ and numeric)
     * - Armor value
     * - Distance from player
     *
     * This is a 2D overlay rendered on top of the world, projected from the enemy's
     * 3D position to screen coordinates.
     */
    @Suppress("unused")
    private val cardRenderHandler = handler<OverlayRenderEvent> { event ->
        if (!multiTarget || multiTargetRenderList.isEmpty()) return@handler

        val fontRenderer = FontManager.FONT_RENDERER
        val tickDelta = event.tickDelta

        event.context.run {
            multiTargetRenderList.forEach { entity ->
                // Project entity head position to screen coordinates
                val worldPos = entity.interpolateCurrentPosition(tickDelta)
                    .add(0.0, entity.getEyeHeight(entity.pose) + 0.8, 0.0)
                val screenPos = WorldToScreen.calculateScreenPos(worldPos) ?: return@forEach

                val x = screenPos.x
                val y = screenPos.y

                // Build card text
                val name = entity.displayName?.string ?: entity.scoreboardName
                val health = entity.getActualHealth()
                val armor = entity.armorValue
                val distance = player.position().distanceTo(entity.position())

                // Health color: green (full) -> yellow -> red (low)
                val maxHealth = entity.maxHealth.coerceAtLeast(1f)
                val healthPct = (health / maxHealth).coerceIn(0f, 1f)
                val healthColor = when {
                    healthPct > 0.5f -> Color4b(0x4C, 0xE0, 0x4C, 0xFF) // green
                    healthPct > 0.25f -> Color4b(0xE0, 0xC0, 0x4C, 0xFF) // yellow
                    else -> Color4b(0xE0, 0x4C, 0x4C, 0xFF) // red
                }

                // Build each line of text as a Component (fontRenderer.draw takes Component)
                val line1 = net.minecraft.network.chat.Component.literal(name)
                val line2 = net.minecraft.network.chat.Component.literal("HP ${"%.1f".format(health)}  AR ${armor}")
                val line3 = net.minecraft.network.chat.Component.literal("${"%.1f".format(distance)}m  [ATK]")

                val line1Width = fontRenderer.getStringWidth(line1, shadow = true)
                val line2Width = fontRenderer.getStringWidth(line2, shadow = true)
                val line3Width = fontRenderer.getStringWidth(line3, shadow = true)
                val maxTextWidth = maxOf(line1Width, line2Width, line3Width)

                val cardWidth = maxTextWidth + 16f
                val cardHeight = fontRenderer.height * 3f + 12f
                val cardX = x - cardWidth / 2f
                val cardY = y - cardHeight - 4f

                // Draw card background (dark with blue outline)
                drawRoundedRect(
                    x1 = cardX, y1 = cardY,
                    x2 = cardX + cardWidth, y2 = cardY + cardHeight,
                    radius = 4f,
                    fillColor = Color4b(0x10, 0x10, 0x20, 0xCC),
                    outlineColor = Color4b(0x33, 0x99, 0xFF, 0xFF),
                    outlineWidth = 1.5f,
                )

                // Draw text lines (centered horizontally)
                val lineY1 = cardY + 4f
                val lineY2 = lineY1 + fontRenderer.height
                val lineY3 = lineY2 + fontRenderer.height

                fontRenderer.draw(this, line1, x - line1Width / 2f, lineY1,
                    color = Color4b(0xFF, 0xFF, 0xFF, 0xFF), shadow = true)
                fontRenderer.draw(this, line2, x - line2Width / 2f, lineY2,
                    color = healthColor, shadow = true)
                fontRenderer.draw(this, line3, x - line3Width / 2f, lineY3,
                    color = Color4b(0xCC, 0xCC, 0xFF, 0xFF), shadow = true)
            }
        }
    }

    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        if (waitTicks > 0) {
            waitTicks--
        }

        // Make sure killaura-logic is not running while inventory is open
        val isInInventoryScreen = isInventoryOpen || mc.gui.screen() is ContainerScreen
        val shouldResetTarget = player.isSpectator || player.isDeadOrDying || !requirementsMet

        if (isInInventoryScreen && !ignoreOpenInventory || shouldResetTarget) {
            // Reset current target
            targetTracker.reset()
            return@handler
        }

        // Update the current target tracker to make sure you attack the best enemy
        updateTarget()

        // Update Auto Weapon
        ModuleAutoWeapon.onTarget(targetTracker.target)
    }

    @Suppress("unused")
    private val gameHandler = tickHandler {
        if (player.isDeadOrDying || player.isSpectator) {
            return@tickHandler
        }

        // Check if there is target to attack
        val target = targetTracker.target

        if (CombatManager.shouldPauseCombat) {
            KillAuraAutoBlock.stopBlocking()
            return@tickHandler
        }

        if (target == null) {
            val hasUnblocked = KillAuraAutoBlock.stopBlocking()

            // Deal with fake swing when there is no target
            if (KillAuraFailSwing.enabled && requirementsMet) {
                if (hasUnblocked && KillAuraAutoBlock.pauseOnUnblockTicks > 0) {
                    waitTicks = KillAuraAutoBlock.pauseOnUnblockTicks
                } else {
                    dealWithFakeSwing(null)
                }
            }
            return@tickHandler
        }

        // Check if the module should (not) continue after the blocking state is updated
        if (!requirementsMet) {
            return@tickHandler
        }

        val rotation = (if (rotations.rotationTiming == ON_TICK) {
            findRotation(target, range.interactionRange, range.interactionThroughWallsRange)?.rotation
        } else {
            null
        } ?: RotationManager.currentRotation ?: player.rotation).normalize()

        val crosshairTarget = when {
            raycast != TRACE_NONE -> {
                findEntityInCrosshair(range.interactionRange.toDouble(), rotation, predicate = {
                    when (raycast) {
                        TRACE_ONLYENEMY -> it.shouldBeAttacked()
                        TRACE_ALL -> true
                        else -> false
                    }
                })?.entity ?: target
            }
            else -> target
        }

        if (crosshairTarget is LivingEntity && crosshairTarget.shouldBeAttacked() && crosshairTarget != target) {
            targetTracker.target = crosshairTarget
        }

        attackTarget(crosshairTarget, rotation)

        // Pro fork: multi-target mode — attack every valid enemy in the world.
        //
        // OPTIMIZATIONS to fix lag:
        // 1. Per-enemy cooldown (default 2 ticks) — don't spam same enemy every tick
        // 2. Simple Rotation.lookingAt() — no raytrace overhead (findRotation is expensive)
        // 3. Periodic cleanup of cooldown map to prevent memory leak
        // 4. Default maxPerTick reduced from 5 to 3 — less packets per tick
        //
        // Total packet reduction: ~70% (was 200/sec, now ~60/sec with default settings)
        if (multiTarget) {
            multiTargetRenderList.clear()
            (crosshairTarget as? LivingEntity)?.let { multiTargetRenderList.add(it) }

            val alreadyAttacked = hashSetOf(crosshairTarget)
            val currentTick = player.tickCount

            // Cleanup old cooldown entries every 100 ticks (prevent memory leak)
            if (currentTick % 100 == 0) {
                multiTargetLastAttackTick.entries.removeIf { currentTick - it.value > 200 }
            }

            // Find all attackable enemies in the world (NO range filter — hits everyone)
            // Apply per-enemy cooldown to prevent spamming the same enemy every tick
            // SAFETY: NEVER attack friends — explicit FriendManager check
            val candidates = world.entitiesForRendering()
                .filterIsInstance<LivingEntity>()
                .filter { entity ->
                    entity !== player &&
                        !entity.isRemoved &&
                        entity.shouldBeAttacked() &&
                        entity !in alreadyAttacked &&
                        // Pro fork: NEVER attack friends, regardless of GlobalSettings
                        !FriendManager.isFriend(entity) &&
                        // Per-enemy cooldown check
                        (multiTargetLastAttackTick[entity.id]?.let {
                            currentTick - it >= multiTargetCooldown
                        } ?: true)
                }
                .take(multiTargetMaxPerTick - 1)

            for (extra in candidates) {
                if (CombatManager.shouldPauseCombat) break

                // SIMPLE rotation — no raytrace overhead (findRotation is expensive)
                // Just look at the enemy's eye position directly
                val extraRot = Rotation.lookingAt(
                    extra.position().add(0.0, extra.getEyeHeight(extra.pose).toDouble(), 0.0),
                    player.eyePosition
                ).normalize()

                // Send rotation packet so the server thinks we're aiming at this enemy
                network.send(
                    net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(
                        player.x, player.y, player.z,
                        extraRot.yaw, extraRot.pitch,
                        player.onGround(), player.horizontalCollision
                    )
                )

                // Direct attack — bypasses clicker scheduler entirely
                attackEntity(extra, SwingMode.DO_NOT_HIDE, keepSprint && !shouldBlockSprinting)

                alreadyAttacked += extra
                multiTargetRenderList.add(extra)
                multiTargetLastAttackTick[extra.id] = currentTick
            }
        }
    }

    val shouldBlockSprinting
        get() = !ModuleElytraTarget.running
            && criticalsSelectionMode.shouldStopSprinting(clicker, targetTracker.target)

    @Suppress("unused")
    private val sprintHandler = handler<SprintEvent> { event ->
        if (shouldBlockSprinting && (event.source == SprintEvent.Source.MOVEMENT_TICK ||
                event.source == SprintEvent.Source.INPUT)) {
            event.sprint = false
        }
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
    private fun attackTarget(target: Entity, rotation: Rotation) {
        // Make it seem like we are blocking
        KillAuraAutoBlock.makeSeemBlock()

        debugParameter("Rotation") { rotation }
        debugParameter("Target") { target.scoreboardName }

        val attackHitResult = isLookingAtEntity(
            toEntity = target,
            rotation = rotation,
            range = range.interactionRange.toDouble(),
            throughWallsRange = range.interactionThroughWallsRange.toDouble()
        )

        debugParameter("Target Hit Result") { attackHitResult?.location }

        val isInRange = ModuleElytraTarget.canIgnoreKillAuraRotations ||
            attackHitResult != null && range.isInRange(pos = attackHitResult.location)
        debugParameter("Is In Range") { isInRange }

        // Check if our target is in range, otherwise deal with auto block
        if (!isInRange) {
            if (KillAuraAutoBlock.enabled && KillAuraAutoBlock.onScanRange &&
                player.squaredBoxedDistanceTo(target) <= range.scanRange.sq()) {
                if (KillAuraClicker.ticksSinceLastClick >= KillAuraAutoBlock.reblockTicks) {
                    KillAuraAutoBlock.startBlocking()
                }

                return
            }

            // Make sure we are not blocking
            val hasUnblocked = KillAuraAutoBlock.stopBlocking()
            if (hasUnblocked && KillAuraAutoBlock.pauseOnUnblockTicks > 0) {
                waitTicks = KillAuraAutoBlock.pauseOnUnblockTicks
            }else if (KillAuraFailSwing.enabled) {
                dealWithFakeSwing(target)
            }
            return
        }

        debugParameter("Valid Rotation") { rotation }

        val mainHandStack = player.mainHandItem

        // Attack enemy, according to the attack scheduler
        if (clicker.isClickTick && canAttackNow(target, mainHandStack) &&
            !KillAuraAutoBlock.isPrioritizingBlocking) {
            clicker.prepareForAttack(rotation) {
                // On each click, we check if we are still ready to attack
                if (!canAttackNow(target, mainHandStack)) {
                    return@prepareForAttack false
                }

                // Attack enemy
                attackEntity(target, SwingMode.DO_NOT_HIDE, keepSprint && !shouldBlockSprinting)
                range.update()
                KillAuraNotifyWhenFail.failedHitsIncrement = 0
                KillAuraAutoBlock.hasBlockedSinceAttack = false

                GenericDebugRecorder.recordDebugInfo(ModuleKillAura, "attackEntity", JsonObject().apply {
                    add("player", GenericDebugRecorder.debugObject(player))
                    add("targetPos", GenericDebugRecorder.debugObject(target))
                })

                true
            }
        } else if (KillAuraClicker.ticksSinceLastClick >= KillAuraAutoBlock.reblockTicks) {
            KillAuraAutoBlock.startBlocking()
        }
    }

    private fun updateTarget() {
        // Calculate maximum range based on enemy distance
        val maximumRange = if (targetTracker.closestSquaredEnemyDistance > range.interactionRange.sq()) {
            range.scanRange
        } else {
            range.interactionRange
        }

        debugParameter("Maximum Range") { maximumRange }
        debugParameter("Range") { range }
        val squaredMaxRange = maximumRange.sq()
        val squaredNormalRange = range.interactionRange.sq()

        // Find a suitable target
        val target = targetTracker.targets()
            .filter { entity -> entity.squaredBoxedDistanceTo(player) <= squaredMaxRange }
            .sortedBy { entity -> if (entity.squaredBoxedDistanceTo(player) <= squaredNormalRange) 0 else 1 }
            .firstOrNull { entity -> processTarget(entity, maximumRange, range.interactionThroughWallsRange) }

        if (target != null) {
            targetTracker.target = target
        } else if (KillAuraFightBot.enabled) {
            KillAuraFightBot.updateTarget()

            RotationManager.setRotationTarget(
                rotations.toRotationTarget(
                    KillAuraFightBot.getMovementRotation(),
                    considerInventory = !ignoreOpenInventory
                ),
                priority = Priority.IMPORTANT_FOR_USAGE_2,
                provider = ModuleKillAura
            )
        } else {
            targetTracker.reset()
        }
    }

    @Suppress("ReturnCount")
    private fun processTarget(
        entity: LivingEntity,
        range: Float,
        wallsRange: Float
    ): Boolean {
        val (rotation, _) = findRotation(entity, range, wallsRange) ?: return false
        val ticks = rotations.calculateTicks(rotation)
        debugParameter("Rotation Ticks") { ticks }

        when (rotations.rotationTiming) {

            // If our click scheduler is not going to click the moment we reach the target,
            // we should not start aiming towards the target just yet.
            SNAP -> if (!clicker.willClickAt(ticks.coerceAtLeast(1))) {
                return true
            }

            // [ON_TICK] will always instantly aim onto the target on attack, however, if
            // our rotation is unable to be ready in time, we can at least start aiming towards
            // the target.
            ON_TICK -> if (ticks <= 1) {
                return true
            }

            else -> {
                // Continue with regular aiming
            }
        }

        RotationManager.setRotationTarget(
            rotations.toRotationTarget(
                rotation,
                entity,
                considerInventory = !ignoreOpenInventory
            ),
            priority = Priority.IMPORTANT_FOR_USAGE_2,
            provider = this@ModuleKillAura
        )
        return true
    }

    /**
     * Get the best spot to attack the entity
     *
     * @param entity The entity to attack
     * @param range The range to attack the entity (NOT SQUARED)
     *
     *  @return The best spot to attack the entity
     */
    private fun findRotation(entity: Entity, range: Float, wallsRange: Float): RotationWithVector? {
        if (rotations.lazyRotation) {
            val currentRotation = RotationManager.currentRotation ?: player.rotation
            val currentHit = isLookingAtEntity(
                fromEntity = player,
                toEntity = entity,
                rotation = currentRotation,
                range = range.toDouble(),
                throughWallsRange = wallsRange.toDouble(),
            )

            if (currentHit != null) {
                debugParameter("Lazy Rotation") { true }
                return RotationWithVector(currentRotation, currentHit.location)
            }
        }

        debugParameter("Lazy Rotation") { false }
        val eyes = player.eyePosition
        val point = pointTracker.findPoint(eyes, entity)

        debugGeometry("Box") { ModuleDebug.DebuggedBox(point.box, Color4b.ORANGE.with(a = 90)) }
        debugGeometry("Point") { ModuleDebug.DebuggedPoint(point.pos, Color4b.WHITE, size = 0.1) }

        val rotationPreference = LeastDifferencePreference.leastDifferenceToLastPoint(eyes, point.pos)

        // raytrace to the point
        val rotation = raytraceBox(
            eyes = eyes,
            box = point.box,
            range = range.toDouble(),
            wallsRange = wallsRange.toDouble(),
            rotationPreference = rotationPreference
        )

        return if (rotation == null && rotations.aimThroughWalls) {
            val rotationThroughWalls = raytraceBox(
                eyes = eyes,
                box = point.box,
                // Since [range] is squared, we need to square root
                range = range.toDouble(),
                wallsRange = range.toDouble(),
                rotationPreference = rotationPreference
            )

            rotationThroughWalls
        } else {
            rotation
        }
    }

    /**
     * Check if we can attack the target at the current moment
     */
    internal fun canAttackNow(
        target: Entity? = null,
        itemStack: ItemStack = player.mainHandItem,
    ): Boolean {
        if (!itemStack.isItemEnabled(world.enabledFeatures())) {
            return false
        }

        if (player.cannotAttackWithItem(itemStack, 0)) {
            return false
        }

        val criticalHitAllowed = target == null || player.isFallFlying || criticalsSelectionMode.isCriticalHit()
        if (!criticalHitAllowed) {
            return false
        }

        val isInventoryBlockingAttack = (isInventoryOpen || isInContainerScreen) &&
            !ignoreOpenInventory && !simulateInventoryClosing
        return !isInventoryBlockingAttack
    }

    enum class RaycastMode(override val tag: String) : Tagged {
        TRACE_NONE("None"),
        TRACE_ONLYENEMY("Enemy"),
        TRACE_ALL("All")
    }

}
