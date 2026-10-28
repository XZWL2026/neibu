import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { validateFrontendBoundaries } from "../src/validate-frontend-boundaries.mjs";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../../../..");
const sdkImport = 'import { getPlatformSmoke } from "@xzwl/api-contracts";';

function vueSource(body, { importSdk = true } = {}) {
  return `<script setup lang="ts">
${importSdk ? sdkImport : ""}
${body}
</script>
<template><main>fixture</main></template>
`;
}

async function write(path, content) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, content);
}

async function writeJson(path, value) {
  await write(path, JSON.stringify(value, null, 2) + "\n");
}

async function writePackage(root, directory, manifest, source = "export {};\n") {
  await writeJson(join(root, directory, "package.json"), manifest);
  await write(join(root, directory, "index.ts"), source);
}

async function writeValidFixture(root) {
  await writeJson(join(root, "package.json"), {
    private: true,
    workspaces: ["frontends/*", "frontends/packages/*"]
  });
  await writeJson(join(root, "frontends/admin-web/package.json"), {
    name: "@xzwl/admin-web",
    dependencies: { "@xzwl/api-contracts": "*", vue: "3.5.13" }
  });
  await writeJson(join(root, "frontends/partner-web/package.json"), {
    name: "@xzwl/partner-web",
    dependencies: { "@xzwl/api-contracts": "*", vue: "3.5.13" }
  });
  await writeJson(join(root, "frontends/user-web/package.json"), {
    name: "@xzwl/user-web",
    dependencies: { "@xzwl/api-contracts": "*", react: "19.0.0" }
  });
  await writeJson(join(root, "frontends/packages/api-contracts/package.json"), {
    name: "@xzwl/api-contracts",
    version: "0.1.0"
  });
  await writeJson(join(root, "frontends/packages/frontend-boundaries/package.json"), {
    name: "@xzwl/frontend-boundaries",
    version: "0.1.0",
    private: true
  });
  await writePackage(root, "frontends/packages/protocol", {
    name: "@xzwl/protocol",
    version: "0.1.0"
  });
  await write(join(root, "frontends/admin-web/src/App.vue"), vueSource("void getPlatformSmoke;"));
  await write(join(root, "frontends/partner-web/src/App.vue"), vueSource("void getPlatformSmoke;"));
  await write(
    join(root, "frontends/user-web/src/App.tsx"),
    `${sdkImport}\nexport const App = () => <main>{String(getPlatformSmoke)}</main>;\n`
  );
  await write(
    join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
    `export const getUrl = () => \`/api/platform/smoke\`;
export const request = () => fetch(getUrl());
`
  );
  await write(
    join(root, "frontends/packages/api-contracts/orval.config.ts"),
    `import { defineConfig } from "orval";
export default defineConfig({ platformSmoke: { output: { client: "fetch" } } });
`
  );
}

