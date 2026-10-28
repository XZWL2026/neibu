import { defineConfig } from "orval";
import { resolve } from "node:path";

const outputDir =
  process.env.ORVAL_OUTPUT_DIR ??
  resolve(process.cwd(), "frontends/packages/api-contracts/src/generated");

export default defineConfig({
  platformSmoke: {
    input: resolve(process.cwd(), "contracts/openapi/public/platform-smoke.json"),
    output: {
      target: outputDir + "/platform-smoke.ts",
      client: "fetch"
    }
  }
});
