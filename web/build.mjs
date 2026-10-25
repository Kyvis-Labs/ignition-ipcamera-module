// Bundles src/index.ts into <outdir>/camera-components.js (+ .css).
//
// React, ReactDOM and the Perspective client are provided by the Perspective
// runtime as window globals and must NOT be bundled: a second React copy breaks
// hooks, and a second PerspectiveClient means a second ComponentRegistry that
// nothing reads. esbuild has no "externals as globals" for an IIFE, so a tiny
// plugin rewrites those imports to the globals.
import * as esbuild from "esbuild";

const outdir = process.argv[2] ?? "dist";

const globals = {
    "react": "React",
    "react-dom": "ReactDOM",
    "@inductiveautomation/perspective-client": "PerspectiveClient",
};

const globalsPlugin = {
    name: "globals",
    setup(build) {
        const filter = new RegExp(`^(${Object.keys(globals).map(k => k.replace(/[/@-]/g, "\\$&")).join("|")})$`);
        build.onResolve({ filter }, args => ({ path: args.path, namespace: "global" }));
        build.onLoad({ filter: /.*/, namespace: "global" }, args => ({
            contents: `module.exports = window[${JSON.stringify(globals[args.path])}];`,
            loader: "js",
        }));
    },
};

await esbuild.build({
    entryPoints: { "camera-components": "src/index.ts" },
    bundle: true,
    format: "iife",
    target: "es2020",
    outdir,
    sourcemap: "linked",
    minify: process.env.NODE_ENV === "production",
    plugins: [globalsPlugin],
    logLevel: "info",
});