async function withFixture(run) {
  const root = await mkdtemp(join(tmpdir(), "xzwl-frontend-boundaries-"));
  try {
    await writeValidFixture(root);
    await run(root);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

async function expectViolation(root, expected) {
  const violations = await validateFrontendBoundaries(root);
  assert.ok(
    violations.some((violation) => violation.includes(expected)),
    `expected violation containing ${JSON.stringify(expected)}, got:\n${violations.join("\n")}`
  );
}

test("accepts the valid fixture", () =>
  withFixture(async (root) => {
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("root npm test explicitly runs the boundary suite before workspace tests", async () => {
  const manifest = JSON.parse(await readFile(join(repositoryRoot, "package.json"), "utf8"));
  assert.equal(
    manifest.scripts?.test,
    "node frontends/packages/frontend-boundaries/tests/frontend-boundaries.test.mjs && npm run test --workspaces --if-present"
  );
});

test("root npm test cannot return raw zero when workspace tests omit the boundary suite", async () => {
  const root = await mkdtemp(join(tmpdir(), "xzwl-root-test-entry-"));
  try {
    const repositoryManifest = JSON.parse(
      await readFile(join(repositoryRoot, "package.json"), "utf8")
    );
    await writeJson(join(root, "package.json"), {
      private: true,
      workspaces: ["frontends/empty"],
      scripts: { test: repositoryManifest.scripts.test }
    });
    await writeJson(join(root, "frontends/empty/package.json"), {
      name: "@xzwl/empty",
      private: true
    });
    const result = spawnSync("npm", ["test"], { cwd: root, encoding: "utf8" });
    assert.notEqual(
      result.status,
      0,
      `root npm test unexpectedly returned raw 0 with no boundary suite:\n${result.stdout}\n${result.stderr}`
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("rejects a root workspace list that omits frontend-boundaries", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "package.json"), {
      private: true,
      workspaces: [
        "frontends/admin-web",
        "frontends/partner-web",
        "frontends/user-web",
        "frontends/packages/api-contracts",
        "frontends/packages/protocol"
      ]
    });
    await expectViolation(root, "frontends/packages/frontend-boundaries/package.json: frontend manifest is not covered by root workspaces");
  }));

test("rejects a root workspace list that omits a top-level frontend package", () =>
  withFixture(async (root) => {
    await writePackage(root, "frontends/local-shell", {
      name: "@xzwl/local-shell",
      version: "0.1.0"
    });
    await writeJson(join(root, "package.json"), {
      private: true,
      workspaces: [
        "frontends/admin-web",
        "frontends/partner-web",
        "frontends/user-web",
        "frontends/packages/*"
      ]
    });
    await expectViolation(root, "frontends/local-shell/package.json: frontend manifest is not covered by root workspaces");
  }));

for (const directory of ["build", "dist", "coverage"]) {
  test(`discovers a top-level frontend package named ${directory} before workspace validation`, () =>
    withFixture(async (root) => {
      await writePackage(root, `frontends/${directory}`, {
        name: `@xzwl/${directory}-tools`,
        version: "0.1.0"
      });
      await writeJson(join(root, "package.json"), {
        private: true,
        workspaces: [
          "frontends/admin-web",
          "frontends/partner-web",
          "frontends/user-web",
          "frontends/packages/*"
        ]
      });
      await expectViolation(
        root,
        `frontends/${directory}/package.json: frontend manifest is not covered by root workspaces`
      );
    }));
}

test("rejects a frontend manifest covered by multiple root workspace patterns", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "package.json"), {
      private: true,
      workspaces: [
        "frontends/*",
        "frontends/admin-web",
        "frontends/packages/*"
      ]
    });
    await expectViolation(root, "frontends/admin-web/package.json: frontend manifest is covered by multiple root workspace patterns");
  }));

for (const directory of ["build", "dist", "coverage"]) {
  test(`ignores package manifests inside a package's generated ${directory} directory`, () =>
    withFixture(async (root) => {
      await writeJson(join(root, `frontends/admin-web/${directory}/package.json`), {
        name: `generated-${directory}-artifact`
      });
      await write(
        join(root, `frontends/admin-web/${directory}/index.js`),
        'globalThis.fetch("/api/platform/smoke");\n'
      );
      assert.deepEqual(await validateFrontendBoundaries(root), []);
    }));
}

const htmlInternalAddressCases = [
  ["src", '<img alt="fixture" src="http://order-service:8080/health">'],
  ["protocol-relative src", '<img alt="fixture" src="//order-service:8080/health">'],
  ["href", '<a href="http://order-service:8080/help">Help</a>'],
  ["action", '<form action="http://order-service:8080/submit"></form>'],
  [
    "formaction",
    '<form><button formaction="http://order-service:8080/submit">Submit</button></form>'
  ],
  ["poster", '<video poster="http://order-service:8080/poster.jpg"></video>'],
  ["object data", '<object data="http://order-service:8080/health"></object>'],
  [
    "srcset",
    '<img alt="fixture" srcset="https://cdn.example.com/safe.png 1x, http://order-service:8080/unsafe.png 2x">'
  ],
  [
    "ping URL list",
    '<a href="https://example.com/help" ping="https://metrics.example.com/a http://order-service:8080/ping">Help</a>'
  ],
  [
    "area ping URL list",
    '<map name="fixture"><area href="https://example.com/help" ping="https://metrics.example.com/a http://order-service:8080/ping"></map>'
  ],
  ["cite", '<blockquote cite="http://order-service:8080/source">Quote</blockquote>'],
  [
    "xlink:href",
    '<svg xmlns:xlink="http://www.w3.org/1999/xlink"><use xlink:href="http://order-service:8080/icon.svg"></use></svg>'
  ]
];

for (const [attribute, element] of htmlInternalAddressCases) {
  test(`rejects a hard-coded internal service address in an HTML ${attribute} attribute`, () =>
    withFixture(async (root) => {
      await write(
        join(root, "frontends/admin-web/index.html"),
        `<!doctype html><html><body>${element}</body></html>\n`
      );
      await expectViolation(root, "forbidden infrastructure reference (hard-coded internal service address)");
    }));
}

test("rejects a hard-coded internal service address in an HTML manifest attribute", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html><html manifest="http://order-service:8080/app.manifest"><body></body></html>\n`
    );
    await expectViolation(root, "forbidden infrastructure reference (hard-coded internal service address)");
  }));

test("rejects a hard-coded internal service address in a meta refresh URL", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html><html><head><meta http-equiv="  ReFrEsH  " content=" 0 ; URL = 'http://order-service:8080/health' "></head></html>\n`
    );
    await expectViolation(root, "forbidden infrastructure reference (hard-coded internal service address)");
  }));

test("rejects a comma-delimited hard-coded internal service address in a meta refresh URL", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html><html><head><meta http-equiv="refresh" content="0, URL=http://order-service:8080/health"></head></html>\n`
    );
    await expectViolation(root, "forbidden infrastructure reference (hard-coded internal service address)");
  }));

test("does not split a comma inside a data URL srcset candidate", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html><html><body><img alt="fixture" srcset="data:text/plain,http://order-service:8080/health 1x"></body></html>\n`
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("rejects a real internal second srcset candidate after a data URL", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html><html><body><img alt="fixture" srcset="data:text/plain,safe 1x, http://order-service:8080/unsafe.png 2x"></body></html>\n`
    );
    await expectViolation(root, "forbidden infrastructure reference (hard-coded internal service address)");
  }));

test("rejects an internal link image preload imagesrcset candidate", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html><html><head><link rel="stylesheet PRELOAD" as=" IMAGE " imagesrcset="https://cdn.example.com/safe.png 1x, http://order-service:8080/unsafe.png 2x"></head></html>\n`
    );
    await expectViolation(root, "forbidden infrastructure reference (hard-coded internal service address)");
  }));

test("does not split a comma inside a link image preload data URL candidate", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html><html><head><link rel="preload" as="image" imagesrcset="data:text/plain,http://order-service:8080/health 1x"></head></html>\n`
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("rejects a real internal link image preload candidate after a data URL", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html><html><head><link rel="preload" as="image" imagesrcset="data:text/plain,safe 1x, http://order-service:8080/unsafe.png 2x"></head></html>\n`
    );
    await expectViolation(root, "forbidden infrastructure reference (hard-coded internal service address)");
  }));

