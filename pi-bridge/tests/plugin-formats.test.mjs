import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

// The modules are self-contained TypeScript (node builtins only), so Node can
// run them directly through its built-in type stripping.
import {
  PluginFormatError,
  inspectPlugin,
  pluginInspectionToJson,
} from "../src/plugin-formats.ts";
import {
  PLUGIN_DIGEST_ALGORITHM,
  PluginIntegrityError,
  computeTreeDigest,
  verifyTreeDigest,
} from "../src/plugin-integrity.ts";

const created = [];

test.after(() => {
  for (const directory of created) fs.rmSync(directory, { recursive: true, force: true });
});

function tree(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "aether-plugin-"));
  created.push(root);
  for (const [relative, content] of Object.entries(files)) {
    const target = path.join(root, ...relative.split("/"));
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, typeof content === "string" ? content : JSON.stringify(content));
  }
  return root;
}

function skillMarkdown(name, description) {
  return `---\nname: ${name}\ndescription: ${description}\n---\n\n# ${name}\n`;
}

// ---------------------------------------------------------------- digest ----

test("tree digest matches the shared cross-language test vector", () => {
  const root = tree({ "dir/b.md": "world", "a.txt": "hello\n" });
  const result = computeTreeDigest(root);
  assert.equal(result.algorithm, PLUGIN_DIGEST_ALGORITHM);
  assert.equal(result.digest, "39151ee6fd4996e4705834d1744d117bc33a2a1e6f58173656f0dfdc94e24b69");
  assert.equal(result.fileCount, 2);
  assert.equal(result.totalBytes, 11);
});

test("tree digest ignores creation order and detects content or name changes", () => {
  const first = tree({ "one.txt": "alpha", "two/three.txt": "beta" });
  const second = tree({ "two/three.txt": "beta", "one.txt": "alpha" });
  assert.equal(computeTreeDigest(first).digest, computeTreeDigest(second).digest);

  const edited = tree({ "one.txt": "alpha", "two/three.txt": "BETA" });
  const renamed = tree({ "uno.txt": "alpha", "two/three.txt": "beta" });
  assert.notEqual(computeTreeDigest(first).digest, computeTreeDigest(edited).digest);
  assert.notEqual(computeTreeDigest(first).digest, computeTreeDigest(renamed).digest);
});

test("tree digest rejects symbolic links and verification reports why", () => {
  const root = tree({ "SKILL.md": "safe" });
  fs.symlinkSync("/tmp", path.join(root, "escape"));
  assert.throws(() => computeTreeDigest(root), PluginIntegrityError);

  const clean = tree({ "SKILL.md": "safe" });
  const pinned = computeTreeDigest(clean).digest;
  assert.equal(verifyTreeDigest(clean, pinned.toUpperCase()).ok, true);
  fs.writeFileSync(path.join(clean, "SKILL.md"), "tampered");
  const tampered = verifyTreeDigest(clean, pinned);
  assert.equal(tampered.ok, false);
  assert.match(tampered.reason, /does not match/);
  assert.equal(verifyTreeDigest(root, pinned).ok, false);
});

test("tree digest enforces file-count and size limits", () => {
  const root = tree({ "a.txt": "1", "b.txt": "2", "c.txt": "3" });
  assert.throws(() => computeTreeDigest(root, { maxFiles: 2 }), PluginIntegrityError);
  assert.throws(() => computeTreeDigest(root, { maxTotalBytes: 2 }), PluginIntegrityError);
});

// --------------------------------------------------------------- formats ----

