package dev.aetherlab.aether.data

import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What a plugin can do once it runs. Shown to the user before they approve it. */
data class PluginCapabilities(
    val runsHookCommands: Boolean = false,
    val startsMcpProcesses: Boolean = false,
    val usesRemoteMcp: Boolean = false,
    val changesAetherUi: Boolean = false,
    val loadsNativeCode: Boolean = false,
    val loadsPiExtensions: Boolean = false,
)

data class PluginSkillRef(
    val name: String,
    /** Folder of the skill relative to the plugin root, "/" separated. */
    val path: String,
)

/** Result of the bridge's `inspect_plugin` request. */
data class PluginInspection(
    val format: String,
    val name: String,
    val version: String,
    val description: String,
    val skills: List<PluginSkillRef>,
    val commandCount: Int,
    val agentCount: Int,
    val mcpServerCount: Int,
    val capabilities: PluginCapabilities,
    val warnings: List<String>,
    val unsupported: List<String>,
    val digest: String,
    val digestAlgorithm: String,
)

enum class PluginState(val storageValue: String) {
    /** Installed and pinned, but nothing of it runs until the user approves. */
    Pending("pending"),

    /** The user approved this exact content. */
    Approved("approved"),

    /** The stored content no longer matches its pinned digest. Reinstall to recover. */
    Quarantined("quarantined"),
    ;

    companion object {
        // Anything unknown is treated as the most restrictive state.
        fun fromStorage(value: String?): PluginState =
            entries.firstOrNull { it.storageValue == value } ?: Quarantined
    }
}

data class InstalledPlugin(
    val id: String,
    val name: String,
    val version: String,
    val format: String,
    val description: String,
    val digest: String,
    val state: PluginState,
    val installedAtMillis: Long,
    val updatedAtMillis: Long,
    val skillIds: List<String>,
    val capabilities: PluginCapabilities,
    val commandCount: Int,
    val agentCount: Int,
    val mcpServerCount: Int,
    val warnings: List<String>,
    val unsupported: List<String>,
)

/** The part of the Skill system the vault needs. Lets tests run without Android. */
interface PluginSkillStore {
    /** Installs one plugin Skill, disabled, and returns its Skill id. */
    suspend fun installPluginSkill(
        skillRoot: File,
        pluginId: String,
        pluginName: String,
        skillName: String,
        subpath: String,
    ): Result<String>

    suspend fun installedSkillIds(): Set<String>

    suspend fun setEnabled(skillId: String, enabled: Boolean)

    suspend fun remove(skillId: String)
}

class AgentPluginSkillStore(
    private val skillManager: AgentSkillManager,
    private val extensionsRepository: AgentExtensionsRepository,
) : PluginSkillStore {
    override suspend fun installPluginSkill(
        skillRoot: File,
        pluginId: String,
        pluginName: String,
        skillName: String,
        subpath: String,
    ): Result<String> = skillManager.installSkillFromPluginDirectory(
        skillRoot = skillRoot,
        skillId = pluginSkillId(pluginId, skillName),
        pluginId = pluginId,
        pluginName = pluginName,
        subpath = subpath,
    ).map { it.id }

    override suspend fun installedSkillIds(): Set<String> =
        extensionsRepository.extensionState.first().installedSkills.map { it.id }.toSet()

    override suspend fun setEnabled(skillId: String, enabled: Boolean) {
        extensionsRepository.setSkillEnabled(skillId, enabled)
    }

    override suspend fun remove(skillId: String) {
        skillManager.uninstallSkill(skillId).getOrThrow()
    }
}

internal fun pluginSkillId(pluginId: String, skillName: String): String =
    "plugin-$pluginId-$skillName".lowercase(Locale.ROOT)

internal fun pluginSkillSourceUri(pluginId: String): String = "plugin:$pluginId"

/**
 * Protected store for plugin folders.
 *
 * Plugins live in app-private storage that the agent (which only sees the
 * Alpine guest) cannot reach. Every plugin is pinned to a content digest when
 * it is installed and re-checked before approval and on demand; a plugin whose
 * bytes changed is quarantined and its Skills are switched off. Installing
 * never enables anything: the user approves a plugin explicitly.
 *
 * Phase 1 covers the plugin's Skills. Commands, sub-agents, MCP servers and UI
 * changes are described by the inspection and stay inert until later phases.
 */
