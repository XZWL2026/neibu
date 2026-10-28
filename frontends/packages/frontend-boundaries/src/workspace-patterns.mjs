export function normalizedWorkspacePatterns(rootManifest, violations) {
  const configured = Array.isArray(rootManifest?.workspaces)
    ? rootManifest.workspaces
    : rootManifest?.workspaces?.packages;
  if (!Array.isArray(configured) || configured.length === 0) {
    violations.push("package.json: workspaces must be a non-empty array");
    return [];
  }

  const patterns = [];
  for (const candidate of configured) {
    if (
      typeof candidate !== "string" ||
      candidate.includes("\\") ||
      candidate.startsWith("!") ||
      candidate.startsWith("/") ||
      candidate.endsWith("/")
    ) {
      violations.push(`package.json: unsafe workspace pattern (${String(candidate)})`);
      continue;
    }
    const segments = candidate.split("/");
    if (
      segments[0] !== "frontends" ||
      segments.some(
        (segment) =>
          segment === "" ||
          segment === "." ||
          segment === ".." ||
          (segment !== "*" && !/^[A-Za-z0-9._-]+$/.test(segment))
      )
    ) {
      violations.push(`package.json: unsafe workspace pattern (${candidate})`);
      continue;
    }
    patterns.push(candidate);
  }

  const uniquePatterns = new Set();
  for (const pattern of patterns) {
    if (uniquePatterns.has(pattern)) {
      violations.push(`package.json: duplicate workspace pattern (${pattern})`);
    }
    uniquePatterns.add(pattern);
  }
  return [...uniquePatterns];
}

export function matchesWorkspacePattern(directory, pattern) {
  const directorySegments = directory.split("/");
  const patternSegments = pattern.split("/");
  return (
    directorySegments.length === patternSegments.length &&
    patternSegments.every(
      (segment, index) => segment === "*" || segment === directorySegments[index]
    )
  );
}
