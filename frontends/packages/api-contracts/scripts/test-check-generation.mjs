import { mkdtemp, mkdir, rm, symlink, writeFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";

const checker = "frontends/packages/api-contracts/scripts/check-generation.mjs";
const fixture = await mkdtemp(join(tmpdir(), "xzwl-generation-checker-"));
const generatedRoot = join(fixture, "generated");
const windows = process.platform === "win32";
function runSnapshot(root = generatedRoot) {
  return spawnSync(process.execPath, [checker], {
    encoding: "utf8",
    env: { ...process.env, SNAPSHOT_ONLY: root }
  });
}
try {
  await mkdir(join(generatedRoot, "nested"), { recursive: true });
  await writeFile(join(generatedRoot, "nested", "client.ts"), "export {};\n");
  let result = runSnapshot();
  if (result.status !== 0) throw new Error("ordinary generated tree must pass: " + result.stderr);

  // Directory junctions are real reparse points and need no Windows symlink privilege.
  await symlink(windows ? join(generatedRoot, "nested") : "nested/client.ts",
    join(generatedRoot, "client-link.ts"), windows ? "junction" : "file");
  result = runSnapshot();
  if (result.status === 0 || !result.stderr.includes("generated snapshot rejects symbolic link")) {
    throw new Error("symlink must be rejected: " + result.stderr);
  }
  await rm(join(generatedRoot, "client-link.ts"));

  if (!windows) {
    const childPipe = join(generatedRoot, "generated.pipe");
    result = spawnSync("mkfifo", [childPipe], { encoding: "utf8" });
    if (result.status !== 0) throw new Error("mkfifo fixture setup failed: " + result.stderr);
    result = runSnapshot();
    if (result.status === 0 || !result.stderr.includes("generated snapshot rejects special item")) {
      throw new Error("special item must be rejected: " + result.stderr);
    }
    await rm(childPipe);
  }

  const rootFailures = [];
  const rootLink = join(fixture, "generated-link");
  await symlink(windows ? generatedRoot : "generated", rootLink, windows ? "junction" : "dir");
  result = runSnapshot(rootLink);
  if (result.status === 0 || !result.stderr.includes("generated snapshot rejects symbolic link")) {
    rootFailures.push("root symlink must be rejected explicitly: " + result.stderr);
  }

  const rootFile = join(fixture, "generated-root.ts");
  await writeFile(rootFile, "export {};\n");
  result = runSnapshot(rootFile);
  if (result.status === 0 || !result.stderr.includes("generated snapshot rejects special item")) {
    rootFailures.push("non-directory root must be rejected explicitly: " + result.stderr);
  }
  if (!windows) {
    const rootPipe = join(fixture, "generated-root.pipe");
    result = spawnSync("mkfifo", [rootPipe], { encoding: "utf8" });
    if (result.status !== 0) throw new Error("root mkfifo fixture setup failed: " + result.stderr);
    result = runSnapshot(rootPipe);
    if (result.status === 0 || !result.stderr.includes("generated snapshot rejects special item")) {
      rootFailures.push("root special item must be rejected explicitly: " + result.stderr);
    }
  } else {
    console.log("POSIX child/root FIFO coverage unavailable on win32; real child/root junction rejection verified");
  }
  if (rootFailures.length > 0) {
    throw new Error(rootFailures.join("\n"));
  }
} finally {
  await rm(fixture, { recursive: true, force: true });
}
console.log("generation checker tests passed");
