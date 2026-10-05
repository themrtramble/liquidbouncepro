# Task Plan: Fix KillAura Movement Stuck Bug (Deep Analysis)

## Goal
Find and fix the EXACT root cause of player movement freezing when KillAura is enabled. Player must be able to walk, jump, sprint, strafe normally with KillAura ON.

## Current Phase
Phase 2

## Phases

### Phase 1: Setup & Discovery
- [x] Clone ai-agent-skills repo
- [x] Read planning-with-files skill
- [x] Create planning files
- **Status:** complete

### Phase 2: Deep Code Analysis (ALL paths KillAura touches)
- [x] Read ModuleKillAura.kt fully
- [x] Read RotationManager.kt fully
- [x] Read MixinLocalPlayer.java fully
- [x] Read MixinKeyboardInput.java
- [x] Read KillAuraClicker.kt
- [x] Read KillAuraRotationsValueGroup.kt
- [x] Read MovementCorrection enum
- [x] Read RotationsValueGroup.kt
- [x] Read PlayerVelocityStrafe event
- [x] Read MixinEntity.java (hookVelocity / moveRelative)
- [x] Read ModuleSprint.kt
- [x] Read resolveMovementYaw function
- [ ] Check ALL SprintEvent handlers and priorities
- [ ] Check RotationManager.velocityHandler condition
- [ ] Check aiStep() in MC source for getYRot usage
- [ ] Trace exact tick() execution with KillAura ON vs OFF
- [ ] Check if there's a SECOND place that sets player.yRot
- **Status:** in_progress

### Phase 3: Fix Root Cause
- [ ] Implement fix
- [ ] Verify no regressions
- **Status:** pending

### Phase 4: Build & Verify
- [ ] Build
- [ ] Download JAR
- [ ] Final verification
- **Status:** pending

## Known Root Causes Fixed So Far
1. v0.40.33: hookSilentRotationYaw/Pitch on `tick()` was overriding player rotation during movement — FIXED (now only on sendPosition)
2. v0.40.34: RotationManager.update() reset path snapped player.yRot — FIXED (now gated by CHANGE_LOOK)

## STILL STUCK — What else could it be?
- Need to check: Is there ANOTHER code path that modifies player.yRot?
- Need to check: Does RotationManager still modify player.yRot in the NON-reset path?
- Need to check: Is velocityHandler affecting movement even with OFF?
- Need to check: Is there a sprint conflict between Sprint module and KillAura?
- Need to check: Does the ConfigSystem load old movementCorrection=SILENT?
