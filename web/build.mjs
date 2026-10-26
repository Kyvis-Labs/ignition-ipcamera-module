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

// The gateway web UI pages are loaded by the gateway's SystemJS (with its AMD extra) and are
// built from the gateway's own React, form and component libraries, which its import map
// supplies. They are bundled as CommonJS with those libraries external, and wrapped in
// define([...]) so SystemJS hands them in as the require() results.
// (The names are the gateway's SystemJsModuleRegistryImpl import map; redux is listed for the
// RTK Query endpoints the pages inject into the gateway's BaseApi.)
const gatewayLibs = [
    "react",
    "react-dom",
    "react-hook-form",
    "react-router-dom",
    "react-redux",
    "@reduxjs/toolkit",
    "@inductiveautomation/ignition-web-ui",
    "@inductiveautomation/ignition-gateway-lib",
    "@inductiveautomation/ignition-icons",
];

await esbuild.build({
    entryPoints: { "config-page": "src/config/index.ts" },
    bundle: true,
    format: "cjs",
    target: "es2020",
    outdir,
    sourcemap: "linked",
    minify: process.env.NODE_ENV === "production",
    external: gatewayLibs,
    banner: {
        js: `define(${JSON.stringify(gatewayLibs)}, function () {\n`
            + "var __libs = {}, __args = arguments;\n"
            + `${JSON.stringify(gatewayLibs)}.forEach(function (n, i) { __libs[n] = __args[i]; });\n`
            + "var require = function (n) { if (!(n in __libs)) throw new Error('not in the gateway import map: ' + n); return __libs[n]; };\n"
            + "var module = { exports: {} }, exports = module.exports;",
    },
    footer: { js: "return module.exports;\n});" },
    logLevel: "info",
});

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
