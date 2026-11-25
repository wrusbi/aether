package dev.aetherlab.aether.ui

public data class SlashCommandSuggestion(
    public val command: String,
    public val description: String,
    public val argsSyntax: String = "",
)

public object SlashCommandSuggestions {
    public val ALL: List<SlashCommandSuggestion> = listOf(
        SlashCommandSuggestion(
            command = "/help",
            description = "Show available slash commands and usage",
        ),
        SlashCommandSuggestion(
            command = "/clear",
            description = "Clear conversation history in current session",
        ),
        SlashCommandSuggestion(
            command = "/compact",
            description = "Compact session context to free up tokens",
        ),
        SlashCommandSuggestion(
            command = "/runtime",
            description = "Inspect or switch active runtime (alpine/termux)",
            argsSyntax = "[alpine|termux]",
        ),
        SlashCommandSuggestion(
            command = "/plugins",
            description = "List installed plugins, vault integrity, and trust status",
        ),
        SlashCommandSuggestion(
            command = "/plugin",
            description = "Manage plugin state (enable/disable/info/verify)",
            argsSyntax = "<enable|disable|info|verify> <plugin-id>",
        ),
    )

    public fun filter(query: String): List<SlashCommandSuggestion> {
        val trimmed = query.trim()
        if (!trimmed.startsWith("/")) return emptyList()
        val filterText = trimmed.removePrefix("/").lowercase()
        return ALL.filter { 
            it.command.removePrefix("/").lowercase().startsWith(filterText) ||
            it.command.lowercase().contains(filterText)
        }
    }
}