test("does not treat imagesrcset as a URL outside link image preload semantics", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html><html><head>
  <link rel="stylesheet" as="image" imagesrcset="http://order-service:8080/stylesheet.png 1x">
  <link rel="preload" as="script" imagesrcset="http://order-service:8080/script.png 1x">
</head></html>\n`
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

const htmlInfrastructureCases = [
  ["Nacos", '<link rel="preload" href="nacos://config-service/config">'],
  ["lb://", '<form action="lb://order-service/api/orders"></form>'],
  ["/internal/", '<a href="/internal/orders">Orders</a>']
];

for (const [label, element] of htmlInfrastructureCases) {
  test(`rejects ${label} in an HTML URL-bearing attribute`, () =>
    withFixture(async (root) => {
      await write(
        join(root, "frontends/admin-web/index.html"),
        `<!doctype html><html><head>${element}</head><body></body></html>\n`
      );
      await expectViolation(root, `forbidden infrastructure reference (${label})`);
    }));
}

test("rejects direct fetch access in an inline module script", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html><html><body><script type="module">fetch("/api/platform/smoke");</script></body></html>\n`
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("rejects direct native network capability in an inline module script", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html><html><body><script type="module">const socket = new WebSocket("wss://events.example.com"); void socket;</script></body></html>\n`
    );
    await expectViolation(root, "business code must not access direct network capability");
  }));

test("checks URL attributes and inline modules inside an HTML template element", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html>
<html><body><template>
  <img alt="fixture" src="http://order-service:8080/health">
  <script type="module">fetch("/api/platform/smoke");</script>
</template></body></html>
`
    );
    await expectViolation(root, "forbidden infrastructure reference (hard-coded internal service address)");
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("does not treat HTML comments, display text, or external documentation links as requests", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/index.html"),
      `<!doctype html>
<html>
  <body>
    <!-- <img src="http://order-service:8080/health"> fetch("/internal/orders") -->
    <p>http://order-service:8080/health and /internal/orders are documentation examples.</p>
    <a href="https://example.com/help">External help</a>
    <a href="https://docs.example.com/internal/setup">External internal-path documentation</a>
    <a href="https://nacos.io/docs">External Nacos documentation</a>
    <object data="https://docs.example.com/help"></object>
    <div data-endpoint="http://order-service:8080/health">Custom data attribute</div>
    <meta name="description" content="http://order-service:8080/health">
    <script type="module">
      // fetch("/api/platform/smoke");
      const documentationLink = "https://example.com/help";
      void documentationLink;
    </script>
  </body>
</html>
`
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("rejects a comment-forged SDK import combined with window bracket fetch", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(
        `// import { getPlatformSmoke } from "@xzwl/api-contracts";
window["fetch"]("/api/platform/smoke");`,
        { importSdk: false }
      )
    );
    await expectViolation(root, "business code must not access fetch directly");
    await expectViolation(root, "app source must import @xzwl/api-contracts");
  }));

const fetchBypasses = [
  ["globalThis.fetch", 'globalThis.fetch("/api/platform/smoke");'],
  ["globalThis bracket fetch", 'globalThis["fetch"]("/api/platform/smoke");'],
  ["optional fetch call", 'globalThis.fetch?.("/api/platform/smoke");'],
  ["fetch alias", 'const request = fetch; request("/api/platform/smoke");'],
  [
    "destructured fetch alias",
    'const { fetch: request } = globalThis; request("/api/platform/smoke");'
  ]
];

for (const [name, source] of fetchBypasses) {
  test(`rejects ${name}`, () =>
    withFixture(async (root) => {
      await write(join(root, "frontends/admin-web/src/App.vue"), vueSource(source));
      await expectViolation(root, "business code must not access fetch directly");
    }));
}

const axiosBypasses = [
  ["axios ESM import", 'import axios from "axios"; axios.get("/api/platform/smoke");'],
  [
    "axios dynamic import",
    'const axiosModule = await import("axios"); axiosModule.default.get("/api/platform/smoke");'
  ],
  ["axios require", 'const client = require("axios"); client.get("/api/platform/smoke");'],
  [
    "axios alias and optional member call",
    'import client from "axios"; const request = client; request?.get?.("/api/platform/smoke");'
  ]
];

for (const [name, source] of axiosBypasses) {
  test(`rejects ${name}`, () =>
    withFixture(async (root) => {
      await write(join(root, "frontends/admin-web/src/App.vue"), vueSource(source));
      await expectViolation(root, "business code must not import or access axios");
    }));
}

const nativeNetworkCapabilities = [
  ["XMLHttpRequest", "const request = new XMLHttpRequest(); void request;"],
  [
    "aliased XMLHttpRequest",
    "const Request = window.XMLHttpRequest; const request = new Request(); void request;"
  ],
  ["WebSocket", 'const socket = new WebSocket("wss://events.example.com"); void socket;'],
  ["EventSource", 'const stream = new EventSource("/api/events"); void stream;'],
  [
    "navigator.sendBeacon optional member call",
    'navigator.sendBeacon?.("/api/telemetry", "fixture");'
  ]
];

for (const [name, source] of nativeNetworkCapabilities) {
  test(`rejects direct ${name}`, () =>
    withFixture(async (root) => {
      await write(join(root, "frontends/admin-web/src/App.vue"), vueSource(source));
      await expectViolation(root, "business code must not access direct network capability");
    }));
}

test("rejects acquiring fetch without immediately calling it", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource('export const request = window["fetch"];')
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("rejects acquiring a native network constructor without immediately calling it", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource("export const Socket = globalThis.WebSocket;")
    );
    await expectViolation(root, "business code must not access direct network capability");
  }));

