# Plugins and the Skill vault

Aether reads plugin folders written for **Claude Code**, **OpenAI Codex** and the
portable **agent plugin** layout. This page covers what exists today (Phase 0
and Phase 1). Commands, sub-agents, MCP servers, hooks and UI changes coming
from plugins are inspected and reported, but not activated yet.

## Supported layouts

| Layout       | Manifest                        | Components                                              |
| ------------ | ------------------------------- | ------------------------------------------------------- |
| Claude Code  | `.claude-plugin/plugin.json`    | `skills/`, `commands/`, `agents/`, `hooks/hooks.json`, `.mcp.json` |
| Codex        | `.codex-plugin/plugin.json`     | `skills/`, `.mcp.json`, `.app.json` (apps unsupported)  |
| Agent plugin | `plugin.json` in the root       | `skills/`, `mcp.json`                                   |

A plugin that should also change Aether's own UI keeps an ordinary `package.json`
next to the manifest and uses the existing `aether` / `pi` blocks documented in
`AETHER_EXTENSIONS.md`. Claude Code and Codex ignore that file.

## Inspecting a plugin

The bridge request `inspect_plugin` (`plugin_root` = folder inside the guest)
returns the plugin's name, version, Skills, commands, agents, MCP servers, hooks,
UI entries, a `capabilities` summary for the consent screen, and a content
`digest`. Nothing in the folder is executed. Paths that are absolute, climb out
of the folder or go through symbolic links are refused and reported as warnings.

## Content digest (`tree-sha256-v1`)

Every plugin is pinned to a digest: the SHA-256 over records
`<relative path>\n<size>\n<sha256 of content>\n` for every file, sorted by path.
Symbolic links make a folder ineligible. `pi-bridge/src/plugin-integrity.ts` and
`PluginDigest.kt` implement it and are tested against the same vector.

## Vault

`PluginVault` (Android) keeps plugins in `filesDir/plugins`, outside the Alpine
guest the agent works in.

* Installing pins the digest and installs the plugin's Skills **disabled**.
* `approve` re-checks the digest, then enables the Skills. Only the user can call it.
* `verifyAll` runs at startup. A plugin whose files changed is **quarantined**
  and its Skills are switched off until it is reinstalled.

## Skill safety rules (Phase 0)

* Skills discovered in folders the agent can write to arrive **disabled**, and
  an edited Skill needs a fresh approval.
* Chat sessions no longer trust the workspace: `<workspace>/.pi/skills`,
  `.pi/extensions` and project prompts do not load on their own
  (`WorkspaceTrustPolicy`). Approved Skills reach Pi through `skill_paths`.
* `aether_skill_manage`: Skills the agent installs start disabled; the agent
  cannot enable a Skill or remove an enabled one.
* The guest copy of mirrored Skills is compared byte-for-byte with the host copy
  on every sync; the signature file alone is no longer trusted.
