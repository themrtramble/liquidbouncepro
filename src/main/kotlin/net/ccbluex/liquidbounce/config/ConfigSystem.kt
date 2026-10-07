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
package net.ccbluex.liquidbounce.config

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.gson.fileGson
import net.ccbluex.liquidbounce.config.gson.util.parseTree
import net.ccbluex.liquidbounce.config.types.Config
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.client.clientLogger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.io.createZipArchive
import java.io.File
import java.io.Reader
import java.io.Writer
import java.util.zip.ZipFile

/**
 * A hierarchy config system
 */
@Suppress("TooManyFunctions")
object ConfigSystem {

    const val KEY_PREFIX = "liquidbounce"

    private val logger = clientLogger("ConfigSystem")

    var isFirstLaunch: Boolean = false
        private set

    // Config directory folder
    val rootFolder = File(
        mc.gameDirectory, LiquidBounce.CLIENT_NAME
    ).apply {
        // Check if there is already a config folder and if not create new folder
        // (mkdirs not needed - .minecraft should always exist)
        if (!exists()) {
            isFirstLaunch = true
            mkdir()
        }
    }

    // User config directory folder
    val userConfigsFolder = File(
        rootFolder, "configs"
    ).apply {
        // Check if there is already a config folder and if not create new folder
        // (mkdirs not needed - .minecraft should always exist)
        if (!exists()) {
            mkdir()
        }
    }

    internal val backupFolder = File(
        rootFolder, "backups"
    ).apply {
        // Check if there is already a config folder and if not create new folder
        // (mkdirs not needed - .minecraft should always exist)
        if (!exists()) {
            mkdir()
        }
    }

    val configs = ArrayList<Config>()

    fun findValueByKey(key: String): Value<*>? {
        ensureRootKeys()
        val normalizedKey = normalizeKeyInput(key)
        return configs.asSequence()
            .flatMap { it.collectValuesRecursively(normalizedKey) }
            .firstOrNull { it.key?.equals(normalizedKey, true) == true }
    }

    fun findValueGroupByKey(key: String): ValueGroup? {
        ensureRootKeys()
        val normalizedKey = normalizeKeyInput(key)
        return configs.asSequence()
            .flatMap { it.collectValueGroupsRecursively(normalizedKey) }
            .firstOrNull { it.key?.equals(normalizedKey, true) == true }
    }

    fun valueKeySequence(prefix: String): Sequence<String> = sequence {
        ensureRootKeys()
        for (valueGroup in configs) {
            for (value in valueGroup.collectValuesRecursively(prefix)) {
                value.key?.let { yield(it) }
            }
        }
    }

    fun valueGroupsKeySequence(prefix: String): Sequence<String> = sequence {
        ensureRootKeys()
        for (valueGroup in configs) {
            for (child in valueGroup.collectValueGroupsRecursively(prefix)) {
                child.key?.let { yield(it) }
            }
        }
    }

    /**
     * Create an config based on an existing tree
     */
    fun root(name: String, tree: MutableCollection<out ValueGroup> = mutableListOf()): Config {
        @Suppress("UNCHECKED_CAST")
        return root(Config(name, value = tree as MutableCollection<Value<*>>))
    }

    /**
     * Add an existing config instance
     */
    fun root(config: Config): Config {
        require(configs.none { it.loweredName == config.loweredName }) {
            "A config named '${config.loweredName}' is already registered"
        }

        config.walkInit()
        configs.add(config)
        return config
    }

    fun remove(config: Config): Boolean = configs.remove(config)

    /**
     * Create a ZIP file backup of configs
     */
    fun backup(fileName: String, groups: Iterable<Config> = this.configs) {
        var zipFile = File(backupFolder, "$fileName.zip")
        var suffix = 1
        while (zipFile.exists()) {
            zipFile = File(backupFolder, "${fileName}_${suffix++}.zip")
        }

        groups.map { valueGroup -> valueGroup.jsonFile }.createZipArchive(zipFile)
    }

