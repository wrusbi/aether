import { inspectPlugin, pluginInspectionToJson } from "./plugin-formats.js";
import { computeTreeDigest } from "./plugin-integrity.js";

type JsonObject = Record<string, unknown>;

/**
 * Bridge request `inspect_plugin`: describe a plugin folder and pin its digest.
 * Nothing in the folder is executed.
 */
export function inspectPluginRequest(payload: JsonObject): JsonObject {
  const root = typeof payload.plugin_root === "string" ? payload.plugin_root.trim() : "";
  if (root === "") throw new Error("plugin_root is required.");
  const plugin = inspectPlugin(root);
  const digest = computeTreeDigest(root);
  return {
    ...pluginInspectionToJson(plugin),
    digest: digest.digest,
    digest_algorithm: digest.algorithm,
    file_count: digest.fileCount,
    total_bytes: digest.totalBytes,
  };
}
