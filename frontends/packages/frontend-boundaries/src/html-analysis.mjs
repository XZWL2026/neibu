import { parse } from "parse5";

const urlAttributesByElement = {
  a: { href: "single", ping: "space-separated" },
  area: { href: "single", ping: "space-separated" },
  audio: { src: "single" },
  base: { href: "single" },
  blockquote: { cite: "single" },
  button: { formaction: "single" },
  del: { cite: "single" },
  embed: { src: "single" },
  form: { action: "single" },
  html: { manifest: "single" },
  iframe: { src: "single" },
  image: { href: "single" },
  img: { src: "single", srcset: "srcset" },
  input: { formaction: "single", src: "single" },
  ins: { cite: "single" },
  link: { href: "single" },
  object: { data: "single" },
  q: { cite: "single" },
  script: { src: "single" },
  source: { src: "single", srcset: "srcset" },
  track: { src: "single" },
  use: { href: "single" },
  video: { poster: "single", src: "single" }
};

const xlinkNamespace = "http://www.w3.org/1999/xlink";

function attributesByName(node) {
  return new Map((node.attrs ?? []).map((attribute) => [attribute.name, attribute.value]));
}

function textContent(node) {
  return (node.childNodes ?? [])
    .filter((child) => child.nodeName === "#text")
    .map((child) => child.value)
    .join("");
}

export function parseHtmlBoundaryInput(path, source) {
  const parseErrors = [];
  const document = parse(source, {
    onParseError(error) {
      parseErrors.push(error.code);
    }
  });
  if (parseErrors.length > 0) {
    throw new Error(`HTML parse failed: ${[...new Set(parseErrors)].join(", ")}`);
  }

  const urlAttributes = [];
  const inlineModules = [];

  function walk(node) {
    if (typeof node.tagName === "string") {
      const attributes = attributesByName(node);
      for (const attribute of node.attrs ?? []) {
        if (
          attribute.name === "href" &&
          attribute.prefix === "xlink" &&
          attribute.namespace === xlinkNamespace
        ) {
          urlAttributes.push({ kind: "single", name: "xlink:href", value: attribute.value });
          continue;
        }
        const kind = urlAttributesByElement[node.tagName]?.[attribute.name];
        if (kind) {
          urlAttributes.push({ kind, name: attribute.name, value: attribute.value });
          continue;
        }
        if (
          node.tagName === "link" &&
          attribute.name === "imagesrcset" &&
          attributes.get("as")?.trim().toLowerCase() === "image" &&
          attributes
            .get("rel")
            ?.split(/[\t\n\f\r ]+/)
            .some((token) => token.toLowerCase() === "preload")
        ) {
          urlAttributes.push({ kind: "srcset", name: attribute.name, value: attribute.value });
          continue;
        }
        if (
          node.tagName === "meta" &&
          attribute.name === "content" &&
          attributes.get("http-equiv")?.trim().toLowerCase() === "refresh"
        ) {
          urlAttributes.push({ kind: "refresh", name: attribute.name, value: attribute.value });
        }
      }
      if (
        node.tagName === "script" &&
        attributes.get("type")?.trim().toLowerCase() === "module" &&
        !attributes.has("src")
      ) {
        inlineModules.push({
          label: `${path}#inline-module-${inlineModules.length + 1}`,
          source: textContent(node)
        });
      }
    }
    if (node.content) walk(node.content);
    for (const child of node.childNodes ?? []) walk(child);
  }

  walk(document);
  return { inlineModules, urlAttributes };
}
