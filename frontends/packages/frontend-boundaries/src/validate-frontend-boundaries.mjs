import { readFile } from "node:fs/promises";
import { dirname, isAbsolute, relative, resolve, sep } from "node:path";
import traverseModule from "@babel/traverse";
import { collectFrontendFiles } from "./safe-filesystem.mjs";
import { parseHtmlBoundaryInput } from "./html-analysis.mjs";
import {
  isScriptPath,
  moduleName,
  parsePrograms,
  propertyName,
  staticStringAt
} from "./source-analysis.mjs";
import {
  matchesWorkspacePattern,
  normalizedWorkspacePatterns
} from "./workspace-patterns.mjs";

const traverse = traverseModule.default ?? traverseModule;

const appRules = [
  { directory: "admin-web", framework: "vue", packageName: "@xzwl/admin-web" },
  { directory: "partner-web", framework: "vue", packageName: "@xzwl/partner-web" },
  { directory: "user-web", framework: "react", packageName: "@xzwl/user-web" }
];

const dependencyFields = [
  "dependencies",
  "devDependencies",
  "optionalDependencies",
  "peerDependencies"
];
const reactCoordinates = new Set([
  "@types/react",
  "@types/react-dom",
  "@vitejs/plugin-react",
  "react",
  "react-dom",
  "react-router",
  "react-router-dom"
]);
const vueCoordinates = new Set([
  "@types/vue",
  "@vitejs/plugin-vue",
  "@vue/compiler-sfc",
  "@vue/test-utils",
  "pinia",
  "vue",
  "vue-router",
  "vue-tsc"
]);
const unauthorizedNetworkClients = new Set([
  "axios",
  "cross-fetch",
  "eventsource",
  "got",
  "isomorphic-fetch",
  "ky",
  "node-fetch",
  "superagent",
  "undici",
  "ws",
  "xmlhttprequest"
]);
const globalObjects = new Set(["globalThis", "navigator", "self", "window"]);
const directNetworkIdentifiers = new Map([
  ["EventSource", "direct"],
  ["WebSocket", "direct"],
  ["XMLHttpRequest", "direct"],
  ["axios", "axios"],
  ["fetch", "fetch"]
]);
const semanticInfrastructurePatterns = [
  [/\bnacos\b/i, "Nacos"],
  [/lb:\/\//i, "lb://"],
  [/\/internal\//i, "/internal/"]
];
const serviceConfigurationKeys = new Set([
  "baseURL",
  "baseUrl",
  "endpoint",
  "proxy",
  "server",
  "target",
  "upstream"
]);

function npmAliasCoordinate(specifier) {
  if (typeof specifier !== "string" || !specifier.startsWith("npm:")) return null;
  const target = specifier.slice(4);
  if (target.startsWith("@")) {
    const slash = target.indexOf("/");
    if (slash < 2) return null;
    const version = target.indexOf("@", slash);
    return version === -1 ? target : target.slice(0, version);
  }
  const version = target.indexOf("@");
  return version === -1 ? target : target.slice(0, version);
}

function workspaceAliasCoordinate(specifier) {
  if (typeof specifier !== "string" || !specifier.startsWith("workspace:")) return null;
  const target = specifier.slice("workspace:".length);
  if (!target.startsWith("@")) return null;
  const slash = target.indexOf("/");
  if (slash < 2) return null;
  const version = target.indexOf("@", slash);
  return version === -1 ? target : target.slice(0, version);
}

function dependencyEntries(manifest) {
  const entries = [];
  for (const field of dependencyFields) {
    for (const [declaredName, specifier] of Object.entries(manifest[field] ?? {})) {
      entries.push({ declaredName, field, specifier });
    }
  }
  return entries;
}

function dependencyCoordinate(entry) {
  return (
    npmAliasCoordinate(entry.specifier) ??
    workspaceAliasCoordinate(entry.specifier) ??
    entry.declaredName
  );
}

function isFrontendManifest(relativePath) {
  return relativePath.startsWith("frontends/") && relativePath.endsWith("/package.json");
}

function isGeneratedSdk(relativePath) {
  return relativePath.startsWith("frontends/packages/api-contracts/src/generated/");
}

function isHtmlPath(relativePath) {
  return relativePath.toLowerCase().endsWith(".html");
}

function appDirectoryFor(relativePath) {
  return appRules.find((rule) => relativePath.startsWith(`frontends/${rule.directory}/`))
    ?.directory;
}

function memberProperty(node) {
  if (node.type !== "MemberExpression" && node.type !== "OptionalMemberExpression") return null;
  return propertyName(node.property);
}

const networkKinds = new Set(["axios", "direct", "fetch"]);
const transparentPathTypes = new Set([
  "AwaitExpression",
  "ParenthesizedExpression",
  "TSAsExpression",
  "TSInstantiationExpression",
  "TSNonNullExpression",
  "TSSatisfiesExpression",
  "TSTypeAssertion",
  "TypeCastExpression"
]);

function unwrapPath(path) {
  let current = path;
  while (current?.node && transparentPathTypes.has(current.node.type)) {
    current = current.get(current.node.type === "AwaitExpression" ? "argument" : "expression");
  }
  return current;
}

function isRuntimeBinding(binding) {
  if (!binding) return false;
  if (isTypeOnlyImport(binding.path)) return false;
  if (binding.path.isTSDeclareFunction?.()) return false;
  if (binding.path.node.declare === true) return false;
  if (binding.path.parentPath?.node.declare === true) return false;
  return !binding.path.findParent(
    (parent) => parent.node.declare === true || parent.isTSDeclareFunction?.()
  );
}

function isUnshadowedGlobal(path, name) {
  return !isRuntimeBinding(path.scope.getBinding(name));
}

function isAxiosLoaderPath(path) {
  const value = unwrapPath(path);
  if (!value?.node) return false;
  if (value.isImportExpression()) return moduleName(value.node.source) === "axios";
  if (!value.isCallExpression() && !value.isOptionalCallExpression()) return false;
  const callee = unwrapPath(value.get("callee"));
  if (callee?.isImport()) return moduleName(value.node.arguments[0]) === "axios";
  return (
    callee?.isIdentifier({ name: "require" }) &&
    isUnshadowedGlobal(callee, "require") &&
    moduleName(value.node.arguments[0]) === "axios"
  );
}

function evaluatedProperty(path) {
  const value = memberProperty(path.node);
  if (!path.node.computed && value !== null) return value;
  const evaluated = path.get("property").evaluate();
  return evaluated.confident && typeof evaluated.value === "string" ? evaluated.value : value;
}

function rootPropertyKind(rootKind, property) {
  if (rootKind === "global-root") {
    if (["globalThis", "self", "window"].includes(property)) return "global-root";
    if (property === "navigator") return "navigator-root";
    if (property === "fetch") return "fetch";
    if (["EventSource", "WebSocket", "XMLHttpRequest"].includes(property)) return "direct";
    if (property === "axios") return "axios";
  }
  if (rootKind === "navigator-root" && property === "sendBeacon") return "direct";
  return null;
}

function bindingPatternProperties(path, bindingName, properties = []) {
  if (path.isIdentifier({ name: bindingName })) return properties;
  if (path.isAssignmentPattern()) {
    return bindingPatternProperties(path.get("left"), bindingName, properties);
  }
  if (!path.isObjectPattern()) return null;
  for (const propertyPath of path.get("properties")) {
    if (!propertyPath.isObjectProperty()) continue;
    const key = propertyPath.node.computed
      ? propertyPath.get("key").evaluate()
      : { confident: true, value: propertyName(propertyPath.node.key) };
    if (!key.confident || typeof key.value !== "string") continue;
    const result = bindingPatternProperties(
      propertyPath.get("value"),
      bindingName,
      [...properties, key.value]
    );
    if (result) return result;
  }
  return null;
}

function bindingPatternKind(patternPath, binding, initializerPath, seenBindings) {
  const properties = bindingPatternProperties(patternPath, binding.identifier.name);
  if (!properties) return null;
  let kind = resolveNetworkPath(initializerPath, seenBindings);
  for (const property of properties) {
    kind = rootPropertyKind(kind, property);
    if (!kind) return null;
  }
  return kind;
}

function resolveObjectMember(objectPath, property, seenBindings) {
  const object = unwrapPath(objectPath);
  if (!object?.node) return null;
  const rooted = rootPropertyKind(resolveNetworkPath(object, seenBindings), property);
  if (rooted) return rooted;
  if (object.isObjectExpression()) {
    for (const propertyPath of object.get("properties")) {
      if (!propertyPath.isObjectProperty()) continue;
      const key = propertyPath.node.computed
        ? propertyPath.get("key").evaluate()
        : { confident: true, value: propertyName(propertyPath.node.key) };
      if (key.confident && key.value === property) {
        return resolveNetworkPath(propertyPath.get("value"), seenBindings);
      }
    }
    return null;
  }
  if (object.isIdentifier()) {
    const binding = object.scope.getBinding(object.node.name);
    if (!binding || seenBindings.has(binding)) return null;
    const nextSeen = new Set(seenBindings).add(binding);
    if (binding.path.isVariableDeclarator()) {
      const initializer = binding.path.get("init");
      if (initializer.node) {
        const resolved = resolveObjectMember(initializer, property, nextSeen);
        if (resolved) return resolved;
      }
    }
    for (const violation of binding.constantViolations) {
      if (violation.isAssignmentExpression()) {
        const resolved = resolveObjectMember(violation.get("right"), property, nextSeen);
        if (resolved) return resolved;
      }
    }
  }
  return null;
}

function resolveBindingKind(binding, seenBindings) {
  if (seenBindings.has(binding)) return null;
  const nextSeen = new Set(seenBindings).add(binding);
  const kinds = new Set();

  if (binding.path.isImportSpecifier() || binding.path.isImportDefaultSpecifier() || binding.path.isImportNamespaceSpecifier()) {
    if (
      !isTypeOnlyImport(binding.path) &&
      moduleName(binding.path.parentPath.node.source) === "axios"
    ) {
      kinds.add("axios");
    }
  }
  if (binding.path.isVariableDeclarator()) {
    const initializer = binding.path.get("init");
    if (initializer.node) {
      const direct = binding.path.get("id").isIdentifier()
        ? resolveNetworkPath(initializer, nextSeen)
        : bindingPatternKind(binding.path.get("id"), binding, initializer, nextSeen);
      if (direct) kinds.add(direct);
    }
  }
  for (const violation of binding.constantViolations) {
    if (!violation.isAssignmentExpression()) continue;
    const resolved = bindingPatternKind(
      violation.get("left"),
      binding,
      violation.get("right"),
      nextSeen
    );
    if (resolved) kinds.add(resolved);
  }
  if (kinds.size !== 1) return null;
  return [...kinds][0];
}

function directFunctionReturnPath(functionPath) {
  if (!functionPath || functionPath.node.params.length !== 0) return null;
  const body = functionPath.get("body");
  if (!body.isBlockStatement()) return body;
  const statements = body.get("body");
  if (statements.length !== 1 || !statements[0].isReturnStatement()) return null;
  const argument = statements[0].get("argument");
  return argument.node ? argument : null;
}

function zeroArgumentReturnPaths(calleePath, seenBindings) {
  const callee = unwrapPath(calleePath);
  if (!callee?.isIdentifier()) return null;
  const binding = callee.scope.getBinding(callee.node.name);
  if (!binding || seenBindings.has(binding)) return null;
  const nextSeen = new Set(seenBindings).add(binding);
  const functionPaths = [];
  if (binding.path.isFunctionDeclaration()) {
    functionPaths.push(binding.path);
  } else if (binding.path.isVariableDeclarator()) {
    const initializer = unwrapPath(binding.path.get("init"));
    if (initializer?.isArrowFunctionExpression() || initializer?.isFunctionExpression()) {
      functionPaths.push(initializer);
    }
  }
  for (const violation of binding.constantViolations) {
    if (!violation.isAssignmentExpression()) continue;
    const assigned = unwrapPath(violation.get("right"));
    if (assigned?.isArrowFunctionExpression() || assigned?.isFunctionExpression()) {
      functionPaths.push(assigned);
    }
  }
  const paths = functionPaths.map(directFunctionReturnPath).filter(Boolean);
  return paths.length > 0 ? { paths, seenBindings: nextSeen } : null;
}

function zeroArgumentMemberKind(objectPath, property, seenBindings) {
  const object = unwrapPath(objectPath);
  if (
    (!object?.isCallExpression() && !object?.isOptionalCallExpression()) ||
    object.node.arguments.length !== 0
  ) {
    return null;
  }
  const returned = zeroArgumentReturnPaths(object.get("callee"), seenBindings);
  if (!returned) return null;
  const kinds = new Set();
  for (const returnPath of returned.paths) {
    const rooted = rootPropertyKind(
      resolveNetworkPath(returnPath, returned.seenBindings),
      property
    );
    if (rooted) kinds.add(rooted);
  }
  return ["fetch", "axios", "direct", "navigator-root", "global-root"].find((kind) =>
    kinds.has(kind)
  ) ?? null;
}

function resolveNetworkPath(path, seenBindings = new Set()) {
  const value = unwrapPath(path);
  if (!value?.node) return null;
  if (value.isIdentifier()) {
    const binding = value.scope.getBinding(value.node.name);
    if (isRuntimeBinding(binding)) return resolveBindingKind(binding, seenBindings);
    if (globalObjects.has(value.node.name)) {
      return value.node.name === "navigator" ? "navigator-root" : "global-root";
    }
    return directNetworkIdentifiers.get(value.node.name) ?? null;
  }
  if (value.isImportExpression() && moduleName(value.node.source) === "axios") return "axios";
  if (value.isCallExpression() || value.isOptionalCallExpression()) {
    if (isAxiosLoaderPath(value)) return "axios";
    const callee = unwrapPath(value.get("callee"));
    if (
      (callee.isMemberExpression() || callee.isOptionalMemberExpression()) &&
      evaluatedProperty(callee) === "bind"
    ) {
      return resolveNetworkPath(callee.get("object"), seenBindings);
    }
    if (value.node.arguments.length === 0) {
      const returned = zeroArgumentReturnPaths(callee, seenBindings);
      if (returned) {
        const kinds = new Set(
          returned.paths
            .map((returnPath) => resolveNetworkPath(returnPath, returned.seenBindings))
            .filter(Boolean)
        );
        if (kinds.size === 1) return [...kinds][0];
      }
    }
    return null;
  }
  if (value.isMemberExpression() || value.isOptionalMemberExpression()) {
    const property = evaluatedProperty(value);
    if (property === null) return null;
    const returnedMember = zeroArgumentMemberKind(
      value.get("object"),
      property,
      seenBindings
    );
    if (returnedMember) return returnedMember;
    const objectKind = resolveNetworkPath(value.get("object"), seenBindings);
    const rooted = rootPropertyKind(objectKind, property);
    if (rooted) return rooted;
    if (objectKind === "axios") return "axios";
    if (["bind", "call", "apply"].includes(property) && networkKinds.has(objectKind)) {
      return objectKind;
    }
    return resolveObjectMember(value.get("object"), property, seenBindings);
  }
  return null;
}

const typeOnlyContainers = new Set([
  "TSCallSignatureDeclaration",
  "TSConstructSignatureDeclaration",
  "TSDeclareFunction",
  "TSIndexSignature",
  "TSInterfaceDeclaration",
  "TSMethodSignature",
  "TSPropertySignature",
  "TSTypeAliasDeclaration"
]);

function isTypeOnlyReference(path) {
  return path.getAncestry().some(
    (ancestor) => ancestor.isTSType?.() || typeOnlyContainers.has(ancestor.node.type)
  );
}

function isTypeOnlyPath(path) {
  return isTypeOnlyReference(path);
}

function isTypeOnlyImport(path) {
  return (
    path.node.importKind === "type" ||
    path.parentPath?.node.importKind === "type"
  );
}

function hasRuntimeImports(node) {
  return (
    node.importKind !== "type" &&
    (node.specifiers.length === 0 ||
      node.specifiers.some((specifier) => specifier.importKind !== "type"))
  );
}

function collectNetworkAnalysis(program) {
  const accesses = new Set();
  let axiosImport = false;
  traverse(program, {
    ImportDeclaration(path) {
      if (moduleName(path.node.source) === "axios" && hasRuntimeImports(path.node)) {
        axiosImport = true;
      }
    },
    ImportExpression(path) {
      if (moduleName(path.node.source) === "axios") axiosImport = true;
    },
    ReferencedIdentifier(path) {
      if (isTypeOnlyReference(path)) return;
      const kind = resolveNetworkPath(path);
      if (networkKinds.has(kind)) accesses.add(kind);
    },
    MemberExpression(path) {
      const kind = resolveNetworkPath(path);
      if (networkKinds.has(kind)) accesses.add(kind);
    },
    OptionalMemberExpression(path) {
      const kind = resolveNetworkPath(path);
      if (networkKinds.has(kind)) accesses.add(kind);
    },
    VariableDeclarator(path) {
      for (const name of Object.keys(path.getBindingIdentifiers())) {
        const binding = path.scope.getBinding(name);
        if (!binding) continue;
        const kind = resolveBindingKind(binding, new Set());
        if (networkKinds.has(kind)) accesses.add(kind);
      }
    },
    "CallExpression|OptionalCallExpression|NewExpression"(path) {
      if (isAxiosLoaderPath(path)) axiosImport = true;
    }
  });

  return { accesses, axiosImport };
}

function importsSdk(program) {
  let imported = false;
  traverse(program, {
    ImportDeclaration(path) {
      if (
        hasRuntimeImports(path.node) &&
        moduleName(path.node.source) === "@xzwl/api-contracts"
      ) {
        imported = true;
      }
    },
    ImportExpression(path) {
      if (moduleName(path.node.source) === "@xzwl/api-contracts") imported = true;
    },
    "CallExpression|OptionalCallExpression"(path) {
      const callee = unwrapPath(path.get("callee"));
      if (
        callee?.isIdentifier({ name: "require" }) &&
        isUnshadowedGlobal(callee, "require") &&
        moduleName(path.node.arguments[0]) === "@xzwl/api-contracts"
      ) {
        imported = true;
      }
    }
  });
  return imported;
}

function isInternalServiceAddress(value) {
  if (!/^https?:\/\//i.test(value)) return false;
  try {
    const hostname = new URL(value).hostname.toLowerCase();
    if (!hostname.includes(".")) return true;
    if (["localhost", "0.0.0.0"].includes(hostname)) return true;
    if (/^(?:10|127|169\.254|192\.168)\./.test(hostname)) return true;
    if (/^172\.(?:1[6-9]|2\d|3[01])\./.test(hostname)) return true;
    return /(?:\.internal|\.local|\.svc)$/.test(hostname);
  } catch {
    return true;
  }
}

function inspectSemanticInfrastructure(program, path, violations) {
  const reported = new Set();
  function report(label) {
    if (reported.has(label)) return;
    reported.add(label);
    violations.push(`${path}: forbidden infrastructure reference (${label})`);
  }

  traverse(program, {
    "StringLiteral|TemplateLiteral|BinaryExpression"(expressionPath) {
      if (isTypeOnlyPath(expressionPath)) return;
      const value = staticStringAt(expressionPath);
      if (value === null) return;
      for (const [pattern, label] of semanticInfrastructurePatterns) {
        if (pattern.test(value)) report(label);
      }
    },
    ObjectProperty(propertyPath) {
      if (isTypeOnlyPath(propertyPath)) return;
      const key = propertyPath.node.computed
        ? staticStringAt(propertyPath.get("key"))
        : propertyName(propertyPath.node.key);
      if (!serviceConfigurationKeys.has(key)) return;
      const value = staticStringAt(propertyPath.get("value"));
      if (value !== null && isInternalServiceAddress(value)) {
        report("hard-coded internal service address");
      }
    }
  });
}

function inspectBusinessProgram(program, path, violations) {
  inspectSemanticInfrastructure(program, path, violations);
  const network = collectNetworkAnalysis(program);
  const kinds = network.accesses;
  if (kinds.has("fetch")) {
    violations.push(`${path}: business code must not access fetch directly`);
  }
  if (network.axiosImport || kinds.has("axios")) {
    violations.push(`${path}: business code must not import or access axios`);
  }
  if (kinds.has("direct")) {
    violations.push(`${path}: business code must not access direct network capability`);
  }
}

function srcsetUrlCandidates(value) {
  const candidates = [];
  let position = 0;
  while (position < value.length) {
    while (position < value.length && /[\t\n\f\r ,]/.test(value[position])) position += 1;
    if (position >= value.length) break;

    const start = position;
    while (position < value.length && !/[\t\n\f\r ]/.test(value[position])) position += 1;
    let url = value.slice(start, position);
    if (url.endsWith(",")) {
      url = url.replace(/,+$/, "");
      if (url) candidates.push(url);
      continue;
    }

    let parentheses = 0;
    while (position < value.length) {
      const character = value[position];
      position += 1;
      if (character === "(") parentheses += 1;
      if (character === ")" && parentheses > 0) parentheses -= 1;
      if (character === "," && parentheses === 0) break;
    }
    if (url) candidates.push(url);
  }
  return candidates;
}

function refreshUrlCandidate(value) {
  const separator = value.search(/[;,]/);
  if (separator === -1) return [];
  let candidate = value.slice(separator + 1).trim();
  candidate = candidate.replace(/^url\s*=\s*/i, "").trim();
  if (
    candidate.length >= 2 &&
    ((candidate.startsWith('"') && candidate.endsWith('"')) ||
      (candidate.startsWith("'") && candidate.endsWith("'")))
  ) {
    candidate = candidate.slice(1, -1).trim();
  }
  return candidate ? [candidate] : [];
}

function htmlUrlCandidates(attribute) {
  if (attribute.kind === "srcset") return srcsetUrlCandidates(attribute.value);
  if (attribute.kind === "space-separated") {
    return attribute.value.trim().split(/\s+/).filter(Boolean);
  }
  if (attribute.kind === "refresh") return refreshUrlCandidate(attribute.value);
  const candidate = attribute.value.trim();
  return candidate ? [candidate] : [];
}

function isInternalHtmlAddress(value) {
  return isInternalServiceAddress(value.startsWith("//") ? `https:${value}` : value);
}

function inspectHtmlUrlCandidate(value, reportInfrastructure) {
  if (/^nacos:\/\//i.test(value)) {
    reportInfrastructure("Nacos");
    return;
  }
  if (/^lb:\/\//i.test(value)) {
    reportInfrastructure("lb://");
    return;
  }
  if (/^(?:https?:)?\/\//i.test(value)) {
    if (isInternalHtmlAddress(value)) {
      reportInfrastructure("hard-coded internal service address");
    }
    return;
  }

  try {
    const base = new URL("https://xzwl.invalid/");
    const resolved = new URL(value, base);
    if (resolved.origin !== base.origin) return;
    if (/\/internal(?:\/|$)/i.test(resolved.pathname)) {
      reportInfrastructure("/internal/");
    }
    if (/\/nacos(?:\/|$)/i.test(resolved.pathname)) {
      reportInfrastructure("Nacos");
    }
  } catch {
    return;
  }
}

function inspectHtmlInput(source, path, violations) {
  let analysis;
  try {
    analysis = parseHtmlBoundaryInput(path, source);
  } catch (error) {
    violations.push(`${path}: HTML parse failed (${error.message})`);
    return;
  }

  const reportedInfrastructure = new Set();
  function reportInfrastructure(label) {
    if (reportedInfrastructure.has(label)) return;
    reportedInfrastructure.add(label);
    violations.push(`${path}: forbidden infrastructure reference (${label})`);
  }

  for (const attribute of analysis.urlAttributes) {
    for (const candidate of htmlUrlCandidates(attribute)) {
      inspectHtmlUrlCandidate(candidate, reportInfrastructure);
    }
  }

  for (const inlineModule of analysis.inlineModules) {
    try {
      const programs = parsePrograms(`${inlineModule.label}.mjs`, inlineModule.source);
      for (const { program } of programs) {
        inspectBusinessProgram(program, inlineModule.label, violations);
      }
    } catch (error) {
      violations.push(`${inlineModule.label}: source parse failed (${error.message})`);
    }
  }
}

function directNativeFetchCall(path) {
  if (!path.isCallExpression() || path.node.optional) return false;
  const callee = unwrapPath(path.get("callee"));
  return (
    callee?.isIdentifier({ name: "fetch" }) &&
    isUnshadowedGlobal(callee, "fetch")
  );
}

function isDirectFetchCalleeReference(path) {
  let current = path;
  while (
    current.parentPath?.node &&
    transparentPathTypes.has(current.parentPath.node.type) &&
    current.parentPath.node.type !== "AwaitExpression"
  ) {
    current = current.parentPath;
  }
  return (
    current.parentPath?.isCallExpression() &&
    !current.parentPath.node.optional &&
    current.parentPath.get("callee").node === current.node &&
    directNativeFetchCall(current.parentPath)
  );
}

function isSafeRelativeApiTarget(value) {
  if (typeof value !== "string" || !value.startsWith("/api/")) return false;
  const rawPath = value.split(/[?#]/, 1)[0];
  if (value.startsWith("//") || rawPath.includes("\\")) return false;
  if (/%(?:25)*(?:2e|2f|5c)/i.test(rawPath)) return false;
  if (rawPath.split("/").some((segment) => segment === "." || segment === "..")) {
    return false;
  }
  try {
    const base = new URL("https://xzwl.invalid/");
    const resolved = new URL(value, base);
    return resolved.origin === base.origin && resolved.pathname.startsWith("/api/");
  } catch {
    return false;
  }
}

function inspectGeneratedProgram(program, path, violations) {
  inspectSemanticInfrastructure(program, path, violations);
  let fetchCalls = 0;
  let exportedCapability = false;
  let forbiddenNetworkCapability = false;
  let invalidFetchCapability = false;
  traverse(program, {
    ImportDeclaration(importPath) {
      if (
        moduleName(importPath.node.source) === "axios" &&
        hasRuntimeImports(importPath.node)
      ) {
        forbiddenNetworkCapability = true;
      }
    },
    ImportExpression(importPath) {
      if (moduleName(importPath.node.source) === "axios") {
        forbiddenNetworkCapability = true;
      }
    },
    ReferencedIdentifier(identifierPath) {
      if (isTypeOnlyReference(identifierPath)) return;
      const kind = resolveNetworkPath(identifierPath);
      if (kind === "fetch") {
        if (!isDirectFetchCalleeReference(identifierPath)) {
          invalidFetchCapability = true;
          if (
            identifierPath.findParent(
              (ancestor) =>
                ancestor.isExportNamedDeclaration() ||
                ancestor.isExportDefaultDeclaration()
            )
          ) {
            exportedCapability = true;
          }
        }
      } else if (kind === "axios" || kind === "direct") {
        forbiddenNetworkCapability = true;
      }
    },
    MemberExpression(memberPath) {
      const kind = resolveNetworkPath(memberPath);
      if (kind === "fetch") invalidFetchCapability = true;
      if (kind === "axios" || kind === "direct") forbiddenNetworkCapability = true;
    },
    OptionalMemberExpression(memberPath) {
      const kind = resolveNetworkPath(memberPath);
      if (kind === "fetch") invalidFetchCapability = true;
      if (kind === "axios" || kind === "direct") forbiddenNetworkCapability = true;
    },
    VariableDeclarator(declarationPath) {
      for (const name of Object.keys(declarationPath.getBindingIdentifiers())) {
        const binding = declarationPath.scope.getBinding(name);
        if (!binding) continue;
        const kind = resolveBindingKind(binding, new Set());
        if (kind === "fetch") invalidFetchCapability = true;
        if (kind === "axios" || kind === "direct") forbiddenNetworkCapability = true;
      }
    },
    "CallExpression|OptionalCallExpression|NewExpression"(callPath) {
      if (isAxiosLoaderPath(callPath)) forbiddenNetworkCapability = true;
      if (!directNativeFetchCall(callPath)) return;
      fetchCalls += 1;
      const targetPath = callPath.get("arguments")[0];
      const target = targetPath?.node && !targetPath.isSpreadElement()
        ? staticStringAt(targetPath)
        : null;
      if (!isSafeRelativeApiTarget(target)) {
        violations.push(
          `${path}: generated SDK request target must be statically relative /api/**`
        );
      }
    }
  });
  if (forbiddenNetworkCapability) {
      violations.push(`${path}: generated SDK may only use fetch for network access`);
  }
  if (invalidFetchCapability) {
    violations.push(
      `${path}: generated SDK request target must be statically relative /api/**`
    );
  }
  if (exportedCapability) {
    violations.push(`${path}: generated SDK must not export raw network capability`);
  }
  return fetchCalls;
}

function isOrvalDefineConfigBinding(binding) {
  return (
    isRuntimeBinding(binding) &&
    binding.path.isImportSpecifier() &&
    propertyName(binding.path.node.imported) === "defineConfig" &&
    moduleName(binding.path.parentPath.node.source) === "orval"
  );
}

function resolveObjectPath(path, seenBindings = new Set()) {
  const value = unwrapPath(path);
  if (!value?.node) return null;
  if (value.isObjectExpression()) return value;
  if (value.isIdentifier()) {
    const binding = value.scope.getBinding(value.node.name);
    if (
      !isRuntimeBinding(binding) ||
      seenBindings.has(binding) ||
      !binding.path.isVariableDeclarator() ||
      binding.kind !== "const" ||
      !binding.constant ||
      !binding.path.get("id").isIdentifier()
    ) {
      return null;
    }
    const initializer = binding.path.get("init");
    return initializer.node
      ? resolveObjectPath(initializer, new Set(seenBindings).add(binding))
      : null;
  }
  if (value.isCallExpression() && !value.node.optional) {
    const callee = unwrapPath(value.get("callee"));
    const binding = callee?.isIdentifier()
      ? callee.scope.getBinding(callee.node.name)
      : null;
    const argumentsPaths = value.get("arguments");
    if (
      !isOrvalDefineConfigBinding(binding) ||
      argumentsPaths.length !== 1 ||
      argumentsPaths[0].isSpreadElement()
    ) {
      return null;
    }
    return resolveObjectPath(argumentsPaths[0], seenBindings);
  }
  return null;
}

function resolvedObjectEntries(path, seenObjects = new Set()) {
  const objectPath = resolveObjectPath(path);
  if (!objectPath || seenObjects.has(objectPath.node)) return null;
  const nextSeen = new Set(seenObjects).add(objectPath.node);
  const entries = [];
  for (const propertyPath of objectPath.get("properties")) {
    if (propertyPath.isSpreadElement()) {
      const spread = resolvedObjectEntries(propertyPath.get("argument"), nextSeen);
      if (!spread) return null;
      entries.push(...spread);
      continue;
    }
    if (!propertyPath.isObjectProperty()) return null;
    const key = propertyPath.node.computed
      ? staticStringAt(propertyPath.get("key"))
      : propertyName(propertyPath.node.key);
    if (key === null) return null;
    entries.push({ key, valuePath: propertyPath.get("value") });
  }
  return entries;
}

function hasUniqueKeys(entries) {
  return entries !== null && new Set(entries.map((entry) => entry.key)).size === entries.length;
}

const orvalDynamicScalarKeys = new Set(["input", "schemas", "target"]);

function inspectOrvalTree(
  path,
  sourcePath,
  violations,
  seenObjects = new Set(),
  allowDynamicScalar = false
) {
  const objectPath = resolveObjectPath(path);
  if (objectPath) {
    if (seenObjects.has(objectPath.node)) return false;
    const entries = resolvedObjectEntries(objectPath, seenObjects);
    if (!hasUniqueKeys(entries)) return false;
    const nextSeen = new Set(seenObjects).add(objectPath.node);
    for (const entry of entries) {
      if (entry.key === "baseURL" || entry.key === "baseUrl") {
        violations.push(`${sourcePath}: Orval baseURL is forbidden`);
      }
      if (entry.key === "mutator") {
        violations.push(`${sourcePath}: Orval mutator is forbidden`);
      }
      if (
        !inspectOrvalTree(
          entry.valuePath,
          sourcePath,
          violations,
          nextSeen,
          orvalDynamicScalarKeys.has(entry.key)
        )
      ) {
        return false;
      }
    }
    return true;
  }

  const value = unwrapPath(path);
  if (!value?.node) return false;
  if (value.isArrayExpression()) {
    for (const element of value.get("elements")) {
      if (!element.node || element.isSpreadElement()) return false;
      if (!inspectOrvalTree(element, sourcePath, violations, seenObjects)) return false;
    }
    return true;
  }
  if (value.isBooleanLiteral() || value.isNumericLiteral() || value.isNullLiteral()) return true;
  return staticStringAt(value) !== null || allowDynamicScalar;
}

function inspectOrvalConfig(programs, path, violations) {
  let defaultCount = 0;
  for (const { program } of programs) {
    const defaults = [];
    traverse(program, {
      ExportDefaultDeclaration(exportPath) {
        defaults.push(exportPath.get("declaration"));
      }
    });
    defaultCount += defaults.length;
    for (const declarationPath of defaults) {
      const rootEntries = resolvedObjectEntries(declarationPath);
      if (!hasUniqueKeys(rootEntries) || rootEntries.length === 0) {
        violations.push(`${path}: Orval output must be statically analyzable`);
        continue;
      }
      for (const operation of rootEntries) {
        const operationEntries = resolvedObjectEntries(operation.valuePath);
        if (!hasUniqueKeys(operationEntries)) {
          violations.push(`${path}: Orval operation must be statically analyzable`);
          continue;
        }
        const outputs = operationEntries.filter((entry) => entry.key === "output");
        if (outputs.length !== 1) {
          violations.push(`${path}: Orval operation output must be statically analyzable`);
          continue;
        }
        const outputEntries = resolvedObjectEntries(outputs[0].valuePath);
        if (!hasUniqueKeys(outputEntries)) {
          violations.push(`${path}: Orval output must be statically analyzable`);
          continue;
        }
        const clients = outputEntries.filter((entry) => entry.key === "client");
        if (clients.length !== 1 || staticStringAt(clients[0].valuePath) !== "fetch") {
          violations.push(`${path}: Orval client must be fetch`);
        }
        if (!inspectOrvalTree(operation.valuePath, path, violations)) {
          violations.push(`${path}: Orval output must be statically analyzable`);
        }
      }
    }
  }
  if (defaultCount !== 1) violations.push(`${path}: Orval output must be statically analyzable`);
}

function localDependencySpecifier(specifier) {
  if (typeof specifier !== "string") return null;
  if (specifier.startsWith("file:")) return specifier.slice("file:".length);
  if (specifier.startsWith("./") || specifier.startsWith("../")) return specifier;
  return null;
}

function pathIsContained(root, candidate) {
  const difference = relative(root, candidate);
  return difference === "" || (!difference.startsWith(`..${sep}`) && difference !== ".." && !isAbsolute(difference));
}

function resolveManifestDependency(
  manifest,
  entry,
  manifests,
  byName,
  repositoryRoot,
  violations
) {
  const coordinate = dependencyCoordinate(entry);
  const localSpecifier = localDependencySpecifier(entry.specifier);
  if (localSpecifier !== null) {
    if (
      localSpecifier === "" ||
      localSpecifier.includes("\\") ||
      isAbsolute(localSpecifier)
    ) {
      violations.push(`${manifest.path}: workspace dependency escapes repository root`);
      return { coordinate, internal: null };
    }
    const targetPath = resolve(repositoryRoot, manifest.directory, localSpecifier);
    if (!pathIsContained(repositoryRoot, targetPath)) {
      violations.push(`${manifest.path}: workspace dependency escapes repository root`);
      return { coordinate, internal: null };
    }
    const targetDirectory = relative(repositoryRoot, targetPath).split(sep).join("/");
    const internal = manifests.get(targetDirectory) ?? null;
    if (!internal) {
      violations.push(`${manifest.path}: local workspace dependency must resolve to one manifest`);
      return { coordinate, internal: null };
    }
    if (entry.declaredName !== internal.value.name) {
      violations.push(`${manifest.path}: local workspace dependency name mismatch`);
    }
    return { coordinate: internal.value.name, internal };
  }

  if (typeof entry.specifier === "string" && entry.specifier.startsWith("workspace:")) {
    const internal = byName.get(coordinate) ?? null;
    if (!internal) {
      violations.push(`${manifest.path}: workspace dependency must resolve to one manifest (${coordinate})`);
    }
    return { coordinate, internal };
  }

  return { coordinate, internal: byName.get(coordinate) ?? null };
}

function inspectManifestPolicies(manifests, repositoryRoot, violations) {
  const candidatesByName = new Map();
  for (const manifest of manifests.values()) {
    if (typeof manifest.value.name === "string") {
      const candidates = candidatesByName.get(manifest.value.name) ?? [];
      candidates.push(manifest);
      candidatesByName.set(manifest.value.name, candidates);
    } else {
      violations.push(`${manifest.path}: workspace manifest must have a package name`);
    }
  }

  const byName = new Map();
  for (const [name, candidates] of candidatesByName) {
    if (candidates.length === 1) {
      byName.set(name, candidates[0]);
    } else {
      violations.push(
        `${candidates.map((candidate) => candidate.path).join(", ")}: duplicate workspace package name (${name})`
      );
    }
  }

  const graph = new Map();
  for (const manifest of manifests.values()) {
    const resolvedDependencies = dependencyEntries(manifest.value).map((entry) =>
      resolveManifestDependency(
        manifest,
        entry,
        manifests,
        byName,
        repositoryRoot,
        violations
      )
    );
    graph.set(manifest.path, resolvedDependencies);
    for (const dependency of resolvedDependencies) {
      if (unauthorizedNetworkClients.has(dependency.coordinate)) {
        violations.push(
          `${manifest.path}: unauthorized network client dependency (${dependency.coordinate})`
        );
      }
      if (/nacos/i.test(dependency.coordinate)) {
        violations.push(`${manifest.path}: forbidden infrastructure reference (Nacos)`);
      }
    }
  }

  for (const rule of appRules) {
    const start = manifests.get(`frontends/${rule.directory}`);
    if (!start) {
      violations.push(`frontends/${rule.directory}/package.json: missing app manifest`);
      continue;
    }
    if (start.value.name !== rule.packageName) {
      violations.push(
        `${start.path}: app manifest name must be ${rule.packageName}`
      );
    }
    const opposite = rule.framework === "vue" ? reactCoordinates : vueCoordinates;
    const visited = new Set();
    const stack = [start];
    let crossed = false;
    while (stack.length > 0 && !crossed) {
      const current = stack.pop();
      if (visited.has(current.path)) continue;
      visited.add(current.path);
      for (const dependency of graph.get(current.path) ?? []) {
        if (opposite.has(dependency.coordinate)) {
          const target = rule.framework === "vue" ? "React" : "Vue";
          violations.push(
            `${rule.directory}: ${target === "React" ? "Vue" : "React"} app must not reach ${target} through workspace dependencies`
          );
          crossed = true;
          break;
        }
        if (dependency.internal && !visited.has(dependency.internal.path)) {
          stack.push(dependency.internal);
        }
      }
    }
  }
  return graph;
}

export async function validateFrontendBoundaries(repositoryRoot) {
  const violations = [];
  const files = await collectFrontendFiles(repositoryRoot, violations);
  if (files.length === 0 && violations.some((violation) => violation.includes("root must"))) {
    return violations;
  }

  const sources = new Map();
  for (const file of files) {
    try {
      sources.set(file.relativePath, await readFile(file.path, "utf8"));
    } catch (error) {
      violations.push(`${file.relativePath}: read failed (${error.message})`);
    }
  }

  let rootManifest = null;
  try {
    rootManifest = JSON.parse(sources.get("package.json") ?? "");
  } catch (error) {
    violations.push(`package.json: invalid package.json (${error.message})`);
  }
  const workspacePatterns = normalizedWorkspacePatterns(rootManifest, violations);
  const manifests = new Map();
  const parsedPrograms = new Map();
  const appSdkImports = new Map(appRules.map((rule) => [rule.directory, false]));

  for (const file of files) {
    const source = sources.get(file.relativePath);
    if (source === undefined) continue;
    if (isFrontendManifest(file.relativePath)) {
      const directory = dirname(file.relativePath).split(sep).join("/");
      const matchingPatterns = workspacePatterns.filter((pattern) =>
        matchesWorkspacePattern(directory, pattern)
      );
      if (matchingPatterns.length === 0) {
        violations.push(
          `${file.relativePath}: frontend manifest is not covered by root workspaces`
        );
      } else if (matchingPatterns.length > 1) {
        violations.push(
          `${file.relativePath}: frontend manifest is covered by multiple root workspace patterns`
        );
      }
      try {
        manifests.set(directory, {
          directory,
          path: file.relativePath,
          value: JSON.parse(source)
        });
      } catch (error) {
        violations.push(`${file.relativePath}: invalid package.json (${error.message})`);
      }
    }
    if (isHtmlPath(file.relativePath)) {
      inspectHtmlInput(source, file.relativePath, violations);
    }
    if (!isScriptPath(file.path)) continue;
    try {
      parsedPrograms.set(file.relativePath, parsePrograms(file.relativePath, source));
    } catch (error) {
      violations.push(`${file.relativePath}: source parse failed (${error.message})`);
    }
  }

  for (const pattern of workspacePatterns) {
    if (![...manifests.keys()].some((directory) => matchesWorkspacePattern(directory, pattern))) {
      violations.push(`package.json: workspace pattern has no manifest (${pattern})`);
    }
  }

  const dependencyGraph = inspectManifestPolicies(manifests, repositoryRoot, violations);

  let generatedFetchCalls = 0;
  for (const [relativePath, programs] of parsedPrograms) {
    if (relativePath === "frontends/packages/api-contracts/orval.config.ts") {
      inspectOrvalConfig(programs, relativePath, violations);
    }
    if (isGeneratedSdk(relativePath)) {
      for (const { program } of programs) {
        generatedFetchCalls += inspectGeneratedProgram(program, relativePath, violations);
      }
      continue;
    }
    for (const { program } of programs) {
      inspectBusinessProgram(program, relativePath, violations);
      const appDirectory = appDirectoryFor(relativePath);
      if (appDirectory && importsSdk(program)) appSdkImports.set(appDirectory, true);
    }
  }

  if (generatedFetchCalls === 0 && files.some((file) => isGeneratedSdk(file.relativePath))) {
    violations.push("generated SDK must contain at least one statically safe fetch call");
  }
  for (const rule of appRules) {
    const manifest = manifests.get(`frontends/${rule.directory}`);
    if (
      !manifest ||
      !(dependencyGraph.get(manifest.path) ?? []).some(
        (dependency) => dependency.coordinate === "@xzwl/api-contracts"
      )
    ) {
      violations.push(`${rule.directory}: app must depend on @xzwl/api-contracts`);
    }
    if (!appSdkImports.get(rule.directory)) {
      violations.push(`${rule.directory}: app source must import @xzwl/api-contracts`);
    }
  }

  return [...new Set(violations)].sort();
}
