// Bundles the dev harness with React included and the Perspective client stubbed.
import * as esbuild from "esbuild";
import path from "node:path";

const outdir = process.argv[2];
await esbuild.build({
    entryPoints: ["dev/config-harness.tsx"],
    bundle: true,
    format: "iife",
    outfile: path.join(outdir, "config-harness.js"),
    define: { "process.env.NODE_ENV": '"development"' },
    sourcemap: "inline",
    logLevel: "warning",
});

await esbuild.build({
    entryPoints: ["dev/harness.tsx"],
    bundle: true,
    format: "iife",
    outfile: path.join(outdir, "harness.js"),
    alias: { "@inductiveautomation/perspective-client": "./dev/fake-perspective.ts" },
    define: { "process.env.NODE_ENV": '"development"' },
    sourcemap: "inline",
    logLevel: "warning",
});
