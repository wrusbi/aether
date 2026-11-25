package dev.aetherlab.aether.data

public data class PluginDigest(
    public val id: String,
    public val name: String,
    public val version: String,
    public val description: String,
    public val sha256Full: String,
    public val sha256Prefix: String,
    public val isEnabled: Boolean,
    public val integrityError: String? = null
)

public data class PluginVaultState(
    public val digests: List<PluginDigest> = emptyList(),
    public val isWorkspaceTrusted: Boolean = false
)

public fun PluginVault.toVaultState(isWorkspaceTrusted: Boolean): PluginVaultState {
    val plugins = listPlugins()
    val digests = plugins.map { plugin ->
        PluginDigest(
            id = plugin.inspection.manifest.id,
            name = plugin.inspection.manifest.name,
            version = plugin.inspection.manifest.version,
            description = plugin.inspection.manifest.description,
            sha256Full = plugin.sha256Digest,
            sha256Prefix = plugin.sha256Digest.take(8),
            isEnabled = plugin.state == PluginState.ENABLED,
            integrityError = plugin.integrityError
        )
    }
    return PluginVaultState(
        digests = digests,
        isWorkspaceTrusted = isWorkspaceTrusted
    )
}

public fun PluginVault.setPluginEnabledState(
    pluginId: String,
    enabled: Boolean,
    isWorkspaceTrusted: Boolean
): Result<Unit> {
    val plugin = getPlugin(pluginId) ?: return Result.failure(IllegalArgumentException("Plugin $pluginId not found"))

    if (enabled) {
        if (!isWorkspaceTrusted) {
            return Result.failure(IllegalStateException("Cannot enable plugin in an untrusted workspace"))
        }
        if (plugin.integrityError != null) {
            return Result.failure(IllegalStateException("Cannot enable plugin with integrity failure: ${plugin.integrityError}"))
        }
        enablePlugin(pluginId)
    } else {
        disablePlugin(pluginId)
    }
    return Result.success(Unit)
}