test("rejects assigning fetch to an alias after declaration", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource('let request; request = globalThis["fetch"]; request("/api/platform/smoke");')
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("rejects nested acquisition of fetch in an object wrapper", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource('export const capabilities = { request: window["fetch"] };')
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("rejects a destructured sendBeacon alias", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource('const { sendBeacon: beacon } = navigator; beacon("/api/telemetry", "x");')
    );
    await expectViolation(root, "business code must not access direct network capability");
  }));

test("rejects a nested destructured sendBeacon alias", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(
        'const { navigator: { sendBeacon: beacon } } = globalThis; beacon("/api/telemetry", "x");'
      )
    );
    await expectViolation(root, "business code must not access direct network capability");
  }));

test("rejects sendBeacon reached through globalThis.navigator", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource('globalThis.navigator.sendBeacon("/api/telemetry", "x");')
    );
    await expectViolation(root, "business code must not access direct network capability");
  }));

test("rejects bare fetch wrapped in an object", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource('const capabilities = { request: fetch }; capabilities.request("/api/platform/smoke");')
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("rejects fetch through a global object alias assigned after declaration", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource('let browser; browser = window; browser.fetch("/api/platform/smoke");')
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("rejects a global alias whose assignments expose multiple network roots", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(
        'let root = window; root = navigator; root.sendBeacon("/api/telemetry", "x");'
      )
    );
    await expectViolation(root, "business code must not access direct network capability");
  }));

test("rejects fetch reached through a zero-argument global object wrapper", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource('const browser = () => window; browser().fetch("/api/platform/smoke");')
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("does not treat type-only fetch identifiers as runtime network access", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(`interface CacheClient { fetch(key: string): string }
type NativeFetch = typeof fetch;
void getPlatformSmoke;`)
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("does not mistake a lexically shadowed local fetch function for the global", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource('const fetch = (value: string) => value; fetch("cache-key"); void getPlatformSmoke;')
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("rejects an unauthorized network client dependency in an app manifest", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/admin-web/package.json"), {
      name: "@xzwl/admin-web",
      dependencies: { "@xzwl/api-contracts": "*", vue: "3.5.13", axios: "1.8.0" }
    });
    await expectViolation(root, "unauthorized network client dependency");
  }));

test("rejects an unauthorized network client dependency in a shared workspace", () =>
  withFixture(async (root) => {
    await writePackage(root, "frontends/packages/network-wrapper", {
      name: "@xzwl/network-wrapper",
      dependencies: { ky: "1.7.5" }
    });
    await expectViolation(root, "unauthorized network client dependency");
  }));

test("rejects an npm-aliased unauthorized network client dependency", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/admin-web/package.json"), {
      name: "@xzwl/admin-web",
      dependencies: {
        "@xzwl/api-contracts": "*",
        client: "npm:axios@1.8.0",
        vue: "3.5.13"
      }
    });
    await expectViolation(root, "unauthorized network client dependency (axios)");
  }));

test("rejects an npm-aliased opposite framework dependency", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/admin-web/package.json"), {
      name: "@xzwl/admin-web",
      dependencies: {
        "@xzwl/api-contracts": "*",
        ui: "npm:react@19.0.0",
        vue: "3.5.13"
      }
    });
    await expectViolation(root, "Vue app must not reach React through workspace dependencies");
  }));

for (const extension of ["mts", "cts"]) {
  test(`rejects direct network access in .${extension} source`, () =>
    withFixture(async (root) => {
      await write(
        join(root, `frontends/admin-web/outside.${extension}`),
        'globalThis["fetch"]("/api/platform/smoke");\n'
      );
      await write(
        join(root, "frontends/admin-web/src/App.vue"),
        vueSource(`import "../outside.${extension}";\nvoid getPlatformSmoke;`)
      );
      await expectViolation(root, "business code must not access fetch directly");
    }));
}

test("rejects a source file symlink", () =>
  withFixture(async (root) => {
    const target = join(root, "unsafe-target.ts");
    await write(target, 'globalThis["fetch"]("/api/platform/smoke");\n');
    await symlink(target, join(root, "frontends/admin-web/linked.ts"));
    await expectViolation(root, "unsupported filesystem entry");
  }));

test("rejects a source FIFO", () =>
  withFixture(async (root) => {
    const fifo = join(root, "frontends/admin-web/request.cts");
    const created = spawnSync("mkfifo", [fifo], { encoding: "utf8" });
    assert.equal(created.status, 0, created.stderr);
    await expectViolation(root, "unsupported filesystem entry");
  }));

test("rejects a symlink repository root", async () => {
  const root = await mkdtemp(join(tmpdir(), "xzwl-frontend-root-"));
  const link = `${root}-link`;
  try {
    await writeValidFixture(root);
    await symlink(root, link);
    await expectViolation(link, "root must be a real directory");
  } finally {
    await rm(link, { force: true });
    await rm(root, { recursive: true, force: true });
  }
});

test("rejects a special repository root", async () => {
  const parent = await mkdtemp(join(tmpdir(), "xzwl-frontend-root-special-"));
  const fifo = join(parent, "root.fifo");
  try {
    const created = spawnSync("mkfifo", [fifo], { encoding: "utf8" });
    assert.equal(created.status, 0, created.stderr);
    await expectViolation(fifo, "root must be a real directory");
  } finally {
    await rm(parent, { recursive: true, force: true });
  }
});