class PluginVault(
    private val rootDirectory: File,
    private val skills: PluginSkillStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun list(): List<InstalledPlugin> = withContext(Dispatchers.IO) {
        mutex.withLock { readIndex() }
    }

    /**
     * Installs [stagedRoot], which must be exactly the folder that was inspected.
     * The plugin starts as [PluginState.Pending] with its Skills disabled.
     */
    suspend fun install(
        stagedRoot: File,
        inspection: PluginInspection,
    ): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { installLocked(stagedRoot, inspection) } }
    }

    /** Enables the plugin's Skills after confirming its content is still the pinned one. */
    suspend fun approve(pluginId: String): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val record = requireRecord(pluginId)
                check(record.state != PluginState.Quarantined) {
                    "This plugin was quarantined because its files changed. Reinstall it to use it."
                }
                if (!matchesPinnedDigest(record)) {
                    quarantineLocked(record)
                    error("The plugin's files no longer match what was installed. It has been quarantined.")
                }
                val known = skills.installedSkillIds()
                record.skillIds.filter { it in known }.forEach { skills.setEnabled(it, true) }
                val updated = record.copy(state = PluginState.Approved, updatedAtMillis = clock())
                writeIndex(readIndex().map { if (it.id == pluginId) updated else it })
                updated
            }
        }
    }

    /** Switches the plugin's Skills off and returns it to [PluginState.Pending]. */
    suspend fun revoke(pluginId: String): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val record = requireRecord(pluginId)
                disableSkills(record)
                val updated = record.copy(
                    state = if (record.state == PluginState.Quarantined) PluginState.Quarantined else PluginState.Pending,
                    updatedAtMillis = clock(),
                )
                writeIndex(readIndex().map { if (it.id == pluginId) updated else it })
                updated
            }
        }
    }

    /** Re-checks every plugin that is not quarantined. Returns the ones quarantined by this call. */
    suspend fun verifyAll(): List<InstalledPlugin> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readIndex()
                .filter { it.state != PluginState.Quarantined && !matchesPinnedDigest(it) }
                .map { quarantineLocked(it) }
        }
    }

    suspend fun remove(pluginId: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val record = requireRecord(pluginId)
                record.skillIds.forEach { runCatching { skills.remove(it) } }
                pluginDirectory(record.id).deleteRecursively()
                writeIndex(readIndex().filterNot { it.id == pluginId })
            }
        }
    }

    /** Directory that holds the plugin's files, for mirroring into the guest. */
    fun directoryFor(pluginId: String): File = pluginDirectory(pluginId)

    // ------------------------------------------------------------------ install

    private suspend fun installLocked(stagedRoot: File, inspection: PluginInspection): InstalledPlugin {
        require(inspection.digestAlgorithm == PluginDigest.Algorithm) { "Unsupported plugin digest algorithm." }
        require(PluginNamePattern.matches(inspection.name)) { "Invalid plugin name." }
        val pluginId = inspection.name.lowercase(Locale.ROOT)
        val skillIds = inspection.skills.map { pluginSkillId(pluginId, it.name) }
        require(skillIds.toSet().size == skillIds.size) { "Plugin has Skills whose names differ only by letter case." }
        inspection.skills.forEach { skill ->
            require(SkillNamePattern.matches(skill.name)) { "Invalid Skill name '${skill.name}'." }
        }

        val stagedDigest = PluginDigest.compute(stagedRoot)
            ?: error("Plugin folder may only contain plain files and folders.")
        require(stagedDigest == inspection.digest.lowercase(Locale.ROOT)) {
            "Plugin content changed after it was inspected."
        }

        rootDirectory.mkdirs()
        val target = pluginDirectory(pluginId)
        val incoming = File(rootDirectory, ".incoming-$pluginId-${UUID.randomUUID()}")
        try {
            check(stagedRoot.copyRecursively(incoming, overwrite = false)) { "Unable to copy plugin files." }
            check(PluginDigest.compute(incoming) == stagedDigest) { "Plugin copy does not match the inspected content." }
        } catch (throwable: Throwable) {
            incoming.deleteRecursively()
            throw throwable
        }

        val previous = readIndex().firstOrNull { it.id == pluginId }
        val backup = File(rootDirectory, ".previous-$pluginId-${UUID.randomUUID()}")
        if (target.exists()) {
            if (!target.renameTo(backup)) {
                incoming.deleteRecursively()
                error("Unable to replace the installed plugin.")
            }
        }
        if (!incoming.renameTo(target)) {
            incoming.deleteRecursively()
            if (backup.exists()) backup.renameTo(target)
            error("Unable to store the plugin.")
        }

        val installedSkillIds = mutableListOf<String>()
        try {
            inspection.skills.forEach { skill ->
                val skillRoot = insidePlugin(target, skill.path)
                val installedId = skills.installPluginSkill(
                    skillRoot = skillRoot,
                    pluginId = pluginId,
                    pluginName = inspection.name,
                    skillName = skill.name,
                    subpath = skill.path,
                ).getOrThrow()
                installedSkillIds += installedId
            }
        } catch (throwable: Throwable) {
            installedSkillIds.forEach { runCatching { skills.remove(it) } }
            target.deleteRecursively()
            if (backup.exists()) backup.renameTo(target)
            throw throwable
        }

        previous?.skillIds.orEmpty()
            .filterNot { it in installedSkillIds }
            .forEach { runCatching { skills.remove(it) } }
        backup.deleteRecursively()

        val now = clock()
        val record = InstalledPlugin(
            id = pluginId,
            name = inspection.name,
            version = inspection.version,
            format = inspection.format,
            description = inspection.description,
            digest = stagedDigest,
            state = PluginState.Pending,
            installedAtMillis = previous?.installedAtMillis ?: now,
            updatedAtMillis = now,
            skillIds = installedSkillIds.toList(),
            capabilities = inspection.capabilities,
            commandCount = inspection.commandCount,
            agentCount = inspection.agentCount,
            mcpServerCount = inspection.mcpServerCount,
            warnings = inspection.warnings,
            unsupported = inspection.unsupported,
        )
        writeIndex(readIndex().filterNot { it.id == pluginId } + record)
        return record
    }

    private fun insidePlugin(pluginRoot: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith("/") && !relativePath.contains("\\")) {
            "Invalid Skill path."
        }
        val base = pluginRoot.canonicalFile
        val candidate = File(base, relativePath).canonicalFile
        require(candidate.path.startsWith(base.path + File.separator)) { "Skill path leaves the plugin folder." }
        require(File(candidate, "SKILL.md").isFile) { "Skill folder has no SKILL.md." }
        return candidate
    }

    // ------------------------------------------------------------------ helpers

    private fun requireRecord(pluginId: String): InstalledPlugin =
        readIndex().firstOrNull { it.id == pluginId } ?: error("Plugin '$pluginId' is not installed.")

    private fun matchesPinnedDigest(record: InstalledPlugin): Boolean =
        PluginDigest.compute(pluginDirectory(record.id)) == record.digest

    private suspend fun disableSkills(record: InstalledPlugin) {
        val known = skills.installedSkillIds()
        record.skillIds.filter { it in known }.forEach { skills.setEnabled(it, false) }
    }

    private suspend fun quarantineLocked(record: InstalledPlugin): InstalledPlugin {
        disableSkills(record)
        val updated = record.copy(state = PluginState.Quarantined, updatedAtMillis = clock())
        writeIndex(readIndex().map { if (it.id == record.id) updated else it })
        return updated
    }

    private fun pluginDirectory(pluginId: String): File {
        require(PluginNamePattern.matches(pluginId)) { "Invalid plugin id." }
        return File(rootDirectory, pluginId)
    }

    private fun indexFile(): File = File(rootDirectory, IndexFileName)

    private fun readIndex(): List<InstalledPlugin> {
        val file = indexFile()
        if (!file.isFile) return emptyList()
        return runCatching {
            val plugins = JSONObject(file.readText()).optJSONArray("plugins") ?: return emptyList()
            (0 until plugins.length()).mapNotNull { index -> plugins.optJSONObject(index)?.let(::pluginFromJson) }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(plugins: List<InstalledPlugin>) {
        rootDirectory.mkdirs()
        val json = JSONObject()
            .put("version", 1)
            .put("plugins", JSONArray(plugins.sortedBy { it.id }.map(::pluginToJson)))
        val temporary = File(rootDirectory, "$IndexFileName.tmp")
        temporary.writeText(json.toString())
        val target = indexFile()
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private companion object {
        const val IndexFileName = "index.json"
        val PluginNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val SkillNamePattern = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

// ---------------------------------------------------------------------- JSON

/** Parses the bridge's `inspect_plugin` response. Throws [IllegalArgumentException] when it is unusable. */
fun parsePluginInspection(json: JSONObject): PluginInspection {
    val name = json.optString("name").trim()
    require(name.isNotEmpty()) { "Inspection has no plugin name." }
    val digest = json.optString("digest").trim().lowercase(Locale.ROOT)
    require(digest.matches(Regex("[0-9a-f]{64}"))) { "Inspection has no valid digest." }
    val skills = json.optJSONArray("skills")?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { entry ->
                val skillName = entry.optString("name").trim()
                val skillPath = entry.optString("path").trim()
                if (skillName.isEmpty() || skillPath.isEmpty()) null else PluginSkillRef(skillName, skillPath)
            }
        }
    }.orEmpty()
    val capabilities = json.optJSONObject("capabilities")
    return PluginInspection(
        format = json.optString("format"),
        name = name,
        version = json.optString("version"),
        description = json.optString("description"),
        skills = skills,
        commandCount = json.optJSONArray("commands")?.length() ?: 0,
        agentCount = json.optJSONArray("agents")?.length() ?: 0,
        mcpServerCount = json.optJSONArray("mcp_servers")?.length() ?: 0,
        capabilities = PluginCapabilities(
            runsHookCommands = capabilities?.optBoolean("runs_hook_commands") ?: false,
            startsMcpProcesses = capabilities?.optBoolean("starts_mcp_processes") ?: false,
            usesRemoteMcp = capabilities?.optBoolean("uses_remote_mcp") ?: false,
            changesAetherUi = capabilities?.optBoolean("changes_aether_ui") ?: false,
            loadsNativeCode = capabilities?.optBoolean("loads_native_code") ?: false,
            loadsPiExtensions = capabilities?.optBoolean("loads_pi_extensions") ?: false,
        ),
        warnings = json.optJSONArray("warnings").toStringList(),
        unsupported = json.optJSONArray("unsupported").toStringList(),
        digest = digest,
        digestAlgorithm = json.optString("digest_algorithm"),
    )
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotEmpty() } }
}

