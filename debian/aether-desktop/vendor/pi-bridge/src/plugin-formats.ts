// Reads plugin folders written for Claude Code, OpenAI Codex or the portable
// "agent plugin" layout and describes what is inside, without running anything.
//
//   Claude Code   .claude-plugin/plugin.json  + skills/ commands/ agents/ hooks/hooks.json .mcp.json
//   Codex         .codex-plugin/plugin.json   + skills/ .mcp.json .app.json
//   Agent plugin  plugin.json (root)          + skills/ mcp.json
//
// Aether-specific UI changes are NOT part of those formats. A plugin folder may
// carry an ordinary `package.json` with the existing `aether` / `pi` blocks;
// this module only reports them so the host can ask for consent.
//
// The module is self-contained (node builtins only) and everything it reads is
// treated as untrusted data.

import * as fs from "node:fs";
import * as path from "node:path";

export type PluginFlavor = "claude-code" | "codex" | "agent-plugin";

export class PluginFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PluginFormatError";
  }
}

export interface PluginSkillEntry {
  name: string;
  /** Folder of the skill, relative to the plugin root, "/" separated. */
  path: string;
  description?: string;
}

export interface PluginMarkdownEntry {
  name: string;
  /** File path relative to the plugin root, "/" separated. */
  path: string;
  description?: string;
}

export interface PluginMcpServerEntry {
  name: string;
  transport: "stdio" | "http" | "sse" | "unknown";
  /** Command line the server would start (stdio only), shortened for display. */
  command?: string;
  url?: string;
}

export interface PluginHooksSummary {
  source: string;
  events: string[];
  commandCount: number;
}

export interface PluginUiSummary {
  packageJsonPath: string;
  /** Aether Script Mod entry files (`aether.extensions`). */
  scriptExtensions: string[];
  /** Aether Native Mod entry classes (`aether.native.entrypoints`). */
  nativeEntrypoints: string[];
  /** Pi extension entry files (`pi.extensions`). */
  piExtensions: string[];
  hasNativeClasspath: boolean;
}

export interface PluginCapabilities {
  runsHookCommands: boolean;
  startsMcpProcesses: boolean;
  usesRemoteMcp: boolean;
  changesAetherUi: boolean;
  loadsNativeCode: boolean;
  loadsPiExtensions: boolean;
}

export interface NormalizedPlugin {
  flavor: PluginFlavor;
  manifestPath: string;
  alsoPresent: PluginFlavor[];
  name: string;
  version?: string;
  description?: string;
  author?: string;
  homepage?: string;
  license?: string;
  skills: PluginSkillEntry[];
  commands: PluginMarkdownEntry[];
  agents: PluginMarkdownEntry[];
  mcpServers: PluginMcpServerEntry[];
  hooks?: PluginHooksSummary;
  ui?: PluginUiSummary;
  capabilities: PluginCapabilities;
  warnings: string[];
  unsupported: string[];
}

type JsonObject = Record<string, unknown>;

interface InspectContext {
  root: string;
  rootReal: string;
  warnings: string[];
  unsupported: string[];
}

const MAX_JSON_BYTES = 512 * 1024;
const MAX_ENTRIES_PER_KIND = 512;
const MAX_WALK_DEPTH = 4;
const MAX_NAME_LENGTH = 64;
const MAX_TEXT_LENGTH = 300;
const MAX_COMMAND_LENGTH = 200;
const FRONTMATTER_READ_BYTES = 16 * 1024;
const PLUGIN_NAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;
const SKILL_NAME_PATTERN = /^[A-Za-z0-9._-]+$/;
const UNSUPPORTED_CLAUDE_MANIFEST_KEYS = ["lspServers", "outputStyles"];

const MANIFESTS: Array<{ flavor: PluginFlavor; file: string }> = [
  { flavor: "claude-code", file: ".claude-plugin/plugin.json" },
  { flavor: "codex", file: ".codex-plugin/plugin.json" },
  { flavor: "agent-plugin", file: "plugin.json" },
];

function isObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function shortText(value: unknown, limit = MAX_TEXT_LENGTH): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.replace(/\s+/g, " ").trim();
  if (trimmed === "") return undefined;
  return trimmed.length > limit ? `${trimmed.slice(0, limit - 1)}…` : trimmed;
}

