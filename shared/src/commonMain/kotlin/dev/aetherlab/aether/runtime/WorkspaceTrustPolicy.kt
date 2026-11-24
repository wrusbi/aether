package dev.aetherlab.aether.runtime

/**
 * Decides whether Pi may load resources that live inside the agent's own
 * workspace (`<workspace>/.pi/skills`, `<workspace>/.pi/extensions`, project
 * prompts and project settings) while a chat turn is running.
 *
 * The workspace is the folder the agent can write to. Anything found there may
 * have been planted by the agent itself or by a repository it downloaded, so
 * chat sessions do not trust it. Skills and plugins the user has approved are
 * handed to Pi explicitly (`skill_paths`), which works regardless of this flag.
 *
 * Skill *discovery* for the Settings list still looks at the workspace
 * ([DiscoveryReadsWorkspace]); discovered Skills are imported disabled and wait
 * for the user to enable them.
 */
object WorkspaceTrustPolicy {
    /** Value of `workspace_trusted` sent with every chat turn / session request. */
    const val TrustWorkspaceForSessions: Boolean = false

    /** Value of `workspace_trusted` sent with `list_discovered_skills`. */
    const val DiscoveryReadsWorkspace: Boolean = true
}