private fun pluginToJson(plugin: InstalledPlugin): JSONObject = JSONObject()
    .put("id", plugin.id)
    .put("name", plugin.name)
    .put("version", plugin.version)
    .put("format", plugin.format)
    .put("description", plugin.description)
    .put("digest", plugin.digest)
    .put("state", plugin.state.storageValue)
    .put("installedAtMillis", plugin.installedAtMillis)
    .put("updatedAtMillis", plugin.updatedAtMillis)
    .put("skillIds", JSONArray(plugin.skillIds))
    .put(
        "capabilities",
        JSONObject()
            .put("runsHookCommands", plugin.capabilities.runsHookCommands)
            .put("startsMcpProcesses", plugin.capabilities.startsMcpProcesses)
            .put("usesRemoteMcp", plugin.capabilities.usesRemoteMcp)
            .put("changesAetherUi", plugin.capabilities.changesAetherUi)
            .put("loadsNativeCode", plugin.capabilities.loadsNativeCode)
            .put("loadsPiExtensions", plugin.capabilities.loadsPiExtensions),
    )
    .put("commandCount", plugin.commandCount)
    .put("agentCount", plugin.agentCount)
    .put("mcpServerCount", plugin.mcpServerCount)
    .put("warnings", JSONArray(plugin.warnings))
    .put("unsupported", JSONArray(plugin.unsupported))