function lstatOrUndefined(target: string): fs.Stats | undefined {
  try {
    return fs.lstatSync(target);
  } catch {
    return undefined;
  }
}

function isRegularFile(target: string): boolean {
  return lstatOrUndefined(target)?.isFile() === true;
}

function isRealDirectory(target: string): boolean {
  return lstatOrUndefined(target)?.isDirectory() === true;
}

function readJsonObject(file: string, label: string): JsonObject {
  const stat = lstatOrUndefined(file);
  if (!stat || !stat.isFile()) throw new PluginFormatError(`${label} is missing.`);
  if (stat.size > MAX_JSON_BYTES) throw new PluginFormatError(`${label} is larger than 512 KB.`);
  let text = fs.readFileSync(file, "utf8");
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    throw new PluginFormatError(`${label} is not valid JSON: ${detail}`);
  }
  if (!isObject(parsed)) throw new PluginFormatError(`${label} must contain a JSON object.`);
  return parsed;
}

/**
 * Turns a path written in a manifest into a normalized path inside the plugin.
 * Returns undefined (and records a warning) for anything that is absolute,
 * climbs out with "..", or leaves the plugin through a symbolic link.
 */
function resolveInside(
  context: InspectContext,
  raw: unknown,
  label: string,
): { absolute: string; relative: string } | undefined {
  if (typeof raw !== "string" || raw.trim() === "" || raw.includes("\0")) {
    context.warnings.push(`${label}: path must be a non-empty string.`);
    return undefined;
  }
  const slashed = raw.trim().replace(/\\/g, "/");
  if (slashed.startsWith("/") || /^[A-Za-z]:/.test(slashed)) {
    context.warnings.push(`${label}: absolute paths are not allowed (${shortText(raw, 80)}).`);
    return undefined;
  }
  const normalized = path.posix.normalize(slashed);
  if (normalized === ".." || normalized.startsWith("../")) {
    context.warnings.push(`${label}: path leaves the plugin folder (${shortText(raw, 80)}).`);
    return undefined;
  }
  const relative = normalized === "." ? "" : normalized.replace(/\/+$/, "");
  const absolute = relative === "" ? context.root : path.join(context.root, ...relative.split("/"));
  if (lstatOrUndefined(absolute)) {
    let real: string;
    try {
      real = fs.realpathSync(absolute);
    } catch {
      context.warnings.push(`${label}: path cannot be resolved (${shortText(raw, 80)}).`);
      return undefined;
    }
    if (real !== context.rootReal && !real.startsWith(context.rootReal + path.sep)) {
      context.warnings.push(`${label}: path escapes the plugin folder (${shortText(raw, 80)}).`);
      return undefined;
    }
  }
  return { absolute, relative };
}

function stringList(value: unknown): unknown[] {
  if (value === undefined || value === null) return [];
  return Array.isArray(value) ? value : [value];
}

function uniqueStrings(values: string[]): string[] {
  return values.filter((entry, index) => values.indexOf(entry) === index);
}

/** Reads `name` / `description` from simple YAML front matter. */
function readFrontMatter(file: string): { name?: string; description?: string } {
  let text: string;
  try {
    const descriptor = fs.openSync(file, "r");
    try {
      const buffer = Buffer.alloc(FRONTMATTER_READ_BYTES);
      const read = fs.readSync(descriptor, buffer, 0, FRONTMATTER_READ_BYTES, 0);
      text = buffer.subarray(0, read).toString("utf8");
    } finally {
      fs.closeSync(descriptor);
    }
  } catch {
    return {};
  }
  if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
  const lines = text.split(/\r?\n/);
  if (lines[0]?.trim() !== "---") return {};
  const result: { name?: string; description?: string } = {};
  for (let index = 1; index < lines.length; index += 1) {
    const line = lines[index];
    if (line.trim() === "---") break;
    const match = /^(name|description)\s*:\s*(.*)$/.exec(line);
    if (!match) continue;
    let value = match[2].trim();
    if (value === "" || /^[>|][+-]?$/.test(value)) continue;
    if (
      value.length >= 2 &&
      ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))
    ) {
      value = value.slice(1, -1);
    }
    if (match[1] === "name") result.name = shortText(value, MAX_NAME_LENGTH);
    else result.description = shortText(value);
  }
  return result;
}

