import { lstat, readdir, realpath } from "node:fs/promises";
import { join, relative, sep } from "node:path";

const dependencyDirectoryNames = new Set(["node_modules"]);
const generatedDirectoryNames = new Set([
  ".vite",
  "build",
  "coverage",
  "dist"
]);
const excludedFrontendDirectories = new Set(["frontends/packages/frontend-boundaries"]);

function normalizedRelative(root, path) {
  return relative(root, path).split(sep).join("/");
}

function isContained(root, candidate) {
  return candidate === root || candidate.startsWith(root + sep);
}

function entryKind(stat) {
  if (stat.isSymbolicLink()) return "symlink";
  if (stat.isFIFO()) return "FIFO";
  if (stat.isSocket()) return "socket";
  if (stat.isBlockDevice()) return "block device";
  if (stat.isCharacterDevice()) return "character device";
  return "special entry";
}

export async function collectFrontendFiles(repositoryRoot, violations) {
  let rootStat;
  try {
    rootStat = await lstat(repositoryRoot);
  } catch (error) {
    violations.push(`repository root must be a real directory: ${error.message}`);
    return [];
  }
  if (rootStat.isSymbolicLink() || !rootStat.isDirectory()) {
    violations.push("repository root must be a real directory");
    return [];
  }

  const canonicalRoot = await realpath(repositoryRoot);
  const frontendRoot = join(repositoryRoot, "frontends");
  const files = [];

  async function walk(path, packageRoot = null) {
    let stat;
    try {
      stat = await lstat(path);
    } catch (error) {
      violations.push(`${normalizedRelative(repositoryRoot, path)}: lstat failed: ${error.message}`);
      return;
    }

    const relativePath = normalizedRelative(repositoryRoot, path);
    if (stat.isSymbolicLink() || (!stat.isDirectory() && !stat.isFile())) {
      violations.push(`${relativePath}: unsupported filesystem entry (${entryKind(stat)})`);
      return;
    }

    let canonicalPath;
    try {
      canonicalPath = await realpath(path);
    } catch (error) {
      violations.push(`${relativePath}: realpath failed: ${error.message}`);
      return;
    }
    if (!isContained(canonicalRoot, canonicalPath)) {
      violations.push(`${relativePath}: filesystem entry escapes repository root`);
      return;
    }

    if (stat.isFile()) {
      files.push({ path, relativePath });
      return;
    }
    const directoryName = path.split(sep).at(-1);
    if (dependencyDirectoryNames.has(directoryName)) {
      return;
    }
    if (packageRoot !== null && generatedDirectoryNames.has(directoryName)) {
      return;
    }

    const names = await readdir(path);
    names.sort((left, right) => left.localeCompare(right));
    const currentPackageRoot = names.includes("package.json") ? path : packageRoot;
    if (excludedFrontendDirectories.has(relativePath)) {
      if (currentPackageRoot === path) {
        await walk(join(path, "package.json"), currentPackageRoot);
      }
      return;
    }
    for (const name of names) {
      await walk(join(path, name), currentPackageRoot);
    }
  }

  const rootPackagePath = join(repositoryRoot, "package.json");
  await walk(rootPackagePath);
  await walk(frontendRoot);
  return files;
}