    /**
     * Restore a backup from a ZIP file to the configs
     */
    fun restore(fileName: String) {
        val zipFile = File(backupFolder, "$fileName.zip")
        check(zipFile.exists()) { "Backup file does not exist" }

        // The backup holds the json files of the configs, which load straight from it
        ZipFile(zipFile).use { zip ->
            for (config in configs) {
                val entry = zip.getEntry(config.jsonFile.name) ?: continue
                deserializeValueGroup(config, zip.getInputStream(entry).bufferedReader())
                store(config)
            }
        }
    }

    /**
     * Loads all registered configs.
     */
    fun loadAll() {
        for (valueGroup in configs) { // Make a new .json file to save our root config
            load(valueGroup)
        }
    }

    fun load(config: Config) {
        config.jsonFile.runCatching {
            if (!exists()) {
                // Do not try to load a non-existing file
                return@runCatching
            }

            logger.debug("Reading config ${config.loweredName}...")
            deserializeValueGroup(config, bufferedReader())
        }.onSuccess {
            logger.info("Successfully loaded config '${config.loweredName}'.")
        }.onFailure {
            logger.error("Unable to load config ${config.loweredName}", it)
        }

        // After loading the config, we need to store it again to make sure all values are up to date
        store(config)
    }

    /**
     * All configs known to the config system should be stored now.
     * This will overwrite all existing files with the new values.
     *
     * These configs are root configs, which always create a new file with their name.
     */
    fun storeAll() {
        configs.forEach(::store)
    }

    /**
     * Store config to a file (will be created if not exists).
     *
     * The config should be known to the config system.
     */
    fun store(config: Config) {
        config.jsonTmpFile.runCatching {
            // Write to temp file
            logger.debug("Writing config ${config.loweredName}...")
            if (!exists()) {
                createNewFile().let { logger.debug("Created new file (status: $it)") }
            }
            serializeValueGroup(config, bufferedWriter())
            logger.debug("Writing config ${config.loweredName}... done")

            // Move temp file to final file
            if (config.jsonFile.exists() && !config.jsonFile.delete()) {
                error("Unable to delete old file for config ${config.loweredName}")
            }

            if (!renameTo(config.jsonFile)) {
                error("Unable to rename temp file to final file for config ${config.loweredName}")
            }
            logger.info("Successfully stored config '${config.loweredName}'.")
        }.onFailure {
            logger.error("Unable to store config ${config.loweredName}", it)
        }
    }

    /**
     * Serialize a config to a writer and close it
     */
    private fun serializeValueGroup(valueGroup: ValueGroup, writer: Writer, gson: Gson = fileGson) {
        gson.newJsonWriter(writer).use {
            gson.toJson(valueGroup, ValueGroup::class.javaObjectType, it)
        }
    }

    /**
     * Serialize a config to a [JsonObject].
     */
    fun serializeValueGroup(valueGroup: ValueGroup, gson: Gson = fileGson): JsonObject =
        gson.toJsonTree(valueGroup, ValueGroup::class.javaObjectType) as JsonObject

    /**
     * Deserialize a config from a reader, and close it
     */
    fun deserializeValueGroup(valueGroup: ValueGroup, reader: Reader, gson: Gson = fileGson) {
        gson.newJsonReader(reader).use { reader ->
            deserializeValueGroup(valueGroup, reader.parseTree())
        }
    }