function collectSkills(context: InspectContext, extraDirectories: unknown[]): PluginSkillEntry[] {
  const directories = uniqueStrings([
    "skills",
    ...extraDirectories.flatMap((entry) => {
      const resolved = resolveInside(context, entry, "skills");
      return resolved ? [resolved.relative] : [];
    }),
  ]);
  const skills: PluginSkillEntry[] = [];
  const seen = new Set<string>();
  const add = (skillPath: string, skillName: string, skillFile: string): void => {
    if (skills.length >= MAX_ENTRIES_PER_KIND || seen.has(skillPath)) return;
    if (!SKILL_NAME_PATTERN.test(skillName) || skillName.length > MAX_NAME_LENGTH) {
      context.warnings.push(`Skipped skill folder with unsupported name: ${shortText(skillName, 80)}.`);
      return;
    }
    seen.add(skillPath);
    skills.push({ name: skillName, path: skillPath, description: readFrontMatter(skillFile).description });
  };

  for (const directory of directories) {
    const absolute = directory === "" ? context.root : path.join(context.root, ...directory.split("/"));
    if (!isRealDirectory(absolute)) continue;
    if (directory !== "" && directory !== "skills" && isRegularFile(path.join(absolute, "SKILL.md"))) {
      add(directory, path.posix.basename(directory), path.join(absolute, "SKILL.md"));
      continue;
    }
    for (const entry of fs.readdirSync(absolute, { withFileTypes: true })) {
      if (entry.isSymbolicLink()) {
        context.warnings.push(`Ignored symbolic link in skills: ${shortText(entry.name, 80)}.`);
        continue;
      }
      if (!entry.isDirectory()) continue;
      const skillFile = path.join(absolute, entry.name, "SKILL.md");
      if (!isRegularFile(skillFile)) continue;
      add(directory === "" ? entry.name : `${directory}/${entry.name}`, entry.name, skillFile);
    }
  }
  skills.sort((left, right) => (left.path < right.path ? -1 : left.path > right.path ? 1 : 0));
  return skills;
}

function walkMarkdown(
  context: InspectContext,
  baseAbsolute: string,
  baseRelative: string,
  directory: string,
  depth: number,
  out: PluginMarkdownEntry[],
): void {
  if (depth > MAX_WALK_DEPTH || out.length >= MAX_ENTRIES_PER_KIND) return;
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (out.length >= MAX_ENTRIES_PER_KIND) return;
    if (entry.isSymbolicLink()) {
      context.warnings.push(`Ignored symbolic link: ${shortText(entry.name, 80)}.`);
      continue;
    }
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      walkMarkdown(context, baseAbsolute, baseRelative, absolute, depth + 1, out);
    } else if (entry.isFile() && entry.name.toLowerCase().endsWith(".md")) {
      const inner = path.relative(baseAbsolute, absolute).split(path.sep).join("/");
      const relativePath = baseRelative === "" ? inner : `${baseRelative}/${inner}`;
      const name = inner.replace(/\.md$/i, "").split("/").join(":");
      out.push({ name, path: relativePath, description: readFrontMatter(absolute).description });
    }
  }
}

function collectMarkdown(
  context: InspectContext,
  defaultDirectory: string,
  extra: unknown[],
  label: string,
): PluginMarkdownEntry[] {
  const locations = uniqueStrings([
    defaultDirectory,
    ...extra.flatMap((entry) => {
      const resolved = resolveInside(context, entry, label);
      return resolved ? [resolved.relative] : [];
    }),
  ]);
  const entries: PluginMarkdownEntry[] = [];
  const seen = new Set<string>();
  for (const location of locations) {
    const absolute = location === "" ? context.root : path.join(context.root, ...location.split("/"));
    const stat = lstatOrUndefined(absolute);
    if (!stat) continue;
    const collected: PluginMarkdownEntry[] = [];
    if (stat.isDirectory()) {
      walkMarkdown(context, absolute, location, absolute, 0, collected);
    } else if (stat.isFile() && location.toLowerCase().endsWith(".md")) {
      const base = path.posix.basename(location).replace(/\.md$/i, "");
      collected.push({ name: base, path: location, description: readFrontMatter(absolute).description });
    }
    for (const entry of collected) {
      if (entries.length >= MAX_ENTRIES_PER_KIND) break;
      if (seen.has(entry.path)) continue;
      seen.add(entry.path);
      entries.push(entry);
    }
  }
  entries.sort((left, right) => (left.path < right.path ? -1 : left.path > right.path ? 1 : 0));
  return entries;
}