test("rejects network access in an imported module outside src", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/outside.ts"),
      'window["fetch"]("/api/platform/smoke");\n'
    );
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource('import "../outside.ts";\nvoid getPlatformSmoke;')
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("rejects a shared workspace network wrapper", () =>
  withFixture(async (root) => {
    await writePackage(
      root,
      "frontends/packages/network-wrapper",
      { name: "@xzwl/network-wrapper", version: "0.1.0" },
      'export const request = () => globalThis["fetch"]("/api/platform/smoke");\n'
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

const infrastructureCases = [
  ["Nacos", 'const discovery = "nacos";'],
  ["lb://", 'const upstream = "lb://order-service";'],
  ["/internal/", 'const endpoint = "/internal/orders";'],
  ["hard-coded service address", 'const config = { baseURL: "http://order-service:8080" };']
];

for (const [name, source] of infrastructureCases) {
  test(`rejects ${name} in executable configuration`, () =>
    withFixture(async (root) => {
      await write(join(root, "frontends/admin-web/vite.config.ts"), source);
      await expectViolation(root, "forbidden infrastructure reference");
    }));
}

test("rejects a Vue app with a transitive internal React dependency", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/admin-web/package.json"), {
      name: "@xzwl/admin-web",
      dependencies: {
        "@xzwl/api-contracts": "*",
        "@xzwl/ui-shell": "*",
        vue: "3.5.13"
      }
    });
    await writePackage(root, "frontends/packages/ui-shell", {
      name: "@xzwl/ui-shell",
      dependencies: { react: "19.0.0" }
    });
    await expectViolation(root, "Vue app must not reach React through workspace dependencies");
  }));

test("rejects a React app with a transitive internal Vue dependency", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/user-web/package.json"), {
      name: "@xzwl/user-web",
      dependencies: {
        "@xzwl/api-contracts": "*",
        "@xzwl/shared-ui": "*",
        react: "19.0.0"
      }
    });
    await writePackage(root, "frontends/packages/shared-ui", {
      name: "@xzwl/shared-ui",
      dependencies: { vue: "3.5.13" }
    });
    await expectViolation(root, "React app must not reach Vue through workspace dependencies");
  }));

test("handles an internal workspace dependency cycle without looping", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/admin-web/package.json"), {
      name: "@xzwl/admin-web",
      dependencies: {
        "@xzwl/api-contracts": "*",
        "@xzwl/cycle-a": "*",
        vue: "3.5.13"
      }
    });
    await writePackage(root, "frontends/packages/cycle-a", {
      name: "@xzwl/cycle-a",
      dependencies: { "@xzwl/cycle-b": "*" }
    });
    await writePackage(root, "frontends/packages/cycle-b", {
      name: "@xzwl/cycle-b",
      dependencies: { "@xzwl/cycle-a": "*" }
    });
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("does not infer frameworks from unrelated dependency name substrings", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/admin-web/package.json"), {
      name: "@xzwl/admin-web",
      dependencies: {
        "@xzwl/api-contracts": "*",
        "reactive-utils": "1.0.0",
        vue: "3.5.13"
      }
    });
    await writeJson(join(root, "frontends/user-web/package.json"), {
      name: "@xzwl/user-web",
      dependencies: {
        "@xzwl/api-contracts": "*",
        react: "19.0.0",
        "vueable-data": "1.0.0"
      }
    });
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

const unsafeGeneratedClients = [
  [
    "concatenated absolute SDK target",
    `const base = "http" + "://order-service:8080";
export const request = () => fetch(base + "/api/platform/smoke");
`
  ],
  [
    "dynamic SDK template target",
    `const base = process.env.API_BASE;
export const request = () => fetch(\`${"${base}"}/api/platform/smoke\`);
`
  ],
  [
    "dynamic SDK variable target",
    `declare function resolveEndpoint(): string;
const endpoint = resolveEndpoint();
export const request = () => fetch(endpoint);
`
  ],
  [
    "protocol-relative SDK target",
    'export const request = () => fetch("//order-service/api/platform/smoke");\n'
  ],
  [
    "legal decoy with an illegal SDK call",
    `export const decoy = "/api/platform/smoke";
const base = "http" + "://order-service:8080";
export const request = () => fetch(base + "/api/platform/smoke");
`
  ]
];

for (const [name, source] of unsafeGeneratedClients) {
  test(`rejects ${name}`, () =>
    withFixture(async (root) => {
      await write(
        join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
        source
      );
      await expectViolation(root, "generated SDK request target must be statically relative /api/**");
    }));
}

test("rejects a generated SDK getter with an unsafe conditional return", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `const getUrl = () => {
  if (Date.now()) return "https://evil.example/platform/smoke";
  return "/api/platform/smoke";
};
export const request = () => fetch(getUrl());
`
    );
    await expectViolation(root, "generated SDK request target must be statically relative /api/**");
  }));

test("rejects a generated SDK dynamic axios call", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `const axiosModule = await import("axios");
export const safe = () => fetch("/api/platform/smoke");
export const request = () => axiosModule.default.get("https://evil.example/platform/smoke");
`
    );
    await expectViolation(root, "generated SDK may only use fetch for network access");
  }));

test("checks the request argument of fetch.call rather than its thisArg", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      'export const request = () => fetch.call("/api/decoy", "https://evil.example/platform/smoke");\n'
    );
    await expectViolation(root, "generated SDK request target must be statically relative /api/**");
  }));

test("preserves fetch.call request position through an alias", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `const invoke = fetch.call;
export const request = () => invoke("/api/decoy", "https://evil.example/platform/smoke");
`
    );
    await expectViolation(root, "generated SDK request target must be statically relative /api/**");
  }));