    /**
     * Deserialize a config from a [JsonElement]. It should be [JsonObject].
     */
    fun deserializeValueGroup(valueGroup: ValueGroup, jsonElement: JsonElement) {
        val jsonObject = jsonElement.asJsonObject

        // Check if the name is the same as the config name
        val name = jsonObject.getAsJsonPrimitive("name").asString
        check(name == valueGroup.name || valueGroup.aliases.contains(name)) {
            "config name does not match the name in the json object"
        }

        valueGroup.prepareDeserialize(jsonObject)

        val storedValues = jsonObject.getAsJsonArray("value")
        val valuesByName = buildMap {
            for (valueElem in storedValues) {
                val valueObj = valueElem.asJsonObject
                val valueName = valueObj["name"].asString
                this.getOrPut(valueName) { ArrayDeque(1) }.addLast(valueObj)
            }
        }

        // Migration Code for KillAura's Range Values
        if (valueGroup is ModuleKillAura) {
            valueGroup.range.migrateFromValues(valuesByName)
        }

        // Pro fork: Migration Code for MovementCorrection
        // Old configs may have MovementCorrection = SILENT which causes
        // player movement to freeze when KillAura is enabled. Force
        // reset to OFF (the new safe default) if the saved value is
        // SILENT or STRICT. This runs once during config load and the
        // user can then change it in ClickGUI if they really want to.
        for (valueElem in storedValues) {
            val valueObj = valueElem.asJsonObject
            val valueName = valueObj["name"]?.asString ?: continue
            if (valueName == "MovementCorrection") {
                val savedValue = valueObj["value"]?.asString
                if (savedValue == "SILENT" || savedValue == "STRICT" || savedValue == "CHANGE_LOOK") {
                    valueObj.addProperty("value", "OFF")
                }
            }
            // Pro fork: Also force reset KillAura Criticals to IGNORE (was SMART)
            // SMART mode pauses attacks when player is jumping (waits for crit),
            // which causes the 'stuck' feeling during PvP combat.
            if (valueName == "Criticals") {
                val savedValue = valueObj["value"]?.asString
                if (savedValue == "SMART" || savedValue == "ALWAYS") {
                    valueObj.addProperty("value", "IGNORE")
                }
            }
            // Pro fork: Also force reset XRay BackgroundOpacity to 0 (clean XRay view)
            // Old configs may have BackgroundOpacity = 100 which shows dim background
            // and makes XRay look like it's not working ("sab normal dikhta hai")
            if (valueName == "BackgroundOpacity") {
                val savedValue = valueObj["value"]?.asInt
                if (savedValue != null && savedValue > 0) {
                    valueObj.addProperty("value", 0)
                }
            }
        }

        for (value in valueGroup.inner) {
            if (!value.isPersistent) continue

            val queue = valuesByName[value.name]
                ?: value.aliases.firstNotNullOfOrNull { valuesByName[it] }
                ?: continue
            if (queue.isEmpty()) continue

            var valueJson = queue.removeFirst()

            // Pro fork: Per-value migration (fires for EVERY value including nested groups)
            // This is the CORRECT place for migration — not the top-level scan above,
            // because MovementCorrection lives inside Rotations (nested in KillAura)
            // and the top-level scan never reaches it.
            val valueName = valueJson["name"]?.asString ?: ""
            if (valueName == "MovementCorrection") {
                val savedValue = valueJson["value"]?.asString
                if (savedValue == "SILENT" || savedValue == "STRICT" || savedValue == "CHANGE_LOOK") {
                    valueJson.addProperty("value", "OFF")
                }
            }
            if (valueName == "Criticals") {
                val savedValue = valueJson["value"]?.asString
                if (savedValue == "SMART" || savedValue == "ALWAYS") {
                    valueJson.addProperty("value", "IGNORE")
                }
            }
            if (valueName == "BackgroundOpacity") {
                val savedValue = valueJson["value"]?.asInt
                if (savedValue != null && savedValue > 0) {
                    valueJson.addProperty("value", 0)
                }
            }

            // Pro fork (v2): ROOT CAUSE #7 — clamp legacy extreme combat values.
            //
            // Pro fork (v5 ROLLBACK): the user asked to go back to the 'light' v2
            // build (CPS 12..16, item cooldown 0.85..1.0, MaxPerTick 1,
            // MultiTargetCooldown 1) — the last configuration that never froze.
            // v3 TURBO (20..25 CPS + zero cooldowns) and v4 MAX SPEED (35..45 CPS)
            // both brought the freeze back, so every known v3/v4/legacy fingerprint
            // is migrated down to the v2 values on load. Changing the code defaults
            // alone is never enough: the saved config is a full snapshot and
            // re-loads its old values on every game start.
            //
            // Pro fork (v4 FREEZE FIX, KEPT in v5): the freeze survived every
            // attack-rate fix, so packets were never the (only) cause. The real
            // movement-killers are features that take over movement the moment
            // KillAura has a target:
            //   - FightBot hijacks MovementInputEvent (auto-walk/jump = 'can't move')
            //   - AutoBlocking sword-blocks during combat (20% walk speed = 'stuck')
            //   - RotationTiming ON_TICK injects raw PosRot packets per attack,
            //     desyncing the movement packet pipeline (rubber-band)
            // All three remain force-migrated below.
            val valueElem = valueJson["value"]
            val savedRange = valueElem as? JsonObject
            val savedNumber = (valueElem as? JsonPrimitive)?.takeIf { it.isNumber }
            val savedString = (valueElem as? JsonPrimitive)?.takeIf { it.isString }?.asString
            val savedBool = (valueElem as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean
            when {
                // Clicker CPS — anything above the v2 curve is migrated DOWN to the
                // freeze-free 12..16: v3 (20..25), v4 (35..45), legacy extremes
                // (1500..2500 / 1500..7000). 12..16 itself is left untouched.
                valueName == "CPS" && savedRange != null -> {
                    val from = (savedRange["from"] as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt
                    val to = (savedRange["to"] as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt
                    if ((from != null && from > 16) || (to != null && to > 16)) {
                        savedRange.addProperty("from", 12)
                        savedRange.addProperty("to", 16)
                    }
                }

                // Clicker MaxPerTick — v2 default 1 (one attack per tick, like the
                // freeze-free 'light' build). v3 (2), v4 (3) and legacy (20) all
                // migrate down to 1.
                valueName == "MaxPerTick" && savedNumber != null && savedNumber.asInt > 1 -> {
                    valueJson.addProperty("value", 1)
                }

                // ItemCooldown minimum — only ItemCooldown uses a float RANGE named
                // "Minimum" (the AntiBot's 'Minimum' is a plain int, won't match an
                // object). v3/v4 (0..0 = spam 20%-damage hits) migrates back to the
                // v2 full-damage timing 0.85..1.0: fewer packets, full damage per
                // hit, and the movement pipeline stays clean.
                valueName == "Minimum" && savedRange != null -> {
                    val to = (savedRange["to"] as? JsonPrimitive)?.takeIf { it.isNumber }?.asFloat
                    if (to != null && to < 0.5f) {
                        savedRange.addProperty("from", 0.85f)
                        savedRange.addProperty("to", 1.0f)
                    }
                }

                // KillAura MultiTargetCooldown — v3/v4 (0 = hit every enemy every
                // tick) migrates back to the v2 default 1 (one tick between attacks
                // on the same enemy). 1 and above are left untouched.
                valueName == "MultiTargetCooldown" && savedNumber != null && savedNumber.asInt < 1 -> {
                    valueJson.addProperty("value", 1)
                }

                // v4 FREEZE FIX #1: RotationTiming ON_TICK injects two raw PosRot
                // packets per attack (rotate-to-target + rotate-back) that bypass
                // the movement packet pipeline. At high CPS this desyncs the
                // server-side position/rotation state = rubber-banding. SNAP does
                // the same job through the legit RotationManager pipeline.
                valueName == "RotationTiming" && savedString == "ON_TICK" -> {
                    valueJson.addProperty("value", "SNAP")
                }

                // v4 FREEZE FIX #2: FightBot & AutoBlocking take over movement the
                // moment KillAura has a target (auto-walk/jump input hijack and
                // 20%-speed sword-blocking). If a legacy config has either enabled,
                // force it off — they are the 'stuck, can't move' feel that survived
                // every attack-rate fix. They can be re-enabled in the ClickGUI.
                valueName == "FightBot" || valueName == "AutoBlocking" -> {
                    (valueJson["value"] as? JsonArray)?.forEach { element ->
                        val innerValue = element as? JsonObject ?: return@forEach
                        if (innerValue["name"]?.asString == "Enabled" &&
                            (innerValue["value"] as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean == true) {
                            innerValue.addProperty("value", false)
                        }
                    }
                }

                // v4 SPEED: AutoBlock reblock/pause ticks — any saved pause (> 0)
                // stalls attacks after unblocking. Migrated to 0..0 so blocking
                // (if re-enabled manually) never pauses the aura.
                valueGroup.name == "AutoBlocking" && savedRange != null && valueName in
                    listOf("Reblock", "TickOn", "PauseOnUnblock", "TickOff") -> {
                    val to = (savedRange["to"] as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt
                    if (to != null && to > 0) {
                        savedRange.addProperty("from", 0)
                        savedRange.addProperty("to", 0)
                    }
                }

                // v4 SPEED: MissCooldown — dropping clicks after a missed hit only
                // slows the aura down. Force off (alias 'AttackCooldown' in old
                // configs is handled by the alias-aware queue lookup above).
                valueName == "MissCooldown" && savedBool == true -> {
                    valueJson.addProperty("value", false)
                }

                // KillAura range values — gated on the 'Range' group so the Reach
                // module's 'Entity' group (same value names) is left untouched.
                valueGroup.name == "Range" && savedNumber != null && valueName in
                    listOf("RangeIncrease", "ThroughWallsRange") -> {
                    if (savedNumber.asFloat > 3.0f) {
                        valueJson.addProperty(
                            "value",
                            if (valueName == "RangeIncrease") 1.5f else 0.5f
                        )
                    }
                }

                valueName == "ScanRangeIncrease" && valueGroup.name == "Range" && savedRange != null -> {
                    val from = (savedRange["from"] as? JsonPrimitive)?.takeIf { it.isNumber }?.asFloat
                    if (from != null && from > 3.0f) {
                        savedRange.addProperty("from", 1.0f)
                        savedRange.addProperty("to", 2.0f)
                    }
                }
            }

            deserializeValue(value, valueJson)
        }
    }

    /**
     * Deserialize a value from a json object
     */
    fun deserializeValue(value: Value<*>, jsonObject: JsonObject) {
        // In the case of a config, we need to go deeper and deserialize the config itself
        if (value is ValueGroup) {
            runCatching {
                if (value is ModeValueGroup<*>) {
                    // Set current active choice
                    runCatching {
                        value.setByString(jsonObject["active"].asString)
                    }.onFailure {
                        logger.error("Unable to deserialize active choice for ${value.name}", it)
                    }

                    // Deserialize each choice
                    val choices = jsonObject["choices"].asJsonObject

                    for (choice in value.modes) {
                        runCatching {
                            val choiceElement = choices[choice.name]
                                // Alias support
                                ?: choice.aliases.firstNotNullOfOrNull { alias -> choices[alias] }
                                ?: error("Choice ${choice.name} not found")

                            deserializeValueGroup(choice, choiceElement)
                        }.onFailure {
                            logger.error("Unable to deserialize choice ${choice.name}", it)
                        }
                    }
                }

                // Deserialize the rest of the config
                deserializeValueGroup(value, jsonObject)
            }.onFailure {
                logger.error("Unable to deserialize config ${value.name}", it)
            }

            return
        }

        // Otherwise, we simply deserialize the value
        runCatching {
            value.deserializeFrom(fileGson, jsonObject["value"])
        }.onFailure {
            logger.error("Unable to deserialize value ${value.name}", it)
        }
    }

    private fun ensureRootKeys() {
        for (valueGroup in configs) {
            if (valueGroup.key == null) {
                valueGroup.walkKeyPath()
            }
        }
    }

    private fun normalizeKeyInput(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            return trimmed
        }
        val prefix = "$KEY_PREFIX."
        return if (trimmed.startsWith(prefix, ignoreCase = true)) {
            trimmed
        } else {
            prefix + trimmed
        }
    }

}