function describeMcpServer(name: string, raw: unknown): PluginMcpServerEntry {
  const server = isObject(raw) ? raw : {};
  const url = shortText(server.url, MAX_COMMAND_LENGTH);
  const declared = typeof server.type === "string" ? server.type : typeof server.transport === "string" ? server.transport : "";
  if (typeof server.command === "string" && server.command.trim() !== "") {
    const args = Array.isArray(server.args) ? server.args.filter((arg) => typeof arg === "string") : [];
    return {
      name,
      transport: "stdio",
      command: shortText([server.command, ...args].join(" "), MAX_COMMAND_LENGTH),
    };
  }
  if (url !== undefined) {
    return { name, transport: declared.toLowerCase() === "sse" ? "sse" : "http", url };
  }
  return { name, transport: "unknown" };
}

function readMcpSource(context: InspectContext, source: unknown, label: string): PluginMcpServerEntry[] {
  let config: JsonObject | undefined;
  if (typeof source === "string") {
    const resolved = resolveInside(context, source, label);
    if (!resolved || !isRegularFile(resolved.absolute)) return [];
    try {
      config = readJsonObject(resolved.absolute, resolved.relative);
    } catch (error) {
      context.warnings.push(error instanceof Error ? error.message : String(error));
      return [];
    }
  } else if (isObject(source)) {
    config = source;
  } else {
    return [];
  }
  const servers = isObject(config.mcpServers)
    ? config.mcpServers
    : Object.fromEntries(Object.entries(config).filter(([, value]) => isObject(value)));
  return Object.entries(servers)
    .slice(0, MAX_ENTRIES_PER_KIND)
    .map(([name, value]) => describeMcpServer(shortText(name, MAX_NAME_LENGTH) ?? "server", value));
}