test("checks a URL prebound through fetch.bind", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `const request = fetch.bind("/api/decoy", "https://evil.example/platform/smoke");
export const invoke = () => request("/api/decoy");
`
    );
    await expectViolation(root, "generated SDK request target must be statically relative /api/**");
  }));

test("checks the request argument passed through Reflect.apply", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `export const safe = () => fetch("/api/platform/smoke");
export const request = () => Reflect.apply(fetch, null, ["https://evil.example/platform/smoke"]);
`
    );
    await expectViolation(root, "generated SDK request target must be statically relative /api/**");
  }));

test("rejects a generated SDK axios import with a statically concatenated module name", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `const axiosModule = await import("axi" + "os");
export const safe = () => fetch("/api/platform/smoke");
export const request = () => axiosModule.default.get("https://evil.example/platform/smoke");
`
    );
    await expectViolation(root, "generated SDK may only use fetch for network access");
  }));

test("preserves call semantics through fetch.call.bind", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `const invoke = fetch.call.bind(fetch);
export const request = () => invoke("/api/decoy", "https://evil.example/platform/smoke");
`
    );
    await expectViolation(root, "generated SDK request target must be statically relative /api/**");
  }));

test("fails closed when an object callable is reassigned with different call semantics", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `let capabilities = { request: fetch };
capabilities = { request: fetch.call };
export const request = () => capabilities.request?.(
  "/api/decoy",
  "https://evil.example/platform/smoke"
);
`
    );
    await expectViolation(root, "generated SDK request target must be statically relative /api/**");
  }));

test("checks Reflect.apply after it is assigned to an alias", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `const apply = Reflect.apply;
export const request = () => apply(
  fetch,
  null,
  ["https://evil.example/platform/smoke"]
);
`
    );
    await expectViolation(root, "generated SDK request target must be statically relative /api/**");
  }));

test("rejects exporting a raw fetch capability from the generated SDK", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `export const raw = fetch;
export const safe = () => fetch("/api/platform/smoke");
`
    );
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(
        `import { raw } from "@xzwl/api-contracts";
raw("https://evil.example/platform/smoke");`,
        { importSdk: false }
      )
    );
    await expectViolation(root, "generated SDK must not export raw network capability");
  }));

test("rejects a non-fetch Orval client", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/orval.config.ts"),
      'export default { platformSmoke: { output: { client: "axios" } } };\n'
    );
    await expectViolation(root, "Orval client must be fetch");
  }));

test("rejects an Orval baseURL", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/orval.config.ts"),
      `export default {
  platformSmoke: { output: { client: "fetch", baseUrl: "https://api.example.com" } }
};
`
    );
    await expectViolation(root, "Orval baseURL is forbidden");
  }));

test("rejects an Orval mutator", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/orval.config.ts"),
      `export default {
  platformSmoke: {
    output: { client: "fetch", override: { mutator: { path: "./request.ts", name: "request" } } }
  }
};
`
    );
    await expectViolation(root, "Orval mutator is forbidden");
  }));

test("does not allow an unrelated client decoy to satisfy Orval output policy", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/orval.config.ts"),
      `const docs = { client: "fetch" };
void docs;
export default { platformSmoke: { output: {} } };
`
    );
    await expectViolation(root, "Orval client must be fetch");
  }));

test("does not treat Object.assign as an identity wrapper for Orval config", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/orval.config.ts"),
      `const safe = { platformSmoke: { output: { client: "fetch" } } };
const evil = {
  platformSmoke: { output: { client: "axios", baseURL: "https://evil.example" } }
};
export default Object.assign(safe, evil);
`
    );
    await expectViolation(root, "Orval output must be statically analyzable");
  }));

test("rejects a computed Orval baseURL key", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/orval.config.ts"),
      `const baseKey = "base" + "Url";
export default {
  platformSmoke: { output: { client: "fetch", [baseKey]: "https://api.example.com" } }
};
`
    );
    await expectViolation(root, "Orval baseURL is forbidden");
  }));

test("rejects a computed Orval mutator key", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/orval.config.ts"),
      `const mutatorKey = "muta" + "tor";
export default {
  platformSmoke: {
    output: {
      client: "fetch",
      override: { [mutatorKey]: { path: "./request.ts", name: "request" } }
    }
  }
};
`
    );
    await expectViolation(root, "Orval mutator is forbidden");
  }));

test("does not treat comments, display links, or src-like path names as violations", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      `<script setup lang="ts">
${sdkImport}
// nacos lb://order-service /internal/orders http://order-service:8080
// fetch("/api/platform/smoke"); axios.get("/api/platform/smoke");
const documentationLink = "https://example.com/help";
void documentationLink;
void getPlatformSmoke;
</script>
<template><a href="https://example.com/help">Help</a></template>
`
    );
    await write(
      join(root, "frontends/admin-web/not-src-assets/comment-only.ts"),
      '// fetch("/api/platform/smoke"); axios.get("/api/platform/smoke");\n'
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("rejects direct Vue and React cross-framework dependencies", () =>
  withFixture(async (root) => {
    const adminPackage = join(root, "frontends/admin-web/package.json");
    await writeJson(adminPackage, {
      name: "@xzwl/admin-web",
      dependencies: { "@xzwl/api-contracts": "*", vue: "3.5.13", react: "19.0.0" }
    });
    await expectViolation(root, "Vue app must not reach React through workspace dependencies");

    await writeJson(adminPackage, {
      name: "@xzwl/admin-web",
      dependencies: { "@xzwl/api-contracts": "*", vue: "3.5.13" }
    });
    await writeJson(join(root, "frontends/user-web/package.json"), {
      name: "@xzwl/user-web",
      dependencies: { "@xzwl/api-contracts": "*", react: "19.0.0", vue: "3.5.13" }
    });
    await expectViolation(root, "React app must not reach Vue through workspace dependencies");
  }));

test("does not allow a shared package to spoof the fixed admin app identity", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/admin-web/package.json"), {
      name: "not-admin",
      dependencies: { "@xzwl/api-contracts": "*", vue: "3.5.13", react: "19.0.0" }
    });
    await writePackage(root, "frontends/packages/spoofed-admin", {
      name: "@xzwl/admin-web",
      version: "0.1.0"
    });
    await expectViolation(root, "app manifest name must be @xzwl/admin-web");
    await expectViolation(root, "Vue app must not reach React through workspace dependencies");
  }));

