# Findings: KillAura Movement Stuck Bug

## Code Paths That Touch Player Movement

### 1. MixinLocalPlayer.java
- `hookSilentRotationYaw` — overrides `getYRot()` in sendPosition (v0.40.33: removed tick)
- `hookSilentRotationPitch` — overrides `getXRot()` in sendPosition (v0.40.33: removed tick)
- `hookMovementTickEvent` — fires PlayerMovementTickEvent, can cancel aiStep
- `hookMovementPre` — fires PlayerNetworkMovementTickEvent PRE, can cancel sendPosition
- `modifyXPosition/Y/Z` — overrides position in sendPosition
- `hookIsWalking` — overrides `canStartSprinting` `hasForwardImpulse` check
- `hookSprint0/hookSprint1` — fires SprintEvent on `canStartSprinting` and `input.sprint()`
- `hookSprintIgnoreCollision` — modifies `shouldStopRunSprinting`
- `hookForceStopSprinting` — adds force stop condition
- `hookSilentRotationYaw/Pitch` — overrides rotation in sendPosition

### 2. RotationManager.kt
- `update()` — called every GameTickEvent, modifies currentRotation AND player.yRot (v0.40.34: gated by CHANGE_LOOK)
- `velocityHandler` — handles PlayerVelocityStrafe, only active when movementCorrection != OFF
- `movementYaw` property — used by resolveMovementYaw, returns managedYaw when movementCorrection != OFF
- `applyChangeLookRotation` — applies rotation to player.setRotation, only CHANGE_LOOK mode
- `gameTickHandler` — calls update() every tick
- `packetHandler` — tracks rotation in packets
- `mouseMovement` — adjusts rotation on mouse move, only CHANGE_LOOK

### 3. ModuleKillAura.kt
- `rotationUpdateHandler` — calls updateTarget() + setRotationTarget via processTarget
- `gameHandler` — tickHandler, calls attackTarget + multi-target loop
- `sprintHandler` — SprintEvent handler, blocks sprint on NETWORK when shouldBlockSprinting
- `shouldBlockSprinting` — true when Criticals=SMART and clicker.willClickAt(1)

### 4. KillAuraRotationsValueGroup.kt
- `rotationTiming` = SNAP (default)
- `aimThroughWalls` = true
- `lazyRotation` = true

### 5. RotationsValueGroup.kt
- `movementCorrection` = OFF (v0.40.30 fix)

### 6. ModuleSprint.kt
- `sprintHandler` — sets event.sprint = true when moving (priority CRITICAL_MODIFICATION)
- `sprintPreventionHandler` — no-op (Pro fork)

### 7. MixinEntity.java
- `hookVelocity` — fires PlayerVelocityStrafe on `moveRelative`

### 8. MixinKeyboardInput.java
- `modifyInput` — fires MovementInputEvent + SprintEvent(INPUT)

## SUSPECT: velocityHandler still active?

RotationManager.velocityHandler:
```kotlin
private val velocityHandler = handler<PlayerVelocityStrafe>(priority = MODEL_STATE) { event ->
    if (activeRotationTarget?.movementCorrection != MovementCorrection.OFF) {
        val rotation = currentRotation ?: return@handler
        event.velocity = Entity.getInputVector(
            event.movementInput,
            event.speed,
            rotation.yaw  // <-- uses ROTATION TARGET yaw, not player yaw!
        )
    }
}
```

When movementCorrection = OFF, this is SKIPPED. Good.

BUT: resolveMovementYaw is used in other places (FallingPlayer, EntityExtensions).
Let me check if any movement code reads RotationManager.movementYaw.

## SUSPECT: ConfigSystem loading old SILENT value

If user has `.minecraft/LiquidBounce/settings.json` with old `movementCorrection = SILENT`,
the config will OVERRIDE our default OFF. User needs to delete config.

## SUSPECT: hookSprint0/hookSprint1 fire SprintEvent

MixinLocalPlayer lines 416-427:
```java
private boolean hookSprint0(boolean original) {
    var event = new SprintEvent(new DirectionalInput(input), original, SprintEvent.Source.MOVEMENT_TICK);
    EventManager.INSTANCE.callEvent(event);
    return event.getSprint();
}
```

This fires SprintEvent on every `canStartSprinting()` call. KillAura.sprintHandler
handles this. With v0.40.31 fix, sprintHandler only blocks NETWORK source.
So MOVEMENT_TICK should pass through.

BUT: KillAura.sprintHandler has DEFAULT priority (not CRITICAL_MODIFICATION).
ModuleSprint.sprintHandler has CRITICAL_MODIFICATION priority.
What if Sprint module is OFF but KillAura's handler still runs?

KillAura.sprintHandler:
```kotlin
if (shouldBlockSprinting && event.source == SprintEvent.Source.NETWORK) {
    event.sprint = false
}
```
Only blocks NETWORK. Good — won't affect MOVEMENT_TICK.

## SUSPECT: player.yRot STILL modified somewhere

Need to check: Does `update()` modify player.yRot in the NON-reset path?

RotationManager.update() line 257-262 (else branch):
```kotlin
} else {
    currentRotation = rotation
    previousRotationTarget = activeRotationTarget
    rotationTarget?.whenReached?.invoke()
}
```

This does NOT modify player.yRot. Only the reset path does (which we gated).

BUT: `applyChangeLookRotation` does:
```kotlin
if (movementCorrection == MovementCorrection.CHANGE_LOOK) {
    player.setRotation(interpolated)
}
```
Only CHANGE_LOOK. With OFF, this is skipped. Good.

## KEY FINDING: hookSilentRotationYaw fires on sendPosition

sendPosition reads getYRot() to build the move packet. With our hook,
it returns rotation.yRot() (server-side target). This is correct —
server gets the spoofed rotation.

BUT: does sendPosition also use getYRot() for any CLIENT-SIDE logic?
Need to check MC source for sendPosition implementation.

## NEXT STEPS
1. Check if there's a mixin that hooks aiStep() and modifies movement
2. Check if Sprint module conflicts with KillAura sprintHandler
3. Check if CombatManager pauses something
4. Check if there's a position freeze / ModuleFreeze auto-enabling
5. Run a git diff to see ALL changes we've made to movement-related files
