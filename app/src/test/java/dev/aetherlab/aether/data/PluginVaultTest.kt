package dev.aetherlab.aether.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginVaultTest {
    private val temporaryRoots = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryRoots.forEach(File::deleteRecursively)
    }

    @Test
    fun installPinsTheContentAndLeavesEverythingDisabled() = runBlocking {
        val fixture = Fixture()
        val staged = fixture.plugin("skills/alpha/SKILL.md" to "alpha v1")

        val installed = fixture.vault.install(staged, fixture.inspection(staged)).getOrThrow()

        assertEquals(PluginState.Pending, installed.state)
        assertEquals(listOf("plugin-demo-alpha"), installed.skillIds)
        assertEquals(PluginDigest.compute(staged), installed.digest)
        assertEquals(mapOf("plugin-demo-alpha" to false), fixture.skills.enabled)
        assertTrue(File(fixture.vault.directoryFor("demo"), "skills/alpha/SKILL.md").isFile)

        // The index survives a restart.
        val reopened = PluginVault(fixture.vaultRoot, fixture.skills)
        assertEquals(listOf("demo"), reopened.list().map { it.id })
        assertEquals(PluginState.Pending, reopened.list().single().state)
    }

    @Test
    fun approveEnablesTheSkillsOfAnUntouchedPlugin() = runBlocking {
        val fixture = Fixture()
        val staged = fixture.plugin("skills/alpha/SKILL.md" to "alpha")
        fixture.vault.install(staged, fixture.inspection(staged)).getOrThrow()

        val approved = fixture.vault.approve("demo").getOrThrow()

        assertEquals(PluginState.Approved, approved.state)
        assertEquals(mapOf("plugin-demo-alpha" to true), fixture.skills.enabled)
    }

    @Test
    fun tamperedFilesQuarantineThePluginAndSwitchItsSkillsOff() = runBlocking {
        val fixture = Fixture()
        val staged = fixture.plugin("skills/alpha/SKILL.md" to "alpha")
        fixture.vault.install(staged, fixture.inspection(staged)).getOrThrow()
        fixture.vault.approve("demo").getOrThrow()

        File(fixture.vault.directoryFor("demo"), "skills/alpha/SKILL.md").writeText("rewritten by someone else")
        val quarantined = fixture.vault.verifyAll()

        assertEquals(listOf("demo"), quarantined.map { it.id })
        assertEquals(PluginState.Quarantined, fixture.vault.list().single().state)
        assertEquals(mapOf("plugin-demo-alpha" to false), fixture.skills.enabled)
        assertTrue(fixture.vault.approve("demo").isFailure)
        // A second pass has nothing new to report.
        assertTrue(fixture.vault.verifyAll().isEmpty())
    }

    @Test
    fun approveRefusesAPluginThatChangedBeforeApproval() = runBlocking {
        val fixture = Fixture()
        val staged = fixture.plugin("skills/alpha/SKILL.md" to "alpha")
        fixture.vault.install(staged, fixture.inspection(staged)).getOrThrow()
        File(fixture.vault.directoryFor("demo"), "extra.txt").writeText("planted")

        assertTrue(fixture.vault.approve("demo").isFailure)

        assertEquals(PluginState.Quarantined, fixture.vault.list().single().state)
        assertEquals(mapOf("plugin-demo-alpha" to false), fixture.skills.enabled)
    }

    @Test
    fun installRejectsContentThatDiffersFromTheInspection() = runBlocking {
        val fixture = Fixture()
        val staged = fixture.plugin("skills/alpha/SKILL.md" to "alpha")
        val inspection = fixture.inspection(staged)
        File(staged, "skills/alpha/SKILL.md").writeText("swapped after inspection")

        val result = fixture.vault.install(staged, inspection)

        assertTrue(result.isFailure)
        assertTrue(fixture.vault.list().isEmpty())
        assertFalse(fixture.vault.directoryFor("demo").exists())
        assertTrue(fixture.skills.enabled.isEmpty())
    }

    @Test
    fun installRejectsFoldersWithSymbolicLinks() = runBlocking {
        val fixture = Fixture()
        val staged = fixture.plugin("skills/alpha/SKILL.md" to "alpha")
        val inspection = fixture.inspection(staged)
        val linked = runCatching {
            Files.createSymbolicLink(File(staged, "escape").toPath(), File("/tmp").toPath())
        }.isSuccess
        if (!linked) return@runBlocking

        assertTrue(fixture.vault.install(staged, inspection).isFailure)
        assertTrue(fixture.vault.list().isEmpty())
    }

    @Test
    fun skillPathsThatLeaveThePluginAreRefusedAndRolledBack() = runBlocking {
        val fixture = Fixture()
        val staged = fixture.plugin("skills/alpha/SKILL.md" to "alpha")
        val escaping = fixture.inspection(staged, skills = listOf(PluginSkillRef("alpha", "../alpha")))

        assertTrue(fixture.vault.install(staged, escaping).isFailure)
        assertTrue(fixture.vault.list().isEmpty())
        assertFalse(fixture.vault.directoryFor("demo").exists())
    }

    @Test
    fun skillNamesDifferingOnlyByCaseAreRefused() = runBlocking {
        val fixture = Fixture()
        val staged = fixture.plugin(
            "skills/Alpha/SKILL.md" to "upper",
            "skills/alpha/SKILL.md" to "lower",
        )
        val clashing = fixture.inspection(
            staged,
            skills = listOf(PluginSkillRef("Alpha", "skills/Alpha"), PluginSkillRef("alpha", "skills/alpha")),
        )

        assertTrue(fixture.vault.install(staged, clashing).isFailure)
        assertTrue(fixture.vault.list().isEmpty())
    }

    @Test
    fun reinstallReplacesContentDropsStaleSkillsAndAsksForApprovalAgain() = runBlocking {
        val fixture = Fixture()
        val first = fixture.plugin(
            "skills/alpha/SKILL.md" to "alpha v1",
            "skills/beta/SKILL.md" to "beta v1",
        )
        fixture.vault.install(
            first,
            fixture.inspection(
                first,
                skills = listOf(PluginSkillRef("alpha", "skills/alpha"), PluginSkillRef("beta", "skills/beta")),
            ),
        ).getOrThrow()
        fixture.vault.approve("demo").getOrThrow()

        val second = fixture.plugin("skills/alpha/SKILL.md" to "alpha v2")
        val updated = fixture.vault.install(second, fixture.inspection(second)).getOrThrow()

        assertEquals(PluginState.Pending, updated.state)
        assertEquals(listOf("plugin-demo-alpha"), updated.skillIds)
        assertEquals(setOf("plugin-demo-alpha"), fixture.skills.enabled.keys)
        assertEquals(false, fixture.skills.enabled["plugin-demo-alpha"])
        assertEquals(
            "alpha v2",
            File(fixture.vault.directoryFor("demo"), "skills/alpha/SKILL.md").readText(),
        )
        assertFalse(File(fixture.vault.directoryFor("demo"), "skills/beta").exists())
        assertEquals(1, fixture.vault.list().size)
    }

    @Test
    fun failedSkillInstallRollsTheWholePluginBack() = runBlocking {
        val fixture = Fixture()
        val staged = fixture.plugin("skills/alpha/SKILL.md" to "alpha")
        fixture.skills.failFor += "plugin-demo-alpha"

        assertTrue(fixture.vault.install(staged, fixture.inspection(staged)).isFailure)

        assertTrue(fixture.vault.list().isEmpty())
        assertFalse(fixture.vault.directoryFor("demo").exists())
    }

    @Test
    fun revokeSwitchesSkillsOffAndKeepsThePlugin() = runBlocking {
        val fixture = Fixture()
        val staged = fixture.plugin("skills/alpha/SKILL.md" to "alpha")
        fixture.vault.install(staged, fixture.inspection(staged)).getOrThrow()
        fixture.vault.approve("demo").getOrThrow()

        val revoked = fixture.vault.revoke("demo").getOrThrow()

        assertEquals(PluginState.Pending, revoked.state)
        assertEquals(mapOf("plugin-demo-alpha" to false), fixture.skills.enabled)
    }

    @Test
    fun removeDeletesFilesSkillsAndRecord() = runBlocking {
        val fixture = Fixture()
        val staged = fixture.plugin("skills/alpha/SKILL.md" to "alpha")
        fixture.vault.install(staged, fixture.inspection(staged)).getOrThrow()

        fixture.vault.remove("demo").getOrThrow()

        assertTrue(fixture.vault.list().isEmpty())
        assertFalse(fixture.vault.directoryFor("demo").exists())
        assertTrue(fixture.skills.enabled.isEmpty())
        assertTrue(fixture.vault.remove("demo").isFailure)
    }

    @Test
    fun unsafePluginIdsNeverTouchTheFileSystem() {
        val fixture = Fixture()

        assertTrue(runCatching { fixture.vault.directoryFor("../escape") }.isFailure)
        assertTrue(runCatching { fixture.vault.directoryFor("a/b") }.isFailure)
    }

    @Test
    fun parsesTheBridgeInspectionResponse() {
        val digest = "a".repeat(64)
        val json = JSONObject(
            """
            {
              "format": "claude-code",
              "name": "demo",
              "version": "1.0.0",
              "description": "Demo plugin",
              "skills": [{"name": "alpha", "path": "skills/alpha"}, {"name": "", "path": "skills/none"}],
              "commands": [{"name": "hello"}],
              "agents": [],
              "mcp_servers": [{"name": "local", "transport": "stdio"}],
              "capabilities": {"runs_hook_commands": true, "starts_mcp_processes": true},
              "warnings": ["careful"],
              "unsupported": ["Hooks are recognised but not executed yet."],
              "digest": "${digest.uppercase()}",
              "digest_algorithm": "tree-sha256-v1"
            }
            """.trimIndent(),
        )

        val parsed = parsePluginInspection(json)

        assertEquals("demo", parsed.name)
        assertEquals(listOf(PluginSkillRef("alpha", "skills/alpha")), parsed.skills)
        assertEquals(1, parsed.commandCount)
        assertEquals(0, parsed.agentCount)
        assertEquals(1, parsed.mcpServerCount)
        assertTrue(parsed.capabilities.runsHookCommands)
        assertTrue(parsed.capabilities.startsMcpProcesses)
        assertFalse(parsed.capabilities.changesAetherUi)
        assertEquals(digest, parsed.digest)
        assertEquals(listOf("careful"), parsed.warnings)
        assertTrue(runCatching { parsePluginInspection(JSONObject("""{"name":"x","digest":"short"}""")) }.isFailure)
        assertTrue(runCatching { parsePluginInspection(JSONObject("""{"digest":"$digest"}""")) }.isFailure)
    }

    @Test
    fun unknownStoredStateIsTreatedAsQuarantined() {
        assertEquals(PluginState.Quarantined, PluginState.fromStorage("something-new"))
        assertEquals(PluginState.Quarantined, PluginState.fromStorage(null))
        assertEquals(PluginState.Approved, PluginState.fromStorage("approved"))
        assertNull(PluginState.entries.firstOrNull { it.storageValue == "nope" })
    }

    private inner class Fixture {
        val vaultRoot: File = temporaryDirectory("vault")
        val skills = FakeSkillStore()
        private var tick = 0L
        val vault = PluginVault(vaultRoot, skills, clock = { ++tick })

        fun plugin(vararg files: Pair<String, String>): File {
            val root = temporaryDirectory("staged")
            File(root, ".claude-plugin").mkdirs()
            File(root, ".claude-plugin/plugin.json").writeText("""{"name":"demo"}""")
            files.forEach { (path, content) ->
                File(root, path).apply {
                    parentFile?.mkdirs()
                    writeText(content)
                }
            }
            return root
        }

        fun inspection(
            root: File,
            skills: List<PluginSkillRef> = listOf(PluginSkillRef("alpha", "skills/alpha")),
        ): PluginInspection = PluginInspection(
            format = "claude-code",
            name = "demo",
            version = "1.0.0",
            description = "Demo",
            skills = skills,
            commandCount = 0,
            agentCount = 0,
            mcpServerCount = 0,
            capabilities = PluginCapabilities(),
            warnings = emptyList(),
            unsupported = emptyList(),
            digest = requireNotNull(PluginDigest.compute(root)),
            digestAlgorithm = PluginDigest.Algorithm,
        )
    }

    private fun temporaryDirectory(prefix: String): File =
        Files.createTempDirectory("plugin-vault-$prefix").toFile().also(temporaryRoots::add)

    private class FakeSkillStore : PluginSkillStore {
        val enabled = mutableMapOf<String, Boolean>()
        val failFor = mutableSetOf<String>()

        override suspend fun installPluginSkill(
            skillRoot: File,
            pluginId: String,
            pluginName: String,
            skillName: String,
            subpath: String,
        ): Result<String> {
            val id = pluginSkillId(pluginId, skillName)
            if (id in failFor) return Result.failure(IllegalStateException("install failed"))
            if (!File(skillRoot, "SKILL.md").isFile) return Result.failure(IllegalStateException("no SKILL.md"))
            enabled[id] = false
            return Result.success(id)
        }

        override suspend fun installedSkillIds(): Set<String> = enabled.keys.toSet()

        override suspend fun setEnabled(skillId: String, enabled: Boolean) {
            this.enabled[skillId] = enabled
        }

        override suspend fun remove(skillId: String) {
            enabled.remove(skillId)
        }
    }
}
