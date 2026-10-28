import { parse as parseJavaScript } from "@babel/parser";
import { parse as parseVue } from "@vue/compiler-sfc";
import { extname } from "node:path";

const scriptExtensions = new Set([
  ".cjs",
  ".cts",
  ".js",
  ".jsx",
  ".mjs",
  ".mts",
  ".ts",
  ".tsx"
]);

const transparentExpressionTypes = new Set([
  "ParenthesizedExpression",
  "TSAsExpression",
  "TSInstantiationExpression",
  "TSNonNullExpression",
  "TSSatisfiesExpression",
  "TSTypeAssertion",
  "TypeCastExpression"
]);

export function isScriptPath(path) {
  return path.endsWith(".vue") || scriptExtensions.has(extname(path));
}

function babelParse(source, filename) {
  return parseJavaScript(source, {
    sourceFilename: filename,
    sourceType: "unambiguous",
    createImportExpressions: true,
    plugins: ["decorators-legacy", "importAttributes", "jsx", "typescript"]
  });
}

export function parsePrograms(path, source) {
  if (!path.endsWith(".vue")) {
    return [{ label: path, program: babelParse(source, path) }];
  }

  const parsed = parseVue(source, { filename: path, sourceMap: false });
  if (parsed.errors.length > 0) {
    const summary = parsed.errors.map((error) => String(error.message ?? error)).join("; ");
    throw new Error(`Vue SFC parse failed: ${summary}`);
  }
  const blocks = [
    ["script", parsed.descriptor.script],
    ["script setup", parsed.descriptor.scriptSetup]
  ].filter((entry) => entry[1] !== null);
  return blocks.map(([kind, block]) => ({
    label: `${path}#${kind}`,
    program: babelParse(block.content, `${path}#${kind}`)
  }));
}

export function walkAst(node, visit, parent = null) {
  if (node === null || typeof node !== "object") return;
  if (typeof node.type === "string") visit(node, parent);
  for (const [key, value] of Object.entries(node)) {
    if (
      key === "comments" ||
      key === "errors" ||
      key === "extra" ||
      key === "leadingComments" ||
      key === "loc" ||
      key === "trailingComments"
    ) {
      continue;
    }
    if (Array.isArray(value)) {
      for (const child of value) walkAst(child, visit, node);
    } else if (value !== null && typeof value === "object") {
      walkAst(value, visit, node);
    }
  }
}

export function unwrapExpression(node) {
  let current = node;
  while (current && transparentExpressionTypes.has(current.type)) current = current.expression;
  return current;
}

export function propertyName(node) {
  const value = unwrapExpression(node);
  if (value?.type === "Identifier") return value.name;
  if (value?.type === "StringLiteral") return value.value;
  return null;
}

export function moduleName(node) {
  const value = unwrapExpression(node);
  if (value?.type === "StringLiteral") return value.value;
  if (value?.type === "TemplateLiteral" && value.expressions.length === 0) {
    return value.quasis[0]?.value.cooked ?? "";
  }
  if (value?.type === "BinaryExpression" && value.operator === "+") {
    const left = moduleName(value.left);
    const right = moduleName(value.right);
    return left === null || right === null ? null : left + right;
  }
  return null;
}

const transparentPathTypes = new Set(transparentExpressionTypes);

function unwrapPath(path) {
  let current = path;
  while (current?.node && transparentPathTypes.has(current.node.type)) {
    current = current.get("expression");
  }
  return current;
}

function directReturnPath(functionPath) {
  if (!functionPath || functionPath.node.params.length !== 0) return null;
  const body = functionPath.get("body");
  if (!body.isBlockStatement()) return body;
  const statements = body.get("body");
  if (statements.length !== 1 || !statements[0].isReturnStatement()) return null;
  const argument = statements[0].get("argument");
  return argument.node ? argument : null;
}

function runtimeConstantInitializer(binding) {
  if (
    !binding?.path.isVariableDeclarator() ||
    binding.path.parentPath?.node.declare === true ||
    binding.kind !== "const" ||
    !binding.constant ||
    !binding.path.get("id").isIdentifier()
  ) {
    return null;
  }
  const initializer = binding.path.get("init");
  return initializer.node ? initializer : null;
}

/**
 * Resolve only small, deterministic string expressions through the Babel binding
 * visible at this exact expression path. This deliberately does not build a
 * file-wide name map: lexical shadowing and binding identity are part of the proof.
 */
export function staticStringAt(path, seenBindings = new Set()) {
  const value = unwrapPath(path);
  if (!value?.node) return null;
  if (value.isStringLiteral()) return value.node.value;
  if (value.isTemplateLiteral()) {
    const expressions = value.get("expressions");
    const quasis = value.node.quasis;
    let result = quasis[0]?.value.cooked ?? "";
    for (let index = 0; index < expressions.length; index += 1) {
      const expression = staticStringAt(expressions[index], seenBindings);
      if (expression === null) return null;
      result += expression + (quasis[index + 1]?.value.cooked ?? "");
    }
    return result;
  }
  if (value.isBinaryExpression({ operator: "+" })) {
    const left = staticStringAt(value.get("left"), seenBindings);
    const right = staticStringAt(value.get("right"), seenBindings);
    return left === null || right === null ? null : left + right;
  }
  if (value.isIdentifier()) {
    const binding = value.scope.getBinding(value.node.name);
    if (!binding || seenBindings.has(binding)) return null;
    const initializer = runtimeConstantInitializer(binding);
    if (!initializer) return null;
    return staticStringAt(initializer, new Set(seenBindings).add(binding));
  }
  if (
    (value.isCallExpression() || value.isOptionalCallExpression()) &&
    !value.node.optional &&
    value.node.arguments.length === 0
  ) {
    const callee = unwrapPath(value.get("callee"));
    if (!callee?.isIdentifier()) return null;
    const binding = callee.scope.getBinding(callee.node.name);
    if (!binding || seenBindings.has(binding)) return null;
    let functionPath = null;
    if (binding.path.isFunctionDeclaration() && binding.constant) {
      functionPath = binding.path;
    } else {
      const initializer = runtimeConstantInitializer(binding);
      const candidate = unwrapPath(initializer);
      if (candidate?.isArrowFunctionExpression() || candidate?.isFunctionExpression()) {
        functionPath = candidate;
      }
    }
    const returned = directReturnPath(functionPath);
    return returned
      ? staticStringAt(returned, new Set(seenBindings).add(binding))
      : null;
  }
  return null;
}
