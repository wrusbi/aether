// Deterministic content digest for plugin folders.
//
// The host app pins this digest when a plugin is installed and compares it
// again before the plugin is used. The Kotlin side (`PluginDigest`) implements
// the very same algorithm, and both are tested against the same vector, so a
// digest computed by either side can be verified by the other.
//
// Algorithm "tree-sha256-v1":
//   1. Walk the folder. Only plain files and folders are allowed; a symbolic
//      link or any other special entry makes the digest unavailable.
//   2. Collect every file as a relative path with "/" separators and sort the
//      paths by UTF-16 code unit order (the default JavaScript / Kotlin order).
//   3. For each file build the record "<path>\n<size>\n<sha256 of content>\n"
//      where the sha256 is lowercase hex.
//   4. The digest is the lowercase hex SHA-256 of all records concatenated
//      (UTF-8), in order.

import { createHash } from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";

export const PLUGIN_DIGEST_ALGORITHM = "tree-sha256-v1";

const DEFAULT_MAX_FILES = 10_000;
const DEFAULT_MAX_TOTAL_BYTES = 256 * 1024 * 1024;

export class PluginIntegrityError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PluginIntegrityError";
  }
}

export interface TreeDigestOptions {
  maxFiles?: number;
  maxTotalBytes?: number;
}

export interface TreeDigest {
  algorithm: typeof PLUGIN_DIGEST_ALGORITHM;
  digest: string;
  fileCount: number;
  totalBytes: number;
}

export interface TreeDigestVerification {
  ok: boolean;
  expected: string;
  actual?: string;
  reason?: string;
}

interface CollectedFile {
  relativePath: string;
  absolutePath: string;
}

function sha256Hex(data: Buffer | string): string {
  return createHash("sha256").update(data).digest("hex");
}

function collectFiles(
  directory: string,
  prefix: string,
  out: CollectedFile[],
  maxFiles: number,
): void {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const relativePath = prefix === "" ? entry.name : `${prefix}/${entry.name}`;
    if (entry.isSymbolicLink()) {
      throw new PluginIntegrityError(`Symbolic links are not allowed: ${relativePath}`);
    }
    const absolutePath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      collectFiles(absolutePath, relativePath, out, maxFiles);
    } else if (entry.isFile()) {
      out.push({ relativePath, absolutePath });
      if (out.length > maxFiles) {
        throw new PluginIntegrityError(`Plugin has more than ${maxFiles} files.`);
      }
    } else {
      throw new PluginIntegrityError(`Unsupported entry type: ${relativePath}`);
    }
  }
}

export function computeTreeDigest(root: string, options: TreeDigestOptions = {}): TreeDigest {
  const maxFiles = options.maxFiles ?? DEFAULT_MAX_FILES;
  const maxTotalBytes = options.maxTotalBytes ?? DEFAULT_MAX_TOTAL_BYTES;
  let rootStat: fs.Stats;
  try {
    rootStat = fs.lstatSync(root);
  } catch {
    throw new PluginIntegrityError(`Plugin folder does not exist: ${root}`);
  }
  if (rootStat.isSymbolicLink() || !rootStat.isDirectory()) {
    throw new PluginIntegrityError("Plugin root must be a real directory.");
  }

  const files: CollectedFile[] = [];
  collectFiles(root, "", files, maxFiles);
  files.sort((left, right) =>
    left.relativePath < right.relativePath ? -1 : left.relativePath > right.relativePath ? 1 : 0,
  );

  const outer = createHash("sha256");
  let totalBytes = 0;
  for (const file of files) {
    const content = fs.readFileSync(file.absolutePath);
    totalBytes += content.length;
    if (totalBytes > maxTotalBytes) {
      throw new PluginIntegrityError("Plugin is larger than the allowed size.");
    }
    outer.update(`${file.relativePath}\n${content.length}\n${sha256Hex(content)}\n`, "utf8");
  }

  return {
    algorithm: PLUGIN_DIGEST_ALGORITHM,
    digest: outer.digest("hex"),
    fileCount: files.length,
    totalBytes,
  };
}

export function verifyTreeDigest(
  root: string,
  expectedDigest: string,
  options: TreeDigestOptions = {},
): TreeDigestVerification {
  const expected = expectedDigest.trim().toLowerCase();
  try {
    const actual = computeTreeDigest(root, options).digest;
    return actual === expected
      ? { ok: true, expected, actual }
      : { ok: false, expected, actual, reason: "Plugin content does not match the pinned digest." };
  } catch (error) {
    return {
      ok: false,
      expected,
      reason: error instanceof Error ? error.message : String(error),
    };
  }
}