test("rejects duplicate internal workspace package names", () =>
  withFixture(async (root) => {
    await writePackage(root, "frontends/packages/duplicate-a", {
      name: "@xzwl/duplicate",
      version: "0.1.0"
    });
    await writePackage(root, "frontends/packages/duplicate-b", {
      name: "@xzwl/duplicate",
      version: "0.1.0"
    });
    await expectViolation(root, "duplicate workspace package name");
  }));

test("does not treat an ambient fetch declaration as a runtime shadow", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(`declare const fetch: typeof globalThis.fetch;
fetch("/api/platform/smoke");`)
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("rejects fetch acquired through nested globalThis window and self roots", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(`globalThis.window.fetch("/api/platform/smoke");
globalThis.self["fetch"]("/api/platform/smoke");`)
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("rejects fetch acquired through a nested globalThis self reference", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(`globalThis.globalThis.fetch("/api/platform/smoke");`)
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("rejects a fetch capability acquired by destructuring assignment", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(`let request;
({ fetch: request } = globalThis);
void request;`)
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("continues to allow a real runtime fetch shadow and pure type query", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(`type NativeFetch = typeof globalThis.fetch;
const fetch = (value: string): string => value;
const local: NativeFetch | null = null;
void fetch("local-only");
void local;`)
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("does not let a locally shadowed require forge an SDK import", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(
        `const require = (name: string) => ({ name });
void require("@xzwl/api-contracts");`,
        { importSdk: false }
      )
    );
    await expectViolation(root, "app source must import @xzwl/api-contracts");
  }));

test("does not mistake locally shadowed require of axios for a network capability", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(`const require = (name: string) => ({ name });
void require("axios");
void getPlatformSmoke;`)
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("recognizes an unshadowed CommonJS SDK require", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(
        `const sdk = require("@xzwl/api-contracts");
void sdk;`,
        { importSdk: false }
      )
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("resolves a generated SDK target from the lexical binding at its call site", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `const endpoint = "/api/platform/smoke";
export function request() {
  return fetch(endpoint);
}
`
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("fails closed when a dynamic parameter shadows a safe SDK target", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `const endpoint = "/api/platform/smoke";
export function request(endpoint = process.env.API_ENDPOINT) {
  return fetch(endpoint);
}
`
    );
    await expectViolation(root, "generated SDK request target must be statically relative /api/**");
  }));

test("fails closed when a generated SDK URL function declaration is reassigned", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `function getUrl() { return "/api/platform/smoke"; }
getUrl = () => "https://evil.example/platform/smoke";
export const request = () => fetch(getUrl());
`
    );
    await expectViolation(root, "generated SDK request target must be statically relative /api/**");
  }));

test("rejects a global network root returned by a reassigned function declaration", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(`function root() { return {}; }
root = () => globalThis;
root().fetch("/api/platform/smoke");`)
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

test("rejects any restricted member exposed by multiple reassigned function roots", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(`function root() { return navigator; }
root = () => globalThis;
root().fetch("/api/platform/smoke");`)
    );
    await expectViolation(root, "business code must not access fetch directly");
  }));

const staticInfrastructureCases = [
  ["Nacos template", "Nacos", "const discovery = `nacos`; void discovery;"],
  ["lb concatenation", "lb://", 'const upstream = "lb" + "://order-service"; void upstream;'],
  [
    "internal path concatenation",
    "/internal/",
    'const internalPath = "/inter" + "nal/orders"; void internalPath;'
  ],
  [
    "hard-coded service address concatenation",
    "hard-coded internal service address",
    'const proxyTarget = "http://" + "order-service:8080"; export default { server: { proxy: { "/api": { target: proxyTarget } } } };'
  ]
];

for (const [label, expected, source] of staticInfrastructureCases) {
  test(`checks statically evaluable ${label}`, () =>
    withFixture(async (root) => {
      await write(join(root, "frontends/admin-web/vite.config.ts"), source);
      await expectViolation(root, expected);
    }));
}

test("does not inspect comments, type literals, or ordinary external display URLs as runtime infrastructure", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/admin-web/src/App.vue"),
      vueSource(`// const discovery = \`nacos\`;
type InternalPath = "/internal/orders";
type Discovery = "nacos";
const documentationLink = "https://example.com/help";
void getPlatformSmoke;
void documentationLink;`)
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("discovers transitive framework dependencies from the root workspace globs", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/admin-web/package.json"), {
      name: "@xzwl/admin-web",
      dependencies: {
        "@xzwl/api-contracts": "*",
        "@xzwl/ui-shell": "workspace:*",
        vue: "3.5.13"
      }
    });
    await writePackage(root, "frontends/ui-shell", {
      name: "@xzwl/ui-shell",
      dependencies: { react: "19.0.0" }
    });
    await expectViolation(root, "Vue app must not reach React through workspace dependencies");
  }));