private fun pluginFromJson(json: JSONObject): InstalledPlugin? {
    val id = json.optString("id")
    val digest = json.optString("digest")
    if (id.isBlank() || digest.isBlank()) return null
    val capabilities = json.optJSONObject("capabilities")
    return InstalledPlugin(
        id = id,
        name = json.optString("name", id),
        version = json.optString("version"),
        format = json.optString("format"),
        description = json.optString("description"),
        digest = digest,
        state = PluginState.fromStorage(json.optString("state")),
        installedAtMillis = json.optLong("installedAtMillis"),
        updatedAtMillis = json.optLong("updatedAtMillis"),
        skillIds = json.optJSONArray("skillIds").toStringList(),
        capabilities = PluginCapabilities(
            runsHookCommands = capabilities?.optBoolean("runsHookCommands") ?: false,
            startsMcpProcesses = capabilities?.optBoolean("startsMcpProcesses") ?: false,
            usesRemoteMcp = capabilities?.optBoolean("usesRemoteMcp") ?: false,
            changesAetherUi = capabilities?.optBoolean("changesAetherUi") ?: false,
            loadsNativeCode = capabilities?.optBoolean("loadsNativeCode") ?: false,
            loadsPiExtensions = capabilities?.optBoolean("loadsPiExtensions") ?: false,
        ),
        commandCount = json.optInt("commandCount"),
        agentCount = json.optInt("agentCount"),
        mcpServerCount = json.optInt("mcpServerCount"),
        warnings = json.optJSONArray("warnings").toStringList(),
        unsupported = json.optJSONArray("unsupported").toStringList(),
    )
}