test("reads a Claude Code plugin with every component kind", () => {
  const root = tree({
    ".claude-plugin/plugin.json": {
      name: "demo-plugin",
      version: "1.2.3",
      description: "Demo plugin",
      author: { name: "Ada" },
      license: "MIT",
    },
    "skills/alpha/SKILL.md": skillMarkdown("alpha", "Alpha skill"),
    "skills/beta/SKILL.md": skillMarkdown("beta", "Beta skill"),
    "skills/notes.txt": "not a skill",
    "commands/hello.md": "---\ndescription: Say hello\n---\nHello",
    "commands/team/review.md": "Review",
    "agents/reviewer.md": "---\nname: reviewer\ndescription: Reviews code\n---\n",
    ".mcp.json": {
      mcpServers: {
        local: { command: "node", args: ["server.js", "--flag"] },
        remote: { type: "http", url: "https://example.com/mcp" },
      },
    },
    "hooks/hooks.json": {
      hooks: { PostToolUse: [{ matcher: "Write", hooks: [{ type: "command", command: "echo hi" }] }] },
    },
    "package.json": {
      name: "demo-plugin",
      pi: { extensions: ["./pi/tool.ts"] },
      aether: {
        extensions: ["./ui/mod.ts"],
        native: { entrypoints: ["com.example.Mod"], classpath: ["./native/mod.dex"] },
      },
    },
  });

  const plugin = inspectPlugin(root);
  assert.equal(plugin.flavor, "claude-code");
  assert.equal(plugin.name, "demo-plugin");
  assert.equal(plugin.version, "1.2.3");
  assert.equal(plugin.author, "Ada");
  assert.deepEqual(plugin.skills.map((skill) => [skill.name, skill.path]), [
    ["alpha", "skills/alpha"],
    ["beta", "skills/beta"],
  ]);
  assert.equal(plugin.skills[0].description, "Alpha skill");
  assert.deepEqual(plugin.commands.map((entry) => [entry.name, entry.path]), [
    ["hello", "commands/hello.md"],
    ["team:review", "commands/team/review.md"],
  ]);
  assert.equal(plugin.commands[0].description, "Say hello");
  assert.deepEqual(plugin.agents.map((entry) => entry.name), ["reviewer"]);
  assert.deepEqual(plugin.mcpServers, [
    { name: "local", transport: "stdio", command: "node server.js --flag" },
    { name: "remote", transport: "http", url: "https://example.com/mcp" },
  ]);
  assert.deepEqual(plugin.hooks, { source: "hooks/hooks.json", events: ["PostToolUse"], commandCount: 1 });
  assert.deepEqual(plugin.ui.scriptExtensions, ["ui/mod.ts"]);
  assert.deepEqual(plugin.ui.piExtensions, ["pi/tool.ts"]);
  assert.deepEqual(plugin.ui.nativeEntrypoints, ["com.example.Mod"]);
  assert.deepEqual(plugin.capabilities, {
    runsHookCommands: true,
    startsMcpProcesses: true,
    usesRemoteMcp: true,
    changesAetherUi: true,
    loadsNativeCode: true,
    loadsPiExtensions: true,
  });
  assert.ok(plugin.unsupported.some((entry) => /Hooks/.test(entry)));
});

test("a plain skills-only Claude plugin has no risky capabilities", () => {
  const root = tree({
    ".claude-plugin/plugin.json": { name: "only-skills" },
    "skills/one/SKILL.md": skillMarkdown("one", "One"),
  });
  const plugin = inspectPlugin(root);
  assert.equal(plugin.skills.length, 1);
  assert.equal(plugin.hooks, undefined);
  assert.equal(plugin.ui, undefined);
  assert.deepEqual(plugin.capabilities, {
    runsHookCommands: false,
    startsMcpProcesses: false,
    usesRemoteMcp: false,
    changesAetherUi: false,
    loadsNativeCode: false,
    loadsPiExtensions: false,
  });
  assert.deepEqual(plugin.warnings, []);
  assert.deepEqual(plugin.unsupported, []);
});

test("reads Codex plugins and flags unsupported Codex apps", () => {
  const root = tree({
    ".codex-plugin/plugin.json": { name: "codex-demo", version: "0.1.0", skills: "./skills/", mcpServers: "./.mcp.json" },
    "skills/review/SKILL.md": skillMarkdown("review", "Review"),
    ".mcp.json": { mcpServers: { docs: { url: "https://example.com/sse", type: "sse" } } },
    ".app.json": { apps: {} },
  });
  const plugin = inspectPlugin(root);
  assert.equal(plugin.flavor, "codex");
  assert.deepEqual(plugin.skills.map((skill) => skill.name), ["review"]);
  assert.deepEqual(plugin.mcpServers, [{ name: "docs", transport: "sse", url: "https://example.com/sse" }]);
  assert.equal(plugin.capabilities.usesRemoteMcp, true);
  assert.equal(plugin.capabilities.startsMcpProcesses, false);
  assert.ok(plugin.unsupported.some((entry) => /\.app\.json/.test(entry)));
});

test("reads the portable agent plugin layout with root plugin.json and mcp.json", () => {
  const root = tree({
    "plugin.json": { $schema: "https://agent-plugins.org/schema/plugin.json", name: "portable", version: "2.0.0" },
    "skills/pdf/SKILL.md": skillMarkdown("pdf", "PDF"),
    "mcp.json": { mcpServers: { files: { command: "npx", args: ["-y", "files-mcp"] } } },
  });
  const plugin = inspectPlugin(root);
  assert.equal(plugin.flavor, "agent-plugin");
  assert.equal(plugin.skills.length, 1);
  assert.deepEqual(plugin.mcpServers, [{ name: "files", transport: "stdio", command: "npx -y files-mcp" }]);
});

test("prefers the Claude manifest when several are present and says so", () => {
  const root = tree({
    ".claude-plugin/plugin.json": { name: "dual" },
    ".codex-plugin/plugin.json": { name: "dual" },
  });
  const plugin = inspectPlugin(root);
  assert.equal(plugin.flavor, "claude-code");
  assert.deepEqual(plugin.alsoPresent, ["codex"]);
  assert.ok(plugin.warnings.some((entry) => /Several manifests/.test(entry)));
});