test("binds workspace protocol aliases to the unique internal package", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/admin-web/package.json"), {
      name: "@xzwl/admin-web",
      dependencies: {
        "@xzwl/api-contracts": "*",
        shell: "workspace:@xzwl/ui-shell@*",
        vue: "3.5.13"
      }
    });
    await writePackage(root, "frontends/packages/ui-shell", {
      name: "@xzwl/ui-shell",
      dependencies: { react: "19.0.0" }
    });
    await expectViolation(root, "Vue app must not reach React through workspace dependencies");
  }));

test("binds npm aliases to internal packages before recursive framework checks", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/admin-web/package.json"), {
      name: "@xzwl/admin-web",
      dependencies: {
        "@xzwl/api-contracts": "*",
        shell: "npm:@xzwl/ui-shell@0.1.0",
        vue: "3.5.13"
      }
    });
    await writePackage(root, "frontends/packages/ui-shell", {
      name: "@xzwl/ui-shell",
      version: "0.1.0",
      dependencies: { react: "19.0.0" }
    });
    await expectViolation(root, "Vue app must not reach React through workspace dependencies");
  }));

for (const [label, specifier] of [
  ["file URL", "file:../packages/ui-shell"],
  ["relative path", "../packages/ui-shell"]
]) {
  test(`binds a ${label} alias to the unique internal package`, () =>
    withFixture(async (root) => {
      await writeJson(join(root, "frontends/admin-web/package.json"), {
        name: "@xzwl/admin-web",
        dependencies: {
          "@xzwl/api-contracts": "*",
          shell: specifier,
          vue: "3.5.13"
        }
      });
      await writePackage(root, "frontends/packages/ui-shell", {
        name: "@xzwl/ui-shell",
        dependencies: { react: "19.0.0" }
      });
      await expectViolation(root, "Vue app must not reach React through workspace dependencies");
    }));
}

test("rejects a local workspace dependency whose declared alias does not match its target package", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/admin-web/package.json"), {
      name: "@xzwl/admin-web",
      dependencies: {
        "@xzwl/api-contracts": "*",
        "@xzwl/ui-shell": "file:../packages/different",
        vue: "3.5.13"
      }
    });
    await writePackage(root, "frontends/packages/different", {
      name: "@xzwl/different",
      dependencies: { react: "19.0.0" }
    });
    await expectViolation(root, "local workspace dependency name mismatch");
  }));

test("rejects a local dependency path that escapes the repository root", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "frontends/admin-web/package.json"), {
      name: "@xzwl/admin-web",
      dependencies: {
        "@xzwl/api-contracts": "*",
        shell: "file:../../../outside",
        vue: "3.5.13"
      }
    });
    await expectViolation(root, "workspace dependency escapes repository root");
  }));

test("rejects a root workspace pattern that escapes the repository", () =>
  withFixture(async (root) => {
    await writeJson(join(root, "package.json"), {
      private: true,
      workspaces: ["frontends/*", "frontends/packages/*", "../outside/*"]
    });
    await expectViolation(root, "unsafe workspace pattern");
  }));

for (const [label, target] of [
  ["dot segment", "/api/../internal/orders"],
  ["in-api parent dot segment", "/api/platform/../smoke"],
  ["in-api current dot segment", "/api/platform/./smoke"],
  ["encoded dot segment", "/api/%2e%2e/internal/orders"],
  ["double-encoded dot segment", "/api/%252e%252e%252finternal/orders"],
  ["encoded slash escape", "/api/%2e%2e%2Finternal/orders"],
  ["backslash", "/api/..\\\\internal/orders"],
  ["protocol relative", "//order-service/api/platform/smoke"]
]) {
  test(`rejects a generated SDK ${label} target`, () =>
    withFixture(async (root) => {
      await write(
        join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
        `export const request = () => fetch(${JSON.stringify(target)});\n`
      );
      await expectViolation(root, "generated SDK request target must be statically relative /api/**");
    }));
}

test("accepts a same-origin relative SDK URL with query and hash under /api/", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `export const request = () => fetch("/api/platform/smoke?probe=1#result");\n`
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("allows percent encoding in an SDK query when the pathname remains safe", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/src/generated/platform-smoke.ts"),
      `export const request = () => fetch("/api/search?q=a%2Fb");\n`
    );
    assert.deepEqual(await validateFrontendBoundaries(root), []);
  }));

test("rejects an unanalyzable Orval operation even when a safe output is present", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/orval.config.ts"),
      `export default {
  safe: { output: { client: "fetch" } },
  hidden: JSON.parse("{}")
};
`
    );
    await expectViolation(root, "Orval operation must be statically analyzable");
  }));

test("rejects a function-valued Orval operation even when a safe output is present", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/orval.config.ts"),
      `export default {
  safe: { output: { client: "fetch" } },
  hidden: () => ({ output: { client: "fetch" } })
};
`
    );
    await expectViolation(root, "Orval operation must be statically analyzable");
  }));

test("rejects a dynamically parsed Orval output", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/orval.config.ts"),
      `export default {
  platformSmoke: { output: JSON.parse('{"client":"fetch"}') }
};
`
    );
    await expectViolation(root, "Orval output must be statically analyzable");
  }));

test("rejects an Orval operation with no statically unique output", () =>
  withFixture(async (root) => {
    await write(
      join(root, "frontends/packages/api-contracts/orval.config.ts"),
      `export default {
  safe: { output: { client: "fetch" } },
  inputOnly: { input: "./openapi.json" }
};
`
    );
    await expectViolation(root, "Orval operation output must be statically analyzable");
  }));

test("accepts the actual repository", async () => {
  assert.deepEqual(await validateFrontendBoundaries(repositoryRoot), []);
});
