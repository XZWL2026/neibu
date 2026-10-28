import { spawnSync } from "node:child_process";
import { lstat, mkdtemp, readFile, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, relative } from "node:path";
import { isDeepStrictEqual } from "node:util";

async function snapshot(root) {
  const rootStatus = await lstat(root);
  if (rootStatus.isSymbolicLink()) {
    throw new Error("generated snapshot rejects symbolic link root: " + root);
  }
  if (!rootStatus.isDirectory()) {
    throw new Error("generated snapshot rejects special item root: " + root);
  }
  const result = {};
  async function walk(directory) {
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const path = join(directory, entry.name);
      const status = await lstat(path);
      if (status.isSymbolicLink()) {
        throw new Error("generated snapshot rejects symbolic link: " + path);
      }
      if (status.isDirectory()) {
        await walk(path);
      } else if (status.isFile()) {
        result[relative(root, path)] = (await readFile(path)).toString("base64");
      } else {
        throw new Error("generated snapshot rejects special item: " + path);
      }
    }
  }
  await walk(root);
  return result;
}

if (process.env.SNAPSHOT_ONLY) {
  await snapshot(process.env.SNAPSHOT_ONLY);
} else {
  const newSystem = process.argv.includes("--new-system");
  const checkedIn = "frontends/packages/api-contracts/src/" + (newSystem ? "generated-new-system" : "generated");
  const temporary = await mkdtemp(join(tmpdir(), "xzwl-orval-"));
  try {
    // npm_execpath identifies the running npm CLI and avoids Windows .cmd spawning.
    const npmCli = process.env.npm_execpath;
    const generationArgs = ["run", newSystem ? "generate:api-new-system" : "generate:api"];
    const generated = spawnSync(npmCli ? process.execPath : "npm", npmCli ? [npmCli, ...generationArgs] : generationArgs, {
      stdio: "inherit",
      env: { ...process.env, ORVAL_OUTPUT_DIR: temporary }
    });
    if (generated.status !== 0) {
      throw new Error("Orval generation failed with exit " + String(generated.status));
    }
    const expected = await snapshot(checkedIn);
    const actual = await snapshot(temporary);
    if (!isDeepStrictEqual(actual, expected)) {
      throw new Error("generated API differs from reproducible temporary output");
    }
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
}