function collectMcpServers(context: InspectContext, manifest: JsonObject, flavor: PluginFlavor): PluginMcpServerEntry[] {
  const sources: unknown[] = stringList(manifest.mcpServers);
  const defaults = flavor === "agent-plugin" ? ["mcp.json", ".mcp.json"] : [".mcp.json"];
  for (const candidate of defaults) {
    if (isRegularFile(path.join(context.root, candidate))) sources.push(candidate);
  }
  const servers: PluginMcpServerEntry[] = [];
  const seen = new Set<string>();
  const handled = new Set<string>();
  for (const source of sources) {
    const key = typeof source === "string" ? path.posix.normalize(source.replace(/\\/g, "/")).replace(/^\.\//, "") : "";
    if (key !== "") {
      if (handled.has(key)) continue;
      handled.add(key);
    }
    for (const server of readMcpSource(context, source, "mcpServers")) {
      if (seen.has(server.name)) continue;
      seen.add(server.name);
      servers.push(server);
    }
  }
  return servers;
}

function summarizeHooksConfig(config: JsonObject, source: string): PluginHooksSummary {
  const table = isObject(config.hooks) ? config.hooks : config;
  const events: string[] = [];
  let commandCount = 0;
  for (const [event, groups] of Object.entries(table)) {
    if (!Array.isArray(groups)) continue;
    events.push(event);
    for (const group of groups) {
      if (!isObject(group)) continue;
      const hooks = Array.isArray(group.hooks) ? group.hooks : [group];
      for (const hook of hooks) {
        if (isObject(hook) && (hook.type === "command" || typeof hook.command === "string")) {
          commandCount += 1;
        }
      }
    }
  }
  return { source, events, commandCount };
}

function collectHooks(context: InspectContext, manifest: JsonObject): PluginHooksSummary | undefined {
  const sources: unknown[] = stringList(manifest.hooks);
  if (isRegularFile(path.join(context.root, "hooks", "hooks.json"))) sources.push("hooks/hooks.json");
  let summary: PluginHooksSummary | undefined;
  const handled = new Set<string>();
  for (const source of sources) {
    let config: JsonObject | undefined;
    let label = "manifest";
    if (typeof source === "string") {
      const resolved = resolveInside(context, source, "hooks");
      if (!resolved || handled.has(resolved.relative) || !isRegularFile(resolved.absolute)) continue;
      handled.add(resolved.relative);
      label = resolved.relative;
      try {
        config = readJsonObject(resolved.absolute, resolved.relative);
      } catch (error) {
        context.warnings.push(error instanceof Error ? error.message : String(error));
        continue;
      }
    } else if (isObject(source)) {
      config = source;
    } else {
      continue;
    }
    const next = summarizeHooksConfig(config, label);
    summary = summary
      ? {
          source: `${summary.source}, ${next.source}`,
          events: uniqueStrings([...summary.events, ...next.events]),
          commandCount: summary.commandCount + next.commandCount,
        }
      : next;
  }
  return summary;
}

function entryPaths(context: InspectContext, value: unknown, label: string): string[] {
  return stringList(value).flatMap((entry) => {
    const resolved = resolveInside(context, entry, label);
    return resolved && resolved.relative !== "" ? [resolved.relative] : [];
  });
}

function collectUi(context: InspectContext): PluginUiSummary | undefined {
  const packageFile = path.join(context.root, "package.json");
  if (!isRegularFile(packageFile)) return undefined;
  let manifest: JsonObject;
  try {
    manifest = readJsonObject(packageFile, "package.json");
  } catch (error) {
    context.warnings.push(error instanceof Error ? error.message : String(error));
    return undefined;
  }
  const aether = isObject(manifest.aether) ? manifest.aether : {};
  const native = isObject(aether.native) ? aether.native : {};
  const pi = isObject(manifest.pi) ? manifest.pi : {};
  const scriptExtensions = entryPaths(context, aether.extensions, "aether.extensions");
  const piExtensions = entryPaths(context, pi.extensions, "pi.extensions");
  const nativeEntrypoints = stringList(native.entrypoints)
    .filter((entry): entry is string => typeof entry === "string" && /^[A-Za-z_$][\w$.]*$/.test(entry))
    .slice(0, MAX_ENTRIES_PER_KIND);
  const hasNativeClasspath = stringList(native.classpath).length > 0 || stringList(native.libraryPath).length > 0;
  if (
    scriptExtensions.length === 0 &&
    piExtensions.length === 0 &&
    nativeEntrypoints.length === 0 &&
    !hasNativeClasspath
  ) {
    return undefined;
  }
  return { packageJsonPath: "package.json", scriptExtensions, nativeEntrypoints, piExtensions, hasNativeClasspath };
}

function readAuthor(value: unknown): string | undefined {
  if (typeof value === "string") return shortText(value, 120);
  if (isObject(value)) return shortText(value.name, 120);
  return undefined;
}

function detectManifests(root: string): Array<{ flavor: PluginFlavor; file: string }> {
  return MANIFESTS.filter((candidate) => isRegularFile(path.join(root, ...candidate.file.split("/"))));
}

/** Describes the plugin stored in [root]. Throws [PluginFormatError] when it is unusable. */
export function inspectPlugin(root: string): NormalizedPlugin {
  const rootStat = lstatOrUndefined(root);
  if (!rootStat || rootStat.isSymbolicLink() || !rootStat.isDirectory()) {
    throw new PluginFormatError("Plugin path must be an existing folder.");
  }
  const found = detectManifests(root);
  if (found.length === 0) {
    throw new PluginFormatError(
      "No plugin manifest found. Expected .claude-plugin/plugin.json, .codex-plugin/plugin.json or plugin.json.",
    );
  }
  const primary = found[0];
  const context: InspectContext = {
    root,
    rootReal: fs.realpathSync(root),
    warnings: [],
    unsupported: [],
  };
  const manifest = readJsonObject(path.join(root, ...primary.file.split("/")), primary.file);

  const name = typeof manifest.name === "string" ? manifest.name.trim() : "";
  if (name === "") throw new PluginFormatError(`${primary.file} must define a "name".`);
  if (name.length > MAX_NAME_LENGTH || !PLUGIN_NAME_PATTERN.test(name)) {
    throw new PluginFormatError(
      `Plugin name "${shortText(name, 80)}" is not valid. Use letters, digits, ".", "_" or "-" (up to ${MAX_NAME_LENGTH}).`,
    );
  }

  const skills = collectSkills(context, stringList(manifest.skills));
  const commands = collectMarkdown(context, "commands", stringList(manifest.commands), "commands");
  const agents = collectMarkdown(context, "agents", stringList(manifest.agents), "agents");
  const mcpServers = collectMcpServers(context, manifest, primary.flavor);
  const hooks = collectHooks(context, manifest);
  const ui = collectUi(context);

  if (found.length > 1) {
    context.warnings.push(
      `Several manifests found; using ${primary.file}. Also present: ${found.slice(1).map((entry) => entry.file).join(", ")}.`,
    );
  }
  if (hooks) {
    context.unsupported.push("Hooks are recognised but not executed yet.");
  }
  if (primary.flavor === "codex" && (manifest.apps !== undefined || isRegularFile(path.join(root, ".app.json")))) {
    context.unsupported.push("Codex apps (.app.json) are not supported.");
  }
  if (primary.flavor === "claude-code") {
    for (const key of UNSUPPORTED_CLAUDE_MANIFEST_KEYS) {
      if (manifest[key] !== undefined) context.unsupported.push(`"${key}" is not supported.`);
    }
  }

  const startsMcpProcesses = mcpServers.some((server) => server.transport === "stdio");
  return {
    flavor: primary.flavor,
    manifestPath: primary.file,
    alsoPresent: found.slice(1).map((entry) => entry.flavor),
    name,
    version: shortText(manifest.version, 64),
    description: shortText(manifest.description),
    author: readAuthor(manifest.author),
    homepage: shortText(manifest.homepage, 200),
    license: shortText(manifest.license, 64),
    skills,
    commands,
    agents,
    mcpServers,
    hooks,
    ui,
    capabilities: {
      runsHookCommands: (hooks?.commandCount ?? 0) > 0,
      startsMcpProcesses,
      usesRemoteMcp: mcpServers.some((server) => server.transport === "http" || server.transport === "sse"),
      changesAetherUi: (ui?.scriptExtensions.length ?? 0) > 0 || (ui?.nativeEntrypoints.length ?? 0) > 0,
      loadsNativeCode: (ui?.nativeEntrypoints.length ?? 0) > 0 || ui?.hasNativeClasspath === true,
      loadsPiExtensions: (ui?.piExtensions.length ?? 0) > 0,
    },
    warnings: context.warnings,
    unsupported: context.unsupported,
  };
}

/** Bridge wire format: snake_case keys, undefined values dropped. */
export function pluginInspectionToJson(plugin: NormalizedPlugin): JsonObject {
  const json: JsonObject = {
    format: plugin.flavor,
    manifest_path: plugin.manifestPath,
    also_present: plugin.alsoPresent,
    name: plugin.name,
    version: plugin.version,
    description: plugin.description,
    author: plugin.author,
    homepage: plugin.homepage,
    license: plugin.license,
    skills: plugin.skills.map((skill) => ({
      name: skill.name,
      path: skill.path,
      description: skill.description,
    })),
    commands: plugin.commands.map((entry) => ({ name: entry.name, path: entry.path, description: entry.description })),
    agents: plugin.agents.map((entry) => ({ name: entry.name, path: entry.path, description: entry.description })),
    mcp_servers: plugin.mcpServers.map((server) => ({
      name: server.name,
      transport: server.transport,
      command: server.command,
      url: server.url,
    })),
    hooks: plugin.hooks
      ? { source: plugin.hooks.source, events: plugin.hooks.events, command_count: plugin.hooks.commandCount }
      : undefined,
    ui: plugin.ui
      ? {
          package_json_path: plugin.ui.packageJsonPath,
          script_extensions: plugin.ui.scriptExtensions,
          native_entrypoints: plugin.ui.nativeEntrypoints,
          pi_extensions: plugin.ui.piExtensions,
          has_native_classpath: plugin.ui.hasNativeClasspath,
        }
      : undefined,
    capabilities: {
      runs_hook_commands: plugin.capabilities.runsHookCommands,
      starts_mcp_processes: plugin.capabilities.startsMcpProcesses,
      uses_remote_mcp: plugin.capabilities.usesRemoteMcp,
      changes_aether_ui: plugin.capabilities.changesAetherUi,
      loads_native_code: plugin.capabilities.loadsNativeCode,
      loads_pi_extensions: plugin.capabilities.loadsPiExtensions,
    },
    warnings: plugin.warnings,
    unsupported: plugin.unsupported,
  };
  return JSON.parse(JSON.stringify(json)) as JsonObject;
}