test("extra component directories add to the defaults", () => {
  const root = tree({
    ".claude-plugin/plugin.json": {
      name: "extras",
      commands: ["./more-commands"],
      skills: ["./extra-skills"],
    },
    "commands/a.md": "A",
    "more-commands/b.md": "B",
    "skills/one/SKILL.md": skillMarkdown("one", "One"),
    "extra-skills/two/SKILL.md": skillMarkdown("two", "Two"),
  });
  const plugin = inspectPlugin(root);
  assert.deepEqual(plugin.commands.map((entry) => entry.name), ["a", "b"]);
  assert.deepEqual(plugin.skills.map((skill) => skill.path), ["extra-skills/two", "skills/one"]);
});

test("rejects unusable plugins with clear errors", () => {
  assert.throws(() => inspectPlugin(tree({ "readme.md": "nothing" })), /No plugin manifest found/);
  assert.throws(() => inspectPlugin(tree({ ".claude-plugin/plugin.json": "{ nope" })), /not valid JSON/);
  assert.throws(() => inspectPlugin(tree({ ".claude-plugin/plugin.json": "[]" })), /JSON object/);
  assert.throws(() => inspectPlugin(tree({ ".claude-plugin/plugin.json": {} })), /"name"/);
  assert.throws(
    () => inspectPlugin(tree({ ".claude-plugin/plugin.json": { name: "../evil" } })),
    PluginFormatError,
  );
  assert.throws(() => inspectPlugin(path.join(os.tmpdir(), "does-not-exist-aether")), PluginFormatError);
});

test("paths that leave the plugin folder are refused and reported", () => {
  const outside = tree({ "secret/SKILL.md": skillMarkdown("secret", "Should never be listed") });
  const root = tree({
    ".claude-plugin/plugin.json": {
      name: "escape",
      skills: ["../" + path.basename(outside), "/etc"],
      commands: ["C:\\Windows"],
      mcpServers: "../outside.json",
    },
  });
  fs.symlinkSync(outside, path.join(root, "linked-skills"));

  const plugin = inspectPlugin(root);
  assert.deepEqual(plugin.skills, []);
  assert.deepEqual(plugin.commands, []);
  assert.deepEqual(plugin.mcpServers, []);
  assert.ok(plugin.warnings.length >= 4);
});

test("symbolic links inside component folders are ignored", () => {
  const outside = tree({ "SKILL.md": skillMarkdown("outside", "Outside") });
  const root = tree({
    ".claude-plugin/plugin.json": { name: "linky" },
    "skills/real/SKILL.md": skillMarkdown("real", "Real"),
  });
  fs.symlinkSync(outside, path.join(root, "skills", "sneaky"));
  fs.symlinkSync(path.join(outside, "SKILL.md"), path.join(root, "commands-link.md"));

  const plugin = inspectPlugin(root);
  assert.deepEqual(plugin.skills.map((skill) => skill.name), ["real"]);
  assert.ok(plugin.warnings.some((entry) => /symbolic link/i.test(entry)));
});

test("bridge JSON uses snake_case and drops empty values", () => {
  const root = tree({
    ".claude-plugin/plugin.json": { name: "wire" },
    "skills/one/SKILL.md": "# no front matter",
    ".mcp.json": { mcpServers: { local: { command: "node" } } },
  });
  const json = pluginInspectionToJson(inspectPlugin(root));
  assert.equal(json.format, "claude-code");
  assert.equal(json.manifest_path, ".claude-plugin/plugin.json");
  assert.deepEqual(json.skills, [{ name: "one", path: "skills/one" }]);
  assert.deepEqual(json.mcp_servers, [{ name: "local", transport: "stdio", command: "node" }]);
  assert.equal(json.capabilities.starts_mcp_processes, true);
  assert.equal("version" in json, false);
  assert.equal("hooks" in json, false);
  assert.equal("ui" in json, false);
});

test("oversized or malformed component data cannot crash the reader", () => {
  const root = tree({
    ".claude-plugin/plugin.json": { name: "robust", mcpServers: [42, null, { servers: 1 }] },
    ".mcp.json": "{ broken",
    "hooks/hooks.json": "[1, 2]",
    "skills/bad name/SKILL.md": skillMarkdown("bad", "Bad"),
  });
  const plugin = inspectPlugin(root);
  assert.deepEqual(plugin.skills, []);
  assert.ok(plugin.warnings.some((entry) => /not valid JSON/.test(entry)));
  assert.ok(plugin.warnings.some((entry) => /unsupported name/.test(entry)));
});